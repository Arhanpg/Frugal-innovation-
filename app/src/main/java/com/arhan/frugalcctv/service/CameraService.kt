package com.arhan.frugalcctv.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arhan.frugalcctv.data.AppPreferences
import com.arhan.frugalcctv.data.RoomLeaseRepository
import com.arhan.frugalcctv.data.SignalingRepository
import com.arhan.frugalcctv.domain.IceConfig
import com.arhan.frugalcctv.domain.SecuritySettings
import com.arhan.frugalcctv.domain.SignalMessage
import com.arhan.frugalcctv.web.WebRtcSession
import kotlinx.coroutines.*
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signaling: SignalingRepository? = null
    private var lease: RoomLeaseRepository? = null
    private var heartbeatJob: Job? = null
    private var rtc: WebRtcSession? = null
    private var detector: ThreatDetector? = null
    private var settings = SecuritySettings()
    private var preview: SurfaceViewRenderer? = null
    private var lastAlert = 0L
    private var tone: ToneGenerator? = null
    private var running = false

    inner class LocalBinder : Binder() { fun getService() = this@CameraService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "frugal_camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "FrugalCCTV Camera", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle("FrugalCCTV camera").setContentText("Camera is running").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(1, notification)
        return START_NOT_STICKY
    }

    fun start(url: String, key: String, room: String, ice: IceConfig, security: SecuritySettings, state: (PeerConnection.IceConnectionState) -> Unit = {}) {
        if (running) return
        val normalizedUrl = url.trim(); val normalizedKey = key.trim(); val normalizedRoom = room.trim().uppercase()
        if (normalizedUrl.isBlank() || normalizedKey.isBlank() || normalizedRoom.isBlank()) { failStart("Supabase URL, publishable key and room code are required"); return }
        running = true; settings = security; tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        val prefs = AppPreferences(this); val deviceId = prefs.deviceId(); val roomLease = RoomLeaseRepository(normalizedUrl, normalizedKey, normalizedRoom, deviceId); lease = roomLease
        scope.launch {
            try {
                if (!roomLease.claim()) { failStart(roomLease.lastError() ?: "Room $normalizedRoom is already in use or could not be claimed"); return@launch }
                heartbeatJob = launch { while (isActive && running) { delay(5_000L); if (roomLease.heartbeat() != true) { failStart(roomLease.lastError() ?: "Camera room lease heartbeat failed"); break } } }
                startStreaming(normalizedUrl, normalizedKey, normalizedRoom, IceConfig(prefs.turnUrls(), prefs.turnUser(), prefs.turnPassword()).let { if (it.turnUrls.isEmpty()) ice else it }, state, deviceId)
            } catch (t: Throwable) { failStart(t.message ?: t::class.simpleName ?: "Camera startup failed") }
        }
    }

    private fun startStreaming(url: String, key: String, room: String, ice: IceConfig, state: (PeerConnection.IceConnectionState) -> Unit, deviceId: String) {
        signaling = SignalingRepository(url, key, room, scope, deviceId)
        detector = ThreatDetector(settings.confidenceThreshold) { event ->
            if (!settings.armed) return@ThreatDetector
            val now = System.currentTimeMillis(); if (now - lastAlert < 15_000L) return@ThreatDetector; lastAlert = now
            scope.launch { val sender = signaling ?: return@launch; runCatching { sender.send(SignalMessage("alert", sender.id(), text = event.message)) }; AlertNotifier.notify(this@CameraService, "FrugalCCTV alert", event.message, playTone = settings.audibleAlarm) }
        }
        rtc = WebRtcSession(this, true,
            onIce = { c -> scope.launch { signaling?.send(SignalMessage("ice", signaling?.id() ?: "", candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) } },
            onRemoteVideo = {},
            onConnection = state,
            onFrame = { detector?.onFrame(it) },
            onError = { android.util.Log.e("FrugalCCTV", "WebRTC error: $it") },
            onFatalError = { failStart(it) }
        )
        preview?.let { rtc?.attachPreview(it) }
        signaling?.start(role = "camera", onMessage = { msg ->
            when (msg.type) {
                "hello" -> scope.launch { val sender = signaling ?: return@launch; sender.send(SignalMessage("ready", sender.id(), to = msg.from)); sender.send(SignalMessage("arm", sender.id(), to = msg.from, armed = settings.armed)) }
                "offer" -> try { rtc?.resetPeer(); rtc?.createPeer(ice); msg.sdp?.let { sdp -> rtc?.setRemote(org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp)) { rtc?.createAnswer { answer -> scope.launch { signaling?.send(SignalMessage("answer", signaling?.id() ?: "", to = msg.from, sdp = answer.description)) } } } } } catch (e: Exception) { failStart(e.message ?: "WebRTC negotiation failed") }
                "ice" -> msg.candidate?.let { rtc?.addIce(org.webrtc.IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, it)) }
                "arm" -> settings = settings.copy(armed = msg.armed ?: false)
            }
        })
    }

    private fun failStart(reason: String) {
        if (!running) return
        running = false; heartbeatJob?.cancel(); heartbeatJob = null
        val message = "Camera could not start: ${reason.take(260)}"; runCatching { AlertNotifier.notify(this, "FrugalCCTV camera error", message, playTone = false) }
        stopCameraResources(); lease?.let { current -> scope.launch { current.release(); current.close() } }; lease = null; stopSelf()
    }
    fun attachPreview(view: SurfaceViewRenderer) { preview = view; rtc?.attachPreview(view) }
    fun setArmed(value: Boolean) { settings = settings.copy(armed = value) }
    fun setAudible(value: Boolean) { settings = settings.copy(audibleAlarm = value) }
    fun isArmed() = settings.armed
    fun isAudible() = settings.audibleAlarm
    fun stopCamera() { if (!running) { stopSelf(); return }; running = false; heartbeatJob?.cancel(); heartbeatJob = null; val currentLease = lease; scope.launch { currentLease?.release(); stopCameraResources(); currentLease?.close(); lease = null; stopSelf() } }
    private fun stopCameraResources() { runCatching { rtc?.release() }; rtc = null; signaling?.close(); signaling = null; detector?.close(); detector = null; tone?.release(); tone = null; preview = null }
    override fun onDestroy() { running = false; heartbeatJob?.cancel(); val currentLease = lease; stopCameraResources(); if (currentLease != null) scope.launch { currentLease.release(); currentLease.close() }; scope.cancel(); super.onDestroy() }
}
