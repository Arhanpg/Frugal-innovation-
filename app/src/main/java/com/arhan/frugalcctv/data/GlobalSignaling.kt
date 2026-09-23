package com.arhan.frugalcctv.data

import android.util.Log
import com.arhan.frugalcctv.domain.SignalMessage
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlobalSignalingChannel(
    private val scope: CoroutineScope,
    private val room: String,
    private val deviceId: String,
    private val onMessage: (SignalMessage) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private var reconnectJob: Job? = null

    fun start() {
        connect()
    }

    private fun connect() {
        if (connected.get()) return
        val roomCode = room.uppercase().trim().replace(Regex("[^A-Z0-9]"), "")
        val url = "wss://socketsbay.com/wss/v2/1/frugalcctv_$roomCode/"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected.set(true)
                onStatus("Global Internet Signaling Connected")
                Log.d("FrugalCCTV", "Global WebSocket connected for room $roomCode")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = decodeSignalMessage(text) ?: return
                if (msg.from != deviceId) {
                    onMessage(msg)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                onStatus("Global Internet Signaling Disconnected: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected.set(false)
                scheduleReconnect()
            }
        })
    }

    fun send(msg: SignalMessage) {
        scope.launch(Dispatchers.IO) {
            val encoded = encodeSignalMessage(msg)
            webSocket?.send(encoded)
        }
    }

    private fun scheduleReconnect() {
        if (!scope.isActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(4000)
            if (!connected.get()) connect()
        }
    }

    fun close() {
        reconnectJob?.cancel()
        connected.set(false)
        runCatching { webSocket?.close(1000, "Closing") }
        webSocket = null
    }
}
