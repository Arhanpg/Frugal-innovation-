package com.arhan.frugalcctv.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arhan.frugalcctv.web.WebRtcSession
import com.arhan.frugalcctv.domain.SignalMessage
import com.arhan.frugalcctv.domain.IceConfig
import com.arhan.frugalcctv.domain.SecuritySettings
import com.arhan.frugalcctv.data.SignalingRepository
import kotlinx.coroutines.*
import org.webrtc.SurfaceViewRenderer

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var signaling: SignalingRepository? = null
    private var rtc: WebRtcSession? = null
    private var detector: ThreatDetector? = null
    private var settings = SecuritySettings()

    inner class LocalBinder : Binder() { fun getService() = this@CameraService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "frugal_camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FrugalCCTV Camera", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId).setContentTitle("FrugalCCTV Active").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
        return START_STICKY
    }

    fun start(url: String, key: String, room: String, ice: IceConfig, security: SecuritySettings) {
        settings = security
        detector = ThreatDetector(settings.confidenceThreshold) { conf ->
            if (settings.armed) {
                scope.launch { signaling?.send(SignalMessage(type = "alert", from = signaling?.id() ?: "", text = "Person detected!")) }
            }
        }
        signaling = SignalingRepository(url, key, room, scope)
        rtc = WebRtcSession(this, captureCamera = true, onIce = { c ->
            scope.launch { signaling?.send(SignalMessage(type = "ice", from = signaling?.id() ?: "", candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) }
        }, onRemoteVideo = {}, onConnection = {}, onFrame = { f -> detector?.onFrame(f) })

        signaling?.start { msg ->
            when (msg.type) {
                "offer" -> rtc?.createPeer(ice)?.let {
                    rtc?.setRemote(org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, msg.sdp))
                    rtc?.createAnswer { ans -> scope.launch { signaling?.send(SignalMessage(type = "answer", from = signaling?.id() ?: "", to = msg.from, sdp = ans.description)) } }
                }
                "ice" -> rtc?.addIce(org.webrtc.IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, msg.candidate ?: ""))
                "arm" -> settings = settings.copy(armed = msg.armed ?: false)
            }
        }
    }

    fun attachPreview(view: SurfaceViewRenderer) = rtc?.attachPreview(view)

    override fun onDestroy() {
        rtc?.release(); signaling?.close(); detector?.close(); scope.cancel()
        super.onDestroy()
    }
}
