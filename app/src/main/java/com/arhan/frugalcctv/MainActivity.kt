package com.arhan.frugalcctv

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.text.SimpleDateFormat
import java.util.*

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
        setContent { AppTheme { Root(service) } }
    }

    private fun startCamera(room: String) {
        pendingRoom = room.trim().uppercase()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
            return
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
        var manualIp by remember { mutableStateOf("") }

        when (mode) {
            AppMode.HOME -> HomeScreen(
                onSelectCamera = { mode = AppMode.CAMERA_SETUP },
                onSelectViewer = { mode = AppMode.VIEWER_SETUP }
            )
            AppMode.CAMERA_SETUP -> SetupScreen(
                title = "Camera Setup",
                subtitle = "Turn this phone into a smart CCTV camera.",
                room = room,
                onRoomChange = { room = it },
                manualIp = manualIp,
                onManualIpChange = { manualIp = it },
                isViewer = false,
                onStart = { startCamera(room); mode = AppMode.CAMERA },
                onBack = { mode = AppMode.HOME }
            )
            AppMode.CAMERA -> CameraScreen(
                room = room,
                service = camera,
                onBack = { camera?.stopCamera(); mode = AppMode.HOME }
            )
            AppMode.VIEWER_SETUP -> SetupScreen(
                title = "Viewer Setup",
                subtitle = "Monitor your camera live over local Wi-Fi.",
                room = room,
                onRoomChange = { room = it },
                manualIp = manualIp,
                onManualIpChange = { manualIp = it },
                isViewer = true,
                onStart = { mode = AppMode.VIEWER },
                onBack = { mode = AppMode.HOME }
            )
            AppMode.VIEWER -> ViewerScreen(
                room = room,
                manualIp = manualIp,
                onBack = { mode = AppMode.HOME }
            )
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF00E676),
        secondary = Color(0xFF00B0FF),
        background = Color(0xFF07110E),
        surface = Color(0xFF0D1B17),
        onSurface = Color.White
    ), content = content
)

@Composable
private fun HomeScreen(onSelectCamera: () -> Unit, onSelectViewer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07110E))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFF00E676).copy(alpha = 0.2f)) {
                    Box(Modifier.padding(8.dp)) {
                        Icon(Icons.Default.Videocam, null, tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("FRUGAL CCTV", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Smart Security.\nZero Cloud Cost.", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, lineHeight = 42.sp)
            Spacer(Modifier.height(10.dp))
            Text("Repurpose old smartphones into high-definition, AI-powered CCTV security cameras operating strictly over local network.", color = Color(0xFF8FA59E), fontSize = 14.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModeCard(
                title = "Camera Mode",
                description = "Place this phone on wall to start streaming & AI detection",
                icon = Icons.Default.Videocam,
                badgeText = "CAMERA HOST",
                onClick = onSelectCamera
            )

            ModeCard(
                title = "Viewer Mode",
                description = "Watch live stream, control camera & receive threat alerts",
                icon = Icons.Default.Visibility,
                badgeText = "REMOTE VIEWER",
                onClick = onSelectViewer
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0D1B17),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                FeatureBadge(Icons.Default.Psychology, "ML Kit AI")
                FeatureBadge(Icons.Default.FlashOn, "WebRTC LAN")
                FeatureBadge(Icons.Default.Shield, "Tamper Defense")
            }
        }
    }
}

@Composable
private fun FeatureBadge(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF00E676), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color(0xFFB0BEC5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    badgeText: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E3830), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B17))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF152A24)) {
                Box(Modifier.padding(14.dp)) {
                    Icon(icon, null, tint = Color(0xFF00E676), modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Pill(badgeText, Color(0xFF00E676))
                Spacer(Modifier.height(4.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(description, fontSize = 12.sp, color = Color(0xFF8FA59E))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF8FA59E))
        }
    }
}

@Composable
private fun SetupScreen(
    title: String,
    subtitle: String,
    room: String,
    onRoomChange: (String) -> Unit,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    isViewer: Boolean,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    val localIp = remember { getLocalIpAddress() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07110E))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(subtitle, color = Color(0xFF8FA59E), fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))

            Spacer(Modifier.height(32.dp))

            Text("SECURITY ROOM CODE", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = room,
                onValueChange = onRoomChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. HOME-4827") },
                trailingIcon = {
                    IconButton(onClick = { onRoomChange("HOME-" + (1000..9999).random()) }) {
                        Icon(Icons.Default.Refresh, null, tint = Color(0xFF00E676))
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E676),
                    unfocusedBorderColor = Color(0xFF1E3830),
                    focusedContainerColor = Color(0xFF0D1B17),
                    unfocusedContainerColor = Color(0xFF0D1B17),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(Modifier.height(20.dp))

            if (isViewer) {
                Text("MANUAL IP CONNECT (OPTIONAL)", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = onManualIpChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. 192.168.1.100 (Leave blank for Auto Discovery)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color(0xFF1E3830),
                        focusedContainerColor = Color(0xFF0D1B17),
                        unfocusedContainerColor = Color(0xFF0D1B17),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(Modifier.height(12.dp))
                Text("Auto-discovery will search the local Wi-Fi subnet. If router broadcast is disabled, enter the Camera IP shown on the camera phone screen.", color = Color(0xFF8FA59E), fontSize = 12.sp)
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0D1B17),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Device Local IP Address", color = Color(0xFF8FA59E), fontSize = 12.sp)
                        Text(localIp, color = Color(0xFF00E676), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Viewers on the same Wi-Fi can connect via Room Code or direct IP.", color = Color(0xFF8FA59E), fontSize = 11.sp)
                    }
                }
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
        ) {
            Text(if (isViewer) "Connect Viewer" else "Launch CCTV Camera", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraScreen(room: String, service: CameraService?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { AppPreferences(ctx) }
    var armed by remember { mutableStateOf(prefs.armed()) }
    var audible by remember { mutableStateOf(prefs.audible()) }
    var torchOn by remember { mutableStateOf(false) }
    var localIp = remember { getLocalIpAddress() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.Red, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("LIVE CAMERA • ROOM $room", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text("IP: $localIp", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = { service?.flipCamera() }) {
                Icon(Icons.Default.Cameraswitch, null, tint = Color.White)
            }
            IconButton(onClick = { torchOn = !torchOn; service?.setTorch(torchOn) }) {
                Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, null, tint = if (torchOn) Color.Yellow else Color.White)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { SurfaceViewRenderer(it) },
                modifier = Modifier.fillMaxSize(),
                update = { service?.attachPreview(it) }
            )

            Pill(
                if (armed) "AI MONITORING ACTIVE" else "DISARMED",
                if (armed) Color(0xFF00E676) else Color.Yellow,
                Modifier.align(Alignment.TopEnd).padding(12.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    armed = !armed
                    service?.setArmed(armed)
                    prefs.saveArmed(armed)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (armed) Color(0xFF1E3830) else Color(0xFF00E676),
                    contentColor = if (armed) Color.White else Color.Black
                )
            ) {
                Text(if (armed) "Disarm Motion" else "Arm Motion")
            }

            Button(
                onClick = {
                    audible = !audible
                    service?.setAudible(audible)
                    prefs.saveAudible(audible)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (audible) Color(0xFFFF5252) else Color(0xFF1E3830),
                    contentColor = Color.White
                )
            ) {
                Text(if (audible) "Siren Active" else "Enable Siren")
            }
        }
    }
}

@Composable
private fun ViewerScreen(room: String, manualIp: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Searching local network…") }
    var alert by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var sirenOn by remember { mutableStateOf(false) }
    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var cameraIp by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<SecurityEventLog>>(emptyList()) }
    var controller by remember { mutableStateOf<ViewerController?>(null) }

    LaunchedEffect(Unit) { notificationPermission(ctx) }

    DisposableEffect(room, manualIp) {
        val c = ViewerController(
            context = ctx,
            room = room,
            manualIp = manualIp,
            onState = { status = it },
            onAlert = { msg ->
                alert = msg
                logs = listOf(SecurityEventLog(event = SecurityEvent(msg, Severity.CRITICAL))) + logs
                AlertNotifier.notify(ctx, "FrugalCCTV Threat Alert", msg, true)
            },
            onStatusUpdate = { msg ->
                msg.armed?.let { armed = it }
                msg.torch?.let { torchOn = it }
                msg.siren?.let { sirenOn = it }
                msg.battery?.let { batteryPct = it }
                msg.charging?.let { isCharging = it }
                msg.ipAddress?.let { cameraIp = it }
            }
        )
        controller = c
        c.start()
        onDispose { c.release(); controller = null }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07110E))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Text("LIVE COMMAND CENTER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (cameraIp.isNotBlank()) "Room $room • $cameraIp" else "Room $room", color = Color.Gray, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    null,
                    tint = if (batteryPct > 20) Color(0xFF00E676) else Color.Red,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("$batteryPct%", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Pill(
                if (status.contains("Connected") || status.contains("video")) "LIVE" else "SEARCHING",
                if (status.contains("Connected") || status.contains("video")) Color(0xFF00E676) else Color.Yellow
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { SurfaceViewRenderer(it) },
                modifier = Modifier.fillMaxSize(),
                update = { controller?.attachPreview(it) }
            )

            if (!status.contains("Connected") && !status.contains("video")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            alert?.let {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xDDFF1744),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            ControlIconButton(
                icon = if (armed) Icons.Default.Shield else Icons.Default.Security,
                label = if (armed) "Armed" else "Disarmed",
                tint = if (armed) Color(0xFF00E676) else Color.Gray,
                onClick = {
                    armed = !armed
                    controller?.setArmed(armed)
                }
            )

            ControlIconButton(
                icon = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                label = "Light",
                tint = if (torchOn) Color.Yellow else Color.Gray,
                onClick = {
                    torchOn = !torchOn
                    controller?.setTorch(torchOn)
                }
            )

            ControlIconButton(
                icon = Icons.Default.Cameraswitch,
                label = "Flip",
                tint = Color.White,
                onClick = { controller?.flipCamera() }
            )

            ControlIconButton(
                icon = if (sirenOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                label = "Siren",
                tint = if (sirenOn) Color.Red else Color.Gray,
                onClick = {
                    sirenOn = !sirenOn
                    controller?.setSiren(sirenOn)
                }
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0D1B17)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("LIVE THREAT LOG", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                if (logs.isEmpty()) {
                    Text("No security threats detected.", color = Color.Gray, fontSize = 12.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs) { log ->
                            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                            Text("[$time] ${log.event.message}", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlIconButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF0D1B17), CircleShape)
        ) {
            Icon(icon, null, tint = tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 11.sp)
    }
}

private class ViewerController(
    context: Context,
    private val room: String,
    private val manualIp: String,
    private val onState: (String) -> Unit,
    private val onAlert: (String) -> Unit,
    private val onStatusUpdate: (SignalMessage) -> Unit
) {
    private val app = context.applicationContext
    private val prefs = AppPreferences(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val id = prefs.deviceId()
    private var endpoint: CameraEndpoint? = null
    private var cameraId: String? = null
    private var negotiating = false
    private var discovery: CameraDiscovery? = null

    private val signaling = DirectSignalingClient(
        scope, room, id, ::handle,
        { state("Signaling connected — negotiating…") },
        { if (scope.isActive) state("Camera disconnected") },
        { state("Signaling error: " + it) }
    )

    private val rtc = WebRtcSession(
        app, false,
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
        onError = { state("WebRTC error: $it") }
    )

    fun start() {
        if (manualIp.isNotBlank()) {
            state("Connecting directly to $manualIp…")
            endpoint = CameraEndpoint(manualIp, SIGNALING_PORT, room, "manual")
            signaling.connect(endpoint!!)
        } else {
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
            "status" -> onStatusUpdate(m)
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
    fun setTorch(value: Boolean) = signaling.send(SignalMessage("torch", id, cameraId, torch = value))
    fun flipCamera() = signaling.send(SignalMessage("flip", id, cameraId))
    fun setSiren(value: Boolean) = signaling.send(SignalMessage("siren", id, cameraId, siren = value))

    fun release() {
        discovery?.stop()
        signaling.close()
        rtc.release()
        scope.cancel()
    }

    private fun state(s: String) = scope.launch { onState(s) }
}

@Composable
private fun Pill(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = Color(0xAA15231F), modifier = modifier) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
    }
}
