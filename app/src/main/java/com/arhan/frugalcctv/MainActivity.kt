package com.arhan.frugalcctv

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arhan.frugalcctv.data.*
import com.arhan.frugalcctv.domain.*
import com.arhan.frugalcctv.service.*
import com.arhan.frugalcctv.web.WebRtcSession
import kotlinx.coroutines.*
import org.webrtc.*

private fun notificationPermission(c: Context) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) (c as? ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
}

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<CameraService?>(null)
    private var pendingRoom: String? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = (b as? CameraService.LocalBinder)?.getService()
            pendingRoom?.let { startCameraService(it) }
        }
        override fun onServiceDisconnected(n: ComponentName?) { service = null }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { Theme { Root(service) } }
    }

    private fun startCamera(room: String) {
        pendingRoom = room.trim().uppercase()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 41); return
        }
        notificationPermission(this)
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
        startCameraService(room)
    }

    private fun startCameraService(room: String) {
        service?.start(
            room,
            SecuritySettings(
                armed = AppPreferences(this).armed(),
                audibleAlarm = AppPreferences(this).audible()
            )
        )
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 41 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) pendingRoom?.let(::startCamera)
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    @Composable
    private fun Root(camera: CameraService?) {
        var mode by remember { mutableStateOf(AppMode.HOME) }
        var room by remember { mutableStateOf("HOME-4827") }
        when (mode) {
            AppMode.HOME -> Home({ mode = AppMode.CAMERA_SETUP }, { mode = AppMode.VIEWER_SETUP })
            AppMode.CAMERA_SETUP -> Setup("Camera", "Direct local streaming — no cloud service.", room, { room = it }, false, { startCamera(room); mode = AppMode.CAMERA }, { mode = AppMode.HOME })
            AppMode.CAMERA -> Camera(room, camera) { camera?.stopCamera(); mode = AppMode.HOME }
            AppMode.VIEWER_SETUP -> Setup("Viewer", "Use the same room code as the camera.", room, { room = it }, true, { mode = AppMode.VIEWER }, { mode = AppMode.HOME })
            AppMode.VIEWER -> Viewer(room) { mode = AppMode.HOME }
        }
    }
}

@Composable private fun Theme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF73E0B1),
        background = Color(0xFF07110E),
        surface = Color(0xFF0D1B17)
    ), content = content
)

@Composable private fun Home(camera: () -> Unit, viewer: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("FRUGAL", color = Color(0xFF73E0B1), fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Text("Smart security.\nNo cloud required.", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Action("Camera mode", "Turn this phone into the camera", Icons.Default.Videocam, camera)
        Spacer(Modifier.height(12.dp))
        Action("Viewer mode", "Watch another phone directly", Icons.Default.Visibility, viewer)
    }
}

@Composable private fun Action(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF73E0B1)); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(desc, fontSize = 13.sp) }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable private fun Setup(title: String, subtitle: String, room: String, roomChange: (String) -> Unit, viewer: Boolean, start: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color(0xFF8FA59E))
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(room, roomChange, Modifier.fillMaxWidth(), label = { Text("Room code") }, singleLine = true)
        Spacer(Modifier.height(18.dp))
        if (viewer) Text("Both devices must be reachable on the same LAN/Wi-Fi. If discovery is blocked by the router, the viewer can be extended with manual IP entry.", fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Button(onClick = start, Modifier.fillMaxWidth().height(54.dp)) { Text(if (viewer) "Find camera" else "Start camera") }
        TextButton(onClick = back) { Text("Back") }
    }
}

@Composable private fun Camera(room: String, service: CameraService?, back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { AppPreferences(ctx) }
    var armed by remember { mutableStateOf(prefs.armed()) }
    var audible by remember { mutableStateOf(prefs.audible()) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ back() }) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Column(Modifier.weight(1f)) { Text("CAMERA", color = Color.White, fontWeight = FontWeight.Bold); Text("Room $room", color = Color.Gray) }
            Text(if (armed) "ARMED" else "OFF", color = if (armed) Color(0xFF73E0B1) else Color.Yellow)
        }
        AndroidView({ SurfaceViewRenderer(it) }, update = { service?.attachPreview(it) }, Modifier.fillMaxWidth().weight(1f).padding(8.dp))
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ armed = !armed; service?.setArmed(armed); prefs.saveArmed(armed) }, Modifier.weight(1f)) { Text(if (armed) "Disarm" else "Arm") }
            Button({ audible = !audible; service?.setAudible(audible); prefs.saveAudible(audible) }, Modifier.weight(1f)) { Text(if (audible) "Alarm off" else "Alarm on") }
        }
    }
}

@Composable private fun Viewer(room: String, back: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Searching local network…") }
    var alert by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<ViewerController?>(null) }

    LaunchedEffect(Unit) { notificationPermission(ctx) }
    DisposableEffect(room) {
        val c = ViewerController(ctx, room, { status = it }, {
            alert = it
            AlertNotifier.notify(ctx, "FrugalCCTV alert", it, true)
        }, { armed = it })
        controller = c
        c.start()
        onDispose { c.release(); controller = null }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ back() }) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Column(Modifier.weight(1f)) { Text("LIVE VIEW", color = Color.White, fontWeight = FontWeight.Bold); Text("Room $room", color = Color.Gray) }
            Text(if (status == "Connected" || status == "Live video received") "LIVE" else "WAITING", color = Color(0xFF73E0B1))
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
            AndroidView({ SurfaceViewRenderer(it) }, update = { controller?.attachPreview(it) }, Modifier.fillMaxSize())
            if (status != "Connected" && status != "Live video received") Text(status, color = Color.Gray)
            alert?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(14.dp), color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Text(if (armed) "Camera armed" else "Camera disarmed", color = if (armed) Color(0xFF73E0B1) else Color.Yellow, modifier = Modifier.padding(12.dp))
        Button({ if (status == "Connected" || status == "Live video received") { armed = !armed; controller?.setArmed(armed) } else controller?.reconnect() }, Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if (status == "Connected" || status == "Live video received") if (armed) "Disarm" else "Arm" else "Retry")
        }
    }
}

private class ViewerController(
    context: Context,
    private val room: String,
    private val onState: (String) -> Unit,
    private val onAlert: (String) -> Unit,
    private val onArmed: (Boolean) -> Unit
) {
    private val app = context.applicationContext
    private val prefs = AppPreferences(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val id = prefs.deviceId()
    private var endpoint: CameraEndpoint? = null
    private var cameraId: String? = null
    private var negotiating = false
    private var discovery: CameraDiscovery? = null

    private val signaling = DirectSignalingClient(scope, room, id, ::handle,
        { state("Signaling connected — negotiating…") },
        { if (scope.isActive) state("Camera disconnected") },
        { state("Signaling error: " + it) })

    private val rtc = WebRtcSession(app, false,
        onIce = { c -> signaling.send(SignalMessage("ice", id, cameraId, candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) },
        onRemoteVideo = { state("Live video received") },
        onConnection = { s ->
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> state("Connected")
                PeerConnection.IceConnectionState.CHECKING -> state("Connecting video…")
                PeerConnection.IceConnectionState.FAILED -> { negotiating = false; state("Video failed — retrying…"); reconnect() }
                PeerConnection.IceConnectionState.DISCONNECTED -> state("Video connection interrupted")
                else -> Unit
            }
        },
        onError = { state("WebRTC error: $it") })

    fun start() {
        state("Searching for camera on local network…")
        discovery = CameraDiscovery(scope, room, { found ->
            if (endpoint == null) {
                endpoint = found
                cameraId = found.cameraId
                prefs.saveCameraEndpoint(found.host, found.port)
                discovery?.stop()
                signaling.connect(found)
            }
        }, { state("Discovery failed — retry connection or check Wi-Fi") })
        discovery?.start()
    }

    private fun handle(m: SignalMessage) {
        when (m.type) {
            "ready" -> {
                cameraId = m.from
                if (!negotiating) negotiate(m.from)
            }
            "answer" -> m.sdp?.let { rtc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, it)) }
            "ice" -> m.candidate?.let { rtc.addIce(IceCandidate(m.sdpMid ?: "", m.sdpMLineIndex ?: 0, it)) }
            "alert" -> m.text?.let(onAlert)
            "arm" -> m.armed?.let(onArmed)
        }
    }

    private fun negotiate(remote: String) {
        if (negotiating && cameraId == remote) return
        negotiating = true
        cameraId = remote
        rtc.resetPeer()
        rtc.createPeer()
        rtc.createOffer { offer -> signaling.send(SignalMessage("offer", id, remote, sdp = offer.description)) }
    }

    fun reconnect() {
        negotiating = false
        rtc.resetPeer()
        endpoint?.let { signaling.connect(it) } ?: start()
    }

    fun attachPreview(v: SurfaceViewRenderer) = rtc.attachPreview(v)
    fun setArmed(value: Boolean) = signaling.send(SignalMessage("arm", id, cameraId, armed = value))

    fun release() {
        discovery?.stop()
        signaling.close()
        rtc.release()
        scope.cancel()
    }

    private fun state(s: String) = scope.launch { onState(s) }
}

@Composable private fun Pill(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = Color(0xAA15231F)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, color = tint)
    }
}
