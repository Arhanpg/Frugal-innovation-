package com.arhan.frugalcctv

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arhan.frugalcctv.data.AppPreferences
import com.arhan.frugalcctv.domain.*
import com.arhan.frugalcctv.service.CameraService
import com.arhan.frugalcctv.web.WebRtcSession
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.Locale

private const val DEFAULT_URL = "https://ihscwvpvtawjnskqmuja.supabase.co"
private const val DEFAULT_KEY = "sb_publishable_Q6RogD9n7TncYd2YdjGq7w_AVAYkkqN"

class MainActivity : ComponentActivity() {
    private var cameraService by mutableStateOf<CameraService?>(null)
    private var pendingCamera: Triple<String, String, String>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { cameraService = (service as CameraService.LocalBinder).getService(); tryStartCamera() }
        override fun onServiceDisconnected(name: ComponentName?) { cameraService = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AppTheme { AppRoot(cameraService) } } }
    private fun requestCamera(room: String, url: String, key: String) {
        pendingCamera = Triple(room, url, key)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS), 41); return }
        bindAndStart()
    }
    private fun bindAndStart() { val intent = Intent(this, CameraService::class.java); startForegroundService(intent); bindService(intent, connection, BIND_AUTO_CREATE); tryStartCamera() }
    private fun tryStartCamera() { val p = pendingCamera ?: return; if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return; cameraService?.start(p.second, p.third, p.first, IceConfig(), SecuritySettings(AppPreferences(this).armed(), audibleAlarm = AppPreferences(this).audible())) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, results); if (requestCode == 41 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) bindAndStart() }
    override fun onDestroy() { runCatching { unbindService(connection) }; super.onDestroy() }

    @Composable private fun AppRoot(service: CameraService?) {
        var mode by remember { mutableStateOf(AppMode.HOME) }; var room by remember { mutableStateOf("HOME-4827") }; var url by remember { mutableStateOf(DEFAULT_URL) }; var key by remember { mutableStateOf(DEFAULT_KEY) }
        when (mode) {
            AppMode.HOME -> HomeScreen({ mode = AppMode.CAMERA_SETUP }, { mode = AppMode.VIEWER_SETUP })
            AppMode.CAMERA_SETUP -> SetupScreen("Camera setup", "Turn an old phone into your home security camera.", room, { room = it }, url, { url = it }, key, { key = it }, { AppPreferences(this).saveConnection(url, key); requestCamera(room, url, key); mode = AppMode.CAMERA }, { mode = AppMode.HOME })
            AppMode.CAMERA -> CameraScreen(room, service) { mode = AppMode.HOME }
            AppMode.VIEWER_SETUP -> SetupScreen("Viewer setup", "Connect to a camera using its room code.", room, { room = it }, url, { url = it }, key, { key = it }, { mode = AppMode.VIEWER }, { mode = AppMode.HOME }, true)
            AppMode.VIEWER -> ViewerScreen(room, url, key) { mode = AppMode.HOME }
        }
    }
}

@Composable private fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF73E0B1), background = Color(0xFF07110E), surface = Color(0xFF0D1B17), surfaceVariant = Color(0xFF162A24)), content = content)
@Composable private fun Header(title: String, subtitle: String, back: (() -> Unit)? = null) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { if (back != null) IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, null) }; Column(Modifier.weight(1f)) { Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text(subtitle, fontSize = 13.sp, color = Color(0xFF8FA59E)) }; Icon(Icons.Default.Shield, null, tint = Color(0xFF73E0B1), modifier = Modifier.size(30.dp)) } }
@Composable private fun HomeScreen(camera: () -> Unit, viewer: () -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { Spacer(Modifier.height(28.dp)); Text("FRUGAL", color = Color(0xFF73E0B1), fontWeight = FontWeight.Bold, letterSpacing = 4.sp); Text("Smart security,\nwithout buying a camera.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text("Reuse an old Android phone as a real-time CCTV camera with WebRTC and on-device AI.", fontSize = 16.sp, color = Color(0xFF9CB0AA)); Spacer(Modifier.height(30.dp)); ActionCard("Camera mode", "Use this phone as the security camera", Icons.Default.Videocam, camera, true); Spacer(Modifier.height(14.dp)); ActionCard("Viewer mode", "Watch the live camera remotely", Icons.Default.Visibility, viewer, false); Spacer(Modifier.height(30.dp)); Text("CORE FEATURES", fontSize = 12.sp, color = Color(0xFF73E0B1), fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Feature(Icons.Default.Wifi, "WebRTC live streaming", "Low-latency peer-to-peer video"); Feature(Icons.Default.Psychology, "On-device AI", "Person detection on the old phone"); Feature(Icons.Default.NotificationsActive, "Security alerts", "Alert the viewer when a person is detected") } }
@Composable private fun ActionCard(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, primary: Boolean) { Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (primary) Color(0xFF123B2F) else Color(0xFF101F1B))) { Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(56.dp).background(if (primary) Color(0xFF73E0B1) else Color(0xFF213A32), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (primary) Color(0xFF07110E) else Color(0xFF73E0B1)) }; Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(desc, fontSize = 13.sp, color = Color(0xFF9CB0AA)) }; Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF6E8780)) } } }
@Composable private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) { Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Color(0xFF73E0B1)); Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(desc, fontSize = 12.sp, color = Color(0xFF81958F)) } } }

@Composable private fun SetupScreen(title: String, subtitle: String, room: String, roomChange: (String) -> Unit, url: String, urlChange: (String) -> Unit, key: String, keyChange: (String) -> Unit, start: () -> Unit, back: () -> Unit, viewer: Boolean = false) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Header(title, subtitle, back); Column(Modifier.padding(horizontal = 20.dp)) { Text("CONNECTION", fontSize = 12.sp, color = Color(0xFF73E0B1), fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Spacer(Modifier.height(10.dp)); OutlinedTextField(room, roomChange, Modifier.fillMaxWidth(), label = { Text("Room code") }, singleLine = true, leadingIcon = { Icon(Icons.Default.MeetingRoom, null) }); Spacer(Modifier.height(12.dp)); OutlinedTextField(url, urlChange, Modifier.fillMaxWidth(), label = { Text("Supabase project URL") }, singleLine = true); Spacer(Modifier.height(12.dp)); OutlinedTextField(key, keyChange, Modifier.fillMaxWidth(), label = { Text("Supabase publishable key") }, singleLine = true); Spacer(Modifier.height(20.dp)); Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF10231D)), shape = RoundedCornerShape(18.dp)) { Text(if (viewer) "Use exactly the same room code as the camera phone." else "Keep this phone connected to Wi-Fi and awake while protecting your home.", Modifier.padding(16.dp), fontSize = 13.sp, color = Color(0xFFB5C6C0)) }; Spacer(Modifier.height(24.dp)); Button(onClick = start, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Icon(if (viewer) Icons.Default.Visibility else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (viewer) "Connect to camera" else "Start camera", fontWeight = FontWeight.Bold) } } } }

@Composable private fun CameraScreen(room: String, service: CameraService?, back: () -> Unit) { var armed by remember { mutableStateOf(service?.isArmed() == true) }; var audible by remember { mutableStateOf(false) }; val ctx = LocalContext.current; val prefs = remember { AppPreferences(ctx) }; LaunchedEffect(service) { while (isActive) { armed = service?.isArmed() == true; delay(600) } }; Column(Modifier.fillMaxSize().background(Color.Black)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.Default.Close, null, tint = Color.White) }; Column(Modifier.weight(1f)) { Text("HOME CAMERA", fontWeight = FontWeight.Bold, color = Color.White); Text("Room $room", fontSize = 12.sp, color = Color(0xFF9FB2AC)) }; Pill("LIVE", Color(0xFF73E0B1)) }; Box(Modifier.fillMaxWidth().weight(1f).padding(10.dp), contentAlignment = Alignment.Center) { AndroidView(factory = { SurfaceViewRenderer(it) }, update = { service?.attachPreview(it) }, modifier = Modifier.fillMaxSize().background(Color(0xFF0A100E))); if (service == null) Text("Starting camera service…", color = Color.White); Pill(if (armed) "● ARMED · AI ON" else "○ DISARMED", if (armed) Color(0xFFFFB86B) else Color(0xFF9FB2AC), Modifier.align(Alignment.BottomStart).padding(18.dp)) }; Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilledTonalButton(onClick = { armed = !armed; service?.setArmed(armed); prefs.saveArmed(armed) }, modifier = Modifier.weight(1f).height(52.dp)) { Icon(if (armed) Icons.Default.Lock else Icons.Default.LockOpen, null); Spacer(Modifier.width(6.dp)); Text(if (armed) "Disarm" else "Arm") }; FilledTonalButton(onClick = { audible = !audible; service?.setAudible(audible); prefs.saveAudible(audible) }, modifier = Modifier.weight(1f).height(52.dp)) { Icon(if (audible) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null); Spacer(Modifier.width(6.dp)); Text("Alarm") } } } }

@Composable private fun ViewerScreen(room: String, url: String, key: String, back: () -> Unit) { val ctx = LocalContext.current; var status by remember { mutableStateOf("Waiting for camera") }; var alert by remember { mutableStateOf<String?>(null) }; var armed by remember { mutableStateOf(false) }; var controller by remember { mutableStateOf<ViewerController?>(null) }; DisposableEffect(room, url, key) { val c = ViewerController(ctx, url, key, room, { status = it }, { alert = it }, { armed = it }); controller = c; c.start(); onDispose { c.release() } }; Column(Modifier.fillMaxSize().background(Color.Black)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.Default.Close, null, tint = Color.White) }; Column(Modifier.weight(1f)) { Text("LIVE VIEW", fontWeight = FontWeight.Bold, color = Color.White); Text("Room $room", fontSize = 12.sp, color = Color(0xFF9FB2AC)) }; Pill(status.uppercase(Locale.US), if (status == "Connected") Color(0xFF73E0B1) else Color(0xFFFFB86B)) }; Box(Modifier.fillMaxWidth().weight(1f).padding(10.dp).background(Color(0xFF0A100E), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { AndroidView(factory = { SurfaceViewRenderer(it) }, update = { controller?.attachPreview(it) }, modifier = Modifier.fillMaxSize()); if (status != "Connected") Text("Waiting for camera…\nKeep the camera phone online.", color = Color(0xFF9FB2AC), textAlign = TextAlign.Center); alert?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(18.dp).background(Color(0xCC7A321D), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold) } }; FilledTonalButton(onClick = { armed = !armed; controller?.setArmed(armed) }, modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp, vertical = 5.dp)) { Icon(if (armed) Icons.Default.Lock else Icons.Default.LockOpen, null); Spacer(Modifier.width(8.dp)); Text(if (armed) "Disarm camera" else "Arm camera") } } }

private class ViewerController(context: Context, url: String, key: String, room: String, private val state: (String) -> Unit, private val alert: (String) -> Unit, private val armed: (Boolean) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signaling = com.arhan.frugalcctv.data.SignalingRepository(url, key, room, scope)
    private val rtc = WebRtcSession(context, false, { c -> scope.launch { signaling.send(SignalMessage("ice", signaling.id(), candidate = c.sdp, sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex)) } }, {}, { s -> state(if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) "Connected" else s.name.lowercase().replace('_', ' ')) })
    fun start() { state("Waiting for camera"); signaling.start { msg -> when (msg.type) { "ready" -> { rtc.createPeer(IceConfig()); rtc.createOffer { offer -> scope.launch { signaling.send(SignalMessage("offer", signaling.id(), to = msg.from, sdp = offer.description)) } } }; "answer" -> msg.sdp?.let { rtc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, it)) }; "ice" -> msg.candidate?.let { rtc.addIce(IceCandidate(msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0, it)) }; "alert" -> msg.text?.let(alert); "arm" -> msg.armed?.let(armed) } }; scope.launch { delay(700); signaling.send(SignalMessage("hello", signaling.id())) } }
    fun attachPreview(v: SurfaceViewRenderer) = rtc.attachPreview(v)
    fun setArmed(value: Boolean) { scope.launch { signaling.send(SignalMessage("arm", signaling.id(), armed = value)) } }
    fun release() { rtc.release(); signaling.close(); scope.cancel() }
}
@Composable private fun Pill(text: String, tint: Color, modifier: Modifier = Modifier) { Surface(modifier = modifier, shape = RoundedCornerShape(50), color = Color(0xAA15231F)) { Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold) } }
