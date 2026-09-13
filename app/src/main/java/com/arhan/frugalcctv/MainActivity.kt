package com.arhan.frugalcctv

import android.Manifest
import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arhan.frugalcctv.data.AppPreferences
import com.arhan.frugalcctv.domain.AppMode
import com.arhan.frugalcctv.domain.IceConfig
import com.arhan.frugalcctv.domain.SecuritySettings
import com.arhan.frugalcctv.service.CameraService
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {
    private var cameraService: CameraService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, s: IBinder) { cameraService = (s as CameraService.LocalBinder).getService() }
        override fun onServiceDisconnected(n: ComponentName) { cameraService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var mode by remember { mutableStateOf(AppMode.HOME) }
            var roomCode by remember { mutableStateOf("1234567890") }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                if (results.all { it.value }) {
                    val intent = Intent(this, CameraService::class.java)
                    startForegroundService(intent)
                    bindService(intent, connection, BIND_AUTO_CREATE)
                    mode = AppMode.CAMERA
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (mode) {
                        AppMode.HOME -> Home(onCamera = { mode = AppMode.CAMERA_SETUP }, onViewer = { mode = AppMode.VIEWER_SETUP })
                        AppMode.CAMERA_SETUP -> Setup(roomCode, { roomCode = it }, "Start Camera") {
                            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
                        }
                        AppMode.CAMERA -> CameraView(roomCode)
                        AppMode.VIEWER_SETUP -> Setup(roomCode, { roomCode = it }, "Start Viewer") { mode = AppMode.VIEWER }
                        AppMode.VIEWER -> ViewerView(roomCode)
                    }
                }
            }
        }
    }

    @Composable fun Home(onCamera: () -> Unit, onViewer: () -> Unit) {
        Column(Modifier.padding(16.dp)) {
            Button(onClick = onCamera) { Text("Camera Mode") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onViewer) { Text("Viewer Mode") }
        }
    }

    @Composable fun Setup(room: String, onRoomChange: (String) -> Unit, label: String, onStart: () -> Unit) {
        Column(Modifier.padding(16.dp)) {
            TextField(value = room, onValueChange = onRoomChange, label = { Text("Room Code") })
            Button(onClick = onStart) { Text(label) }
        }
    }

    @Composable fun CameraView(room: String) {
        val ctx = LocalContext.current
        val prefs = remember { AppPreferences(ctx) }
        LaunchedEffect(cameraService) {
            cameraService?.start(
                BuildConfig.DEFAULT_SUPABASE_URL.takeIf { it.isNotEmpty() } ?: prefs.supabaseUrl(),
                BuildConfig.DEFAULT_SUPABASE_KEY.takeIf { it.isNotEmpty() } ?: prefs.supabaseKey(),
                room,
                IceConfig(prefs.turnUrls(), prefs.turnUser(), prefs.turnPassword()),
                SecuritySettings(prefs.armed())
            )
        }
        AndroidView(factory = { SurfaceViewRenderer(it).apply { cameraService?.attachPreview(this) } }, modifier = Modifier.fillMaxSize())
    }

    @Composable fun ViewerView(room: String) {
        Text("Viewer for $room (TBD UI)")
    }
}
