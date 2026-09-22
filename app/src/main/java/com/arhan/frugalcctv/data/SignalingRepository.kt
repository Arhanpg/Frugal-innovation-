package com.arhan.frugalcctv.data

import android.os.Process
import com.arhan.frugalcctv.domain.PresenceState
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
import kotlinx.coroutines.isActive
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
    private var presenceCollector: Job? = null
    private var retryJob: Job? = null

    fun id() = clientId

    fun start(
        role: String,
        onMessage: (SignalMessage) -> Unit,
        onPresence: ((List<PresenceState>) -> Unit)? = null,
        onSubscribed: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val flow: Flow<SignalMessage> =
                    channel.broadcastFlow(event = "signal")

                collector = launch {
                    flow.collect { message ->
                        val addressed =
                            message.to == null || message.to == clientId

                        if (message.from != clientId && addressed) {
                            onMessage(message)
                        }
                    }
                }

                if (onPresence != null) {
                    val presenceFlow: Flow<List<PresenceState>> =
                        channel.presenceDataFlow<PresenceState>()

                    presenceCollector?.cancel()
                    presenceCollector = launch {
                        presenceFlow.collect { states ->
                            onPresence(states)
                        }
                    }
                }

                channel.subscribe(blockUntilSubscribed = true)
                channel.track(PresenceState(clientId, role))
                onSubscribed?.invoke()

                retryJob?.cancel()
                if (onSubscribed != null) {
                    retryJob = launch {
                        repeat(6) {
                            delay(1_000L)
                            if (!isActive || collector?.isActive != true) {
                                return@launch
                            }
                            onSubscribed.invoke()
                        }
                    }
                }
            } catch (t: Throwable) {
                onError?.invoke(
                    t.message ?: t::class.simpleName ?: "Realtime signaling failed"
                )
            }
        }
    }

    suspend fun send(message: SignalMessage) {
        channel.broadcast(event = "signal", message = buildJsonObject {
            put("type", message.type)
            put("from", message.from)
            message.to?.let { put("to", it) }
            message.sdp?.let { put("sdp", it) }
            message.candidate?.let { put("candidate", it) }
            message.sdpMid?.let { put("sdpMid", it) }
            message.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
            message.armed?.let { put("armed", it) }
            message.text?.let { put("text", it) }
        })
    }

    fun close() {
        retryJob?.cancel()
        presenceCollector?.cancel()
        collector?.cancel()
        scope.launch {
            runCatching { channel.untrack() }
            runCatching { channel.unsubscribe() }
        }
    }
}
