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
    private var rtc: WebRtcSession? = null
    private var detector: ThreatDetector? = null
    private var settings = SecuritySettings()
    private var preview: SurfaceViewRenderer? = null
    private var lastAlert = 0L
    private var tone: ToneGenerator? = null

    inner class LocalBinder : Binder() { fun getService() = this@CameraService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "frugal_camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "FrugalCCTV Camera", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle("FrugalCCTV is protecting this device").setContentText("Camera service is running").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(1, notification)
        return START_STICKY
    }

    fun start(url: String, key: String, room: String, ice: IceConfig, security: SecuritySettings, state: (PeerConnection.IceConnectionState) -> Unit = {}) {
        if (rtc != null) return
        require(url.isNotBlank() && key.isNotBlank()) { "Supabase URL and publishable key are required" }
        settings = security
        detector = ThreatDetector(settings.confidenceThreshold) { confidence ->
            if (!settings.armed) return@ThreatDetector
            val now = System.currentTimeMillis()
            if (now - lastAlert < 15_000L) return@ThreatDetector
            lastAlert = now
            scope.launch {
                signaling?.send(SignalMessage("alert", signaling?.id() ?: "", text = "Person detected • ${(confidence * 100).toInt()}% confidence"))
                if (settings.audibleAlarm) tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            }
        }
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        signaling = SignalingRepository(url, key, room, scope)
        rtc = WebRtcSession(this, true,
            onIce = { c -> scope.launch { signaling?.send(SignalMessage("ice", signaling?.id() ?: "", candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) } },
            onRemoteVideo = {}, onConnection = { state(it) }, onFrame = { detector?.onFrame(it) }
        )
        preview?.let { rtc?.attachPreview(it) }
        signaling?.start { msg ->
            when (msg.type) {
                "hello" -> scope.launch { signaling?.send(SignalMessage("ready", signaling?.id() ?: "", to = msg.from)) }
                "offer" -> {
                    rtc?.createPeer(ice)
                    msg.sdp?.let { rtc?.setRemote(org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, it)) }
                    rtc?.createAnswer { answer -> scope.launch { signaling?.send(SignalMessage("answer", signaling?.id() ?: "", to = msg.from, sdp = answer.description)) } }
                }
                "ice" -> msg.candidate?.let { rtc?.addIce(org.webrtc.IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, it)) }
                "arm" -> settings = settings.copy(armed = msg.armed ?: false)
            }
        }
    }
    fun attachPreview(view: SurfaceViewRenderer) { preview = view; rtc?.attachPreview(view) }
    fun setArmed(value: Boolean) { settings = settings.copy(armed = value) }
    fun setAudible(value: Boolean) { settings = settings.copy(audibleAlarm = value) }
    fun isArmed() = settings.armed
    override fun onDestroy() { rtc?.release(); signaling?.close(); detector?.close(); tone?.release(); preview = null; scope.cancel(); super.onDestroy() }
}
