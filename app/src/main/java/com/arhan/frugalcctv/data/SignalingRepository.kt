package com.arhan.frugalcctv.data

import android.os.Process
import com.arhan.frugalcctv.domain.SignalMessage
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class SignalingRepository(
    private val projectUrl: String,
    private val publishableKey: String,
    roomCode: String,
    private val scope: CoroutineScope,
    stableClientId: String? = null
) {
    private val clientId = stableClientId ?: Process.myPid().toString().ifBlank { UUID.randomUUID().toString() }
    private val supabase = createSupabaseClient(projectUrl, publishableKey) { install(Realtime) }
    private val channel = supabase.channel("room:${roomCode.trim().uppercase()}")
    private var collector: Job? = null
    fun id() = clientId

    fun start(onMessage: (SignalMessage) -> Unit, onSubscribed: (() -> Unit)? = null) = scope.launch {
        val flow: Flow<SignalMessage> = channel.broadcastFlow(event = "signal")
        collector = launch {
            flow.collect { message ->
                val addressed = message.to == null || message.to == clientId
                if (message.from != clientId && addressed) onMessage(message)
            }
        }
        channel.subscribe(blockUntilSubscribed = true)
        onSubscribed?.invoke()
        // Broadcasts are ephemeral. Retry the viewer hello briefly so a camera
        // that is still subscribing cannot miss the discovery message.
        if (onSubscribed != null) {
            repeat(5) {
                delay(2_000L)
                if (collector?.isActive == true) onSubscribed.invoke() else return@repeat
            }
        }
    }

    suspend fun send(message: SignalMessage) {
        channel.broadcast(event = "signal", message = buildJsonObject {
            put("type", message.type); put("from", message.from)
            message.to?.let { put("to", it) }; message.sdp?.let { put("sdp", it) }
            message.candidate?.let { put("candidate", it) }; message.sdpMid?.let { put("sdpMid", it) }
            message.sdpMLineIndex?.let { put("sdpMLineIndex", it) }; message.armed?.let { put("armed", it) }; message.text?.let { put("text", it) }
        })
    }

    fun close() { collector?.cancel(); scope.launch { channel.unsubscribe() } }
}
