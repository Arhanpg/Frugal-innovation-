package com.arhan.frugalcctv.data

import com.arhan.frugalcctv.domain.SignalMessage
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class SignalingRepository(private val projectUrl: String, private val publishableKey: String, roomCode: String, private val scope: CoroutineScope) {
    private val clientId = UUID.randomUUID().toString()
    private val supabase = createSupabaseClient(projectUrl, publishableKey) { install(Realtime) }
    private val channel = supabase.channel("room:${roomCode.trim()}")
    private var collector: Job? = null
    fun id() = clientId

    fun start(onMessage: (SignalMessage) -> Unit, onSubscribed: (() -> Unit)? = null) = scope.launch {
        val flow: Flow<SignalMessage> = channel.broadcastFlow(event = "signal")
        collector = launch { flow.collect { message -> if (message.from != clientId && (message.to == null || message.to == clientId)) onMessage(message) } }
        channel.subscribe(blockUntilSubscribed = true)
        onSubscribed?.invoke()
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
