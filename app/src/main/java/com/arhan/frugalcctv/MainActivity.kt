package com.arhan.frugalcctv

import android.Manifest
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.arhan.frugalcctv.data.AppPreferences
import com.arhan.frugalcctv.domain.AppMode
import com.arhan.frugalcctv.domain.IceConfig
import com.arhan.frugalcctv.domain.SecuritySettings
import com.arhan.frugalcctv.domain.SignalMessage
import com.arhan.frugalcctv.service.CameraService
import com.arhan.frugalcctv.web.WebRtcSession
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.Locale

private const val DEFAULT_URL = "https://ihscwvpvtawjnskqmuja.supabase.co"
private const val DEFAULT_KEY = "sb_publishable_Q6RogD9n7TncYd2YdjGq7w_AVAYkkqN"

class MainActivity : ComponentActivity() {
    private var cameraService: CameraService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { cameraService = (service as CameraService.LocalBinder).getService() }
        override fun onServiceDisconnected(name: ComponentName?) { cameraService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrugalTheme {
                var mode by remember { mutableStateOf(AppMode.HOME) }
                var room by remember { mutableStateOf("HOME-4827") }
                var url by remember { mutableStateOf(DEFAULT_URL) }
                var key by remember { mutableStateOf(DEFAULT_KEY) }
                when (mode) {
                    AppMode.HOME -> HomeScreen({ mode = AppMode.CAMERA_SETUP }, { mode = AppMode.VIEWER_SETUP })
                    AppMode.CAMERA_SETUP -> SetupScreen("Camera setup", "Turn this old phone into your home camera.", room, { room = it }, url, { url = it }, key, { key = it }, { startCamera(room, url, key); mode = AppMode.CAMERA }, { mode = AppMode.HOME })
                    AppMode.CAMERA -> CameraScreen(room, cameraService, { mode = AppMode.HOME })
                    AppMode.VIEWER_SETUP -> SetupScreen("Viewer setup", "Open the live feed from another phone.", room, { room = it }, url, { url = it }, key, { key = it }, { mode = AppMode.VIEWER }, { mode = AppMode.HOME }, viewer = true)
                    AppMode.VIEWER -> ViewerScreen(room, url, key, { mode = AppMode.HOME })
                }
            }
        }
    }

    private fun startCamera(room: String, url: String, key: String) {
        val prefs = AppPreferences(this)
        prefs.saveConnection(url, key)
        val intent = Intent(this, CameraService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() { runCatching { unbindService(serviceConnection) }; super.onDestroy() }
}

@Composable private fun FrugalTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(primary = Color(0xFF73E0B1), secondary = Color(0xFF80CBC4), background = Color(0xFF07110E), surface = Color(0xFF0D1B17), surfaceVariant = Color(0xFF162A24))
    MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = LocalTextStyle.current.copy(fontSize = 16.sp)), content = content)
}

@Composable private fun AppHeader(title: String, subtitle: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        Column(Modifier.weight(1f)) { Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Color(0xFF8FA59E), fontSize = 13.sp) }
        Icon(Icons.Default.Shield, null, tint = Color(0xFF73E0B1), modifier = Modifier.size(30.dp))
    }
}

@Composable private fun HomeScreen(onCamera: () -> Unit, onViewer: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(30.dp)); Text("FRUGAL", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF73E0B1), letterSpacing = 4.sp)
        Text("Smart security,\nwithout buying a camera.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text("Reuse an old Android phone as a real-time CCTV camera with WebRTC streaming and on-device person detection.", color = Color(0xFF9CB0AA), fontSize = 16.sp)
        Spacer(Modifier.height(30.dp))
        ModeCard("Camera mode", "Use this phone as the always-on security camera", Icons.Default.Videocam, onCamera, true)
        Spacer(Modifier.height(14.dp)); ModeCard("Viewer mode", "Watch your camera remotely from another device", Icons.Default.Visibility, onViewer, false)
        Spacer(Modifier.height(28.dp)); Text("HOW IT WORKS", color = Color(0xFF73E0B1), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        FeatureRow(Icons.Default.Wifi, "WebRTC live video", "Low-latency peer-to-peer streaming")
        FeatureRow(Icons.Default.Psychology, "AI detection", "Person detection runs on the camera phone")
        FeatureRow(Icons.Default.NotificationsActive, "Instant alerts", "Get notified when the camera is armed")
    }
}

@Composable private fun ModeCard(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, primary: Boolean) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (primary) Color(0xFF123B2F) else Color(0xFF101F1B))) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(if (primary) Color(0xFF73E0B1) else Color(0xFF213A32), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (primary) Color(0xFF07110E) else Color(0xFF73E0B1)) }
            Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(desc, color = Color(0xFF9CB0AA), fontSize = 13.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF6E8780))
        }
    }
}

@Composable private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) { Row(Modifier.padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Color(0xFF73E0B1), modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(desc, color = Color(0xFF81958F), fontSize = 12.sp) } } }

@Composable private fun SetupScreen(title: String, subtitle: String, room: String, onRoom: (String) -> Unit, url: String, onUrl: (String) -> Unit, key: String, onKey: (String) -> Unit, onStart: () -> Unit, onBack: () -> Unit, viewer: Boolean = false) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(title, subtitle, onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionTitle("CONNECTION")
            OutlinedTextField(room, onRoom, Modifier.fillMaxWidth(), label = { Text("Room code") }, singleLine = true, leadingIcon = { Icon(Icons.Default.MeetingRoom, null) })
            Spacer(Modifier.height(12.dp)); OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text("Supabase project URL") }, singleLine = true)
            Spacer(Modifier.height(12.dp)); OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("Supabase publishable key") }, singleLine = true)
            Spacer(Modifier.height(20.dp)); InfoCard(if (viewer) "Use the same room code as the camera phone." else "Keep this phone awake and connected to Wi‑Fi while it protects your home.")
            Spacer(Modifier.height(24.dp)); Button(onClick = onStart, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(if (viewer) Icons.Default.Visibility else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (viewer) "Connect to camera" else "Start camera", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp)); Text("Video is sent with WebRTC. Supabase is used only for the signaling handshake.", color = Color(0xFF718780), fontSize = 12.sp)
        }
    }
}

@Composable private fun CameraScreen(room: String, service: CameraService?, onBack: () -> Unit) {
    var armed by remember { mutableStateOf(service?.isArmed() == true) }; var audible by remember { mutableStateOf(false) }; var state by remember { mutableStateOf("Starting camera…") }
    val ctx = LocalContext.current; val prefs = remember { AppPreferences(ctx) }
    LaunchedEffect(service) { while (isActive) { armed = service?.isArmed() == true; delay(700) } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.Close, null, tint = Color.White) }; Column(Modifier.weight(1f)) { Text("HOME CAMERA", color = Color.White, fontWeight = FontWeight.Bold); Text("Room $room", color = Color(0xFF9FB2AC), fontSize = 12.sp) }; StatusPill("LIVE", Color(0xFF73E0B1)) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            AndroidView(factory = { SurfaceViewRenderer(it) }, update = { service?.attachPreview(it) }, modifier = Modifier.fillMaxSize().background(Color(0xFF0A100E)))
            if (service == null) Text("Connecting to camera service…", color = Color.White)
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) { StatusPill(state, Color.White); Spacer(Modifier.height(8.dp)); StatusPill(if (armed) "● ARMED · AI detection ON" else "○ DISARMED", if (armed) Color(0xFFFFB86B) else Color(0xFF9FB2AC)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { service?.setArmed(!armed); prefs.saveArmed(!armed); armed = !armed }, Modifier.weight(1f).height(52.dp)) { Icon(if (armed) Icons.Default.Lock else Icons.Default.LockOpen, null); Spacer(Modifier.width(7.dp)); Text(if (armed) "Disarm" else "Arm") }
            FilledTonalButton(onClick = { audible = !audible; service?.setAudible(audible); prefs.saveAudible(audible) }, Modifier.weight(1f).height(52.dp)) { Icon(if (audible) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null); Spacer(Modifier.width(7.dp)); Text("Alarm") }
        }
    }
}

@Composable private fun ViewerScreen(room: String, url: String, key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current; var connection by remember { mutableStateOf("Connecting…") }; var alert by remember { mutableStateOf<String?>(null) }; var armed by remember { mutableStateOf(false) }; var controller by remember { mutableStateOf<ViewerController?>(null) }
    DisposableEffect(room, url, key) { val c = ViewerController(ctx, url, key, room, { connection = it }, { alert = it }, { armed = it }); controller = c; c.start(); onDispose { c.release() } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.Close, null, tint = Color.White) }; Column(Modifier.weight(1f)) { Text("LIVE VIEW", color = Color.White, fontWeight = FontWeight.Bold); Text("Room $room", color = Color(0xFF9FB2AC), fontSize = 12.sp) }; StatusPill(connection.uppercase(Locale.US), if (connection == "Connected") Color(0xFF73E0B1) else Color(0xFFFFB86B)) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(10.dp).background(Color(0xFF0A100E), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            AndroidView(factory = { SurfaceViewRenderer(it) }, update = { controller?.attachPreview(it) }, modifier = Modifier.fillMaxSize())
            if (connection != "Connected") Text("Waiting for camera…\nKeep the camera phone open and online.", color = Color(0xFF9FB2AC), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            alert?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(18.dp).background(Color(0xCC7A321D), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { armed = !armed; controller?.setArmed(armed) }, Modifier.weight(1f).height(54.dp)) { Icon(if (armed) Icons.Default.Lock else Icons.Default.LockOpen, null); Spacer(Modifier.width(8.dp)); Text(if (armed) "Disarm camera" else "Arm camera") }
        }
    }
}

private class ViewerController(context: Context, url: String, key: String, room: String, val onState: (String) -> Unit, val onAlert: (String) -> Unit, val onArmed: (Boolean) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); private val signaling = com.arhan.frugalcctv.data.SignalingRepository(url, key, room, scope); private val rtc = WebRtcSession(context, false, { c -> scope.launch { signaling.send(SignalMessage("ice", signaling.id(), candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) } }, {}, { s -> if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) onState("Connected") else onState(s.name.lowercase().replace('_', ' ')) }); private var started = false
    fun start() { if (started) return; started = true; onState("Waiting for camera"); signaling.start { msg -> when (msg.type) { "ready" -> { rtc.createPeer(IceConfig()); rtc.createOffer { offer -> scope.launch { signaling.send(SignalMessage("offer", signaling.id(), to = msg.from, sdp = offer.description)) } } }; "answer" -> msg.sdp?.let { rtc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, it)) }; "ice" -> msg.candidate?.let { rtc.addIce(IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, it)) }; "alert" -> msg.text?.let(onAlert); "arm" -> msg.armed?.let(onArmed) } } }
    fun attachPreview(v: SurfaceViewRenderer) = rtc.attachPreview(v)
    fun setArmed(value: Boolean) { scope.launch { signaling.send(SignalMessage("arm", signaling.id(), armed = value)) } }
    fun release() { rtc.release(); signaling.close(); scope.cancel() }
}

@Composable private fun SectionTitle(text: String) { Text(text, Modifier.padding(bottom = 10.dp), color = Color(0xFF73E0B1), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
@Composable private fun InfoCard(text: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF10231D)), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Color(0xFF73E0B1)); Spacer(Modifier.width(12.dp)); Text(text, color = Color(0xFFB5C6C0), fontSize = 13.sp) } } }
@Composable private fun StatusPill(text: String, tint: Color) { Surface(shape = RoundedCornerShape(50), color = Color(0xAA15231F)) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
