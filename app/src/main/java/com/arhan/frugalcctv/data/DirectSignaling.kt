package com.arhan.frugalcctv.data

import android.util.Base64
import android.util.Log
import com.arhan.frugalcctv.domain.SignalMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

private const val DISCOVERY_PORT = 47677
private const val SIGNALING_PORT = 47678
private const val MAGIC = "FRUGAL_CCTV_V2"
private val json = Json { ignoreUnknownKeys = true }

private fun encode(m: SignalMessage): String =
    Base64.encodeToString(json.encodeToString(SignalMessage.serializer(), m).toByteArray(), Base64.NO_WRAP)

private fun decode(line: String): SignalMessage? = runCatching {
    json.decodeFromString<SignalMessage>(String(Base64.decode(line, Base64.DEFAULT), StandardCharsets.UTF_8))
}.getOrNull()

data class CameraEndpoint(val host: String, val port: Int = SIGNALING_PORT, val room: String, val cameraId: String)

class DirectSignalingServer(
    private val scope: CoroutineScope,
    private val room: String,
    private val cameraId: String,
    private val onMessage: (SignalMessage) -> Unit,
    private val onClientCount: (Int) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArraySet<Client>()
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var beaconJob: Job? = null
    val port get() = SIGNALING_PORT

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SIGNALING_PORT))
                }
                while (isActive && running.get()) {
                    val client = Client(server!!.accept())
                    clients += client
                    onClientCount(clients.size)
                    client.start()
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t.message ?: "Direct signaling server failed")
            }
        }
        beaconJob = scope.launch(Dispatchers.IO) {
            val udp = DatagramSocket().apply { broadcast = true }
            try {
                while (isActive && running.get()) {
                    val payload = (MAGIC + "|" + room.uppercase() + "|" + SIGNALING_PORT + "|" + cameraId)
                        .toByteArray(StandardCharsets.UTF_8)
                    broadcastAddresses().forEach {
                        runCatching { udp.send(DatagramPacket(payload, payload.size, it, DISCOVERY_PORT)) }
                    }
                    delay(1000)
                }
            } finally { udp.close() }
        }
    }

    fun send(message: SignalMessage) {
        clients.toList().forEach { if (message.to == null || it.id == message.to) it.send(message) }
    }

    fun close() {
        running.set(false)
        acceptJob?.cancel()
        beaconJob?.cancel()
        clients.toList().forEach { it.close() }
        clients.clear()
        runCatching { server?.close() }
        onClientCount(0)
    }

    private inner class Client(private val socket: Socket) {
        private val alive = AtomicBoolean(true)
        private var writer: BufferedWriter? = null
        var id: String? = null

        fun start() = scope.launch(Dispatchers.IO) {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                while (isActive && alive.get()) {
                    val m = reader.readLine()?.let(::decode) ?: break
                    if (m.type == "hello") {
                        id = m.from
                        send(SignalMessage("ready", cameraId, to = m.from))
                    }
                    onMessage(m)
                }
            } catch (t: Throwable) {
                if (alive.get()) Log.w("FrugalCCTV", "Signaling client closed", t)
            } finally { close() }
        }

        @Synchronized fun send(m: SignalMessage) {
            if (!alive.get()) return
            runCatching { writer?.apply { write(encode(m)); newLine(); flush() } }.onFailure { close() }
        }

        fun close() {
            if (!alive.compareAndSet(true, false)) return
            runCatching { socket.close() }
            clients.remove(this)
            onClientCount(clients.size)
        }
    }
}

class DirectSignalingClient(
    private val scope: CoroutineScope,
    private val room: String,
    private val clientId: String,
    private val onMessage: (SignalMessage) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val closed = AtomicBoolean(false)

    fun connect(endpoint: CameraEndpoint) {
        close()
        closed.set(false)
        scope.launch(Dispatchers.IO) {
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(endpoint.host, endpoint.port), 2500)
                }
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                onConnected()
                send(SignalMessage("hello", clientId, to = endpoint.cameraId, text = room.uppercase()))
                readerJob = launch {
                    try {
                        while (isActive && !closed.get()) {
                            val line = reader.readLine() ?: break
                            decode(line)?.let(onMessage)
                        }
                    } finally {
                        if (!closed.get()) onDisconnected()
                        close()
                    }
                }
            } catch (t: Throwable) {
                if (!closed.get()) {
                    onDisconnected()
                    onError(t.message ?: ("Unable to connect to " + endpoint.host))
                }
            }
        }
    }

    fun send(m: SignalMessage) {
        scope.launch(Dispatchers.IO) {
            synchronized(this@DirectSignalingClient) {
                if (closed.get()) return@synchronized
                runCatching { writer?.apply { write(encode(m)); newLine(); flush() } }
                    .onFailure { onError(it.message ?: "Signaling send failed"); close() }
            }
        }
    }

    fun close() {
        closed.set(true)
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        writer = null
    }
}

class CameraDiscovery(
    private val scope: CoroutineScope,
    private val room: String,
    private val onFound: (CameraEndpoint) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    soTimeout = 1500
                }
                val buffer = ByteArray(2048)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val f = String(packet.data, packet.offset, packet.length).split('|')
                        if (f.size >= 4 && f[0] == MAGIC && f[1].equals(room, true)) {
                            val host = packet.address.hostAddress ?: continue
                            val port = f[2].toIntOrNull() ?: continue
                            onFound(CameraEndpoint(host, port, f[1], f[3]))
                        }
                    } catch (_: SocketTimeoutException) {}
                }
                socket.close()
            } catch (t: Throwable) {
                if (isActive) onError(t.message ?: "Local camera discovery failed")
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

private fun broadcastAddresses(): List<InetAddress> {
    val result = LinkedHashSet<InetAddress>()
    result += InetAddress.getByName("255.255.255.255")
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val n = interfaces.nextElement()
            if (!n.isUp || n.isLoopback) continue
            n.interfaceAddresses.forEach { it.broadcast?.let(result::add) }
        }
    }
    return result.toList()
}
