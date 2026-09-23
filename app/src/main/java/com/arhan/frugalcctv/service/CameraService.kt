package com.arhan.frugalcctv.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arhan.frugalcctv.data.AppPreferences
import com.arhan.frugalcctv.data.DirectSignalingServer
import com.arhan.frugalcctv.data.getLocalIpAddress
import com.arhan.frugalcctv.domain.*
import com.arhan.frugalcctv.web.WebRtcSession
import kotlinx.coroutines.*
import org.webrtc.*

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signaling: DirectSignalingServer? = null
    private var rtc: WebRtcSession? = null
    private var detector: ThreatDetector? = null
    private var settings = SecuritySettings()
    private var running = false
    private var viewerId: String? = null
    private var preview: SurfaceViewRenderer? = null
    private var room = ""
    private var criticalAt = 0L
    private var warningAt = 0L

    private var torchOn = false
    private var sirenActive = false
    private var sirenTone: ToneGenerator? = null
    private var batteryPercent = 100
    private var isCharging = false
    private var telemetryJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPercent = (level * 100) / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
    }

    inner class LocalBinder : Binder() { fun getService() = this@CameraService }
    override fun onBind(i: Intent): IBinder = LocalBinder()

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val channel = "frugal_camera"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "FrugalCCTV Camera", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, channel)
            .setContentTitle("FrugalCCTV camera active")
            .setContentText("Direct LAN CCTV streaming in progress")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)

        return START_NOT_STICKY
    }

    fun start(roomCode: String, security: SecuritySettings) {
        if (running) return
        room = roomCode.trim().uppercase()
        if (room.isBlank()) return failStart("Room code is required")
        running = true
        settings = security
        viewerId = null
        val id = AppPreferences(this).deviceId()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        detector = ThreatDetector(settings.confidenceThreshold) { event ->
            if (settings.armed) publish(event)
        }

        rtc = WebRtcSession(this, true,
            onIce = { c -> signaling?.send(SignalMessage("ice", id, viewerId, candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) },
            onRemoteVideo = {},
            onConnection = {},
            onFrame = { detector?.onFrame(it) },
            onError = { Log.e("FrugalCCTV", it) },
            onFatalError = { failStart(it) }
        )

        signaling = DirectSignalingServer(scope, room, id, { m -> handle(m, id) }, { if (it == 0) viewerId = null }, { failStart(it) }).also { it.start() }

        startTelemetry(id)
    }

    private fun startTelemetry(deviceId: String) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                if (viewerId != null) {
                    val msg = SignalMessage(
                        type = "status",
                        from = deviceId,
                        to = viewerId,
                        armed = settings.armed,
                        torch = torchOn,
                        siren = sirenActive,
                        battery = batteryPercent,
                        charging = isCharging,
                        ipAddress = getLocalIpAddress()
                    )
                    signaling?.send(msg)
                }
                delay(3000)
            }
        }
    }

    private fun handle(m: SignalMessage, id: String) {
        when (m.type) {
            "hello" -> {
                viewerId = m.from
                signaling?.send(SignalMessage("ready", id, m.from))
                sendState(id, m.from)
            }
            "offer" -> {
                viewerId = m.from
                try {
                    rtc?.resetPeer()
                    rtc?.createPeer()
                    val sdp = m.sdp ?: return
                    rtc?.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp)) {
                        rtc?.createAnswer { answer -> signaling?.send(SignalMessage("answer", id, m.from, sdp = answer.description)) }
                    }
                } catch (t: Throwable) {
                    Log.e("FrugalCCTV", "Offer handling failed", t)
                    rtc?.resetPeer()
                }
            }
            "ice" -> m.candidate?.let { rtc?.addIce(IceCandidate(m.sdpMid ?: "", m.sdpMLineIndex ?: 0, it)) }
            "arm" -> {
                m.armed?.let { setArmed(it) }
                sendState(id, m.from)
            }
            "torch" -> {
                m.torch?.let { setTorch(it) }
                sendState(id, m.from)
            }
            "flip" -> {
                flipCamera()
            }
            "siren" -> {
                m.siren?.let { triggerSiren(it) }
                sendState(id, m.from)
            }
        }
    }

    private fun sendState(id: String, toId: String?) {
        signaling?.send(
            SignalMessage(
                type = "status",
                from = id,
                to = toId,
                armed = settings.armed,
                torch = torchOn,
                siren = sirenActive,
                battery = batteryPercent,
                charging = isCharging,
                ipAddress = getLocalIpAddress()
            )
        )
    }

    fun setTorch(enable: Boolean) {
        runCatching {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (backCameraId != null) {
                cameraManager.setTorchMode(backCameraId, enable)
                torchOn = enable
            }
        }
    }

    fun flipCamera() {
        rtc?.switchCamera()
    }

    fun triggerSiren(enable: Boolean) {
        sirenActive = enable
        if (enable) {
            runCatching {
                sirenTone?.release()
                sirenTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                sirenTone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 4000)
            }
        } else {
            runCatching {
                sirenTone?.stopTone()
                sirenTone?.release()
            }
            sirenTone = null
        }
    }

    private fun publish(event: SecurityEvent) {
        val now = System.currentTimeMillis()
        val cooldown = if (event.severity == Severity.CRITICAL) 3000L else 4000L
        val last = if (event.severity == Severity.CRITICAL) criticalAt else warningAt
        if (now - last < cooldown) return
        if (event.severity == Severity.CRITICAL) criticalAt = now else warningAt = now
        val id = AppPreferences(this).deviceId()
        signaling?.send(SignalMessage("alert", id, viewerId, text = event.message))
        AlertNotifier.notify(this, "FrugalCCTV alert", event.message, settings.audibleAlarm)
    }

    private fun failStart(reason: String) {
        if (!running) return
        running = false
        AlertNotifier.notify(this, "FrugalCCTV camera error", reason.take(240), false)
        stopResources()
        stopSelf()
    }

    fun attachPreview(v: SurfaceViewRenderer) { preview = v; rtc?.attachPreview(v) }
    fun setArmed(v: Boolean) { settings = settings.copy(armed = v) }
    fun setAudible(v: Boolean) { settings = settings.copy(audibleAlarm = v) }
    fun isArmed() = settings.armed
    fun isAudible() = settings.audibleAlarm
    fun stopCamera() { running = false; stopResources(); stopSelf() }

    private fun stopResources() {
        telemetryJob?.cancel()
        runCatching { unregisterReceiver(batteryReceiver) }
        triggerSiren(false)
        setTorch(false)
        runCatching { rtc?.release() }; rtc = null
        signaling?.close(); signaling = null
        detector?.close(); detector = null
        viewerId = null; preview = null
    }

    override fun onDestroy() { running = false; stopResources(); scope.cancel(); super.onDestroy() }
}
