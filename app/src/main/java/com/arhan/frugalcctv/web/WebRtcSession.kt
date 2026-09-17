package com.arhan.frugalcctv.web

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcSession(
    context: Context,
    private val captureCamera: Boolean,
    private val onIce: (IceCandidate) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onConnection: (PeerConnection.IceConnectionState) -> Unit,
    private val onFrame: ((VideoFrame) -> Unit)? = null,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private var initialized = false
        private var factory: PeerConnectionFactory? = null
        private var eglBase: EglBase? = null

        @Synchronized
        private fun getFactory(context: Context): PeerConnectionFactory {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
            if (factory == null) {
                eglBase = EglBase.create()
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            return factory!!
        }

        fun getEglContext() = eglBase?.eglBaseContext
    }

    private val rtcFactory = getFactory(context)
    private var camera: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var peer: PeerConnection? = null
    private var preview: SurfaceViewRenderer? = null
    private val queuedIce = mutableListOf<IceCandidate>()
    private var remoteSet = false

    init {
        if (captureCamera) startCamera(context.applicationContext)
    }

    fun attachPreview(view: SurfaceViewRenderer) {
        if (preview === view) return
        preview?.let { old ->
            runCatching { track?.removeSink(old) }
            runCatching { remoteTrack?.removeSink(old) }
            runCatching { old.release() }
        }
        preview = view
        val egl = getEglContext()
        if (egl == null) {
            onError("WebRTC renderer is not ready")
            return
        }
        runCatching {
            view.init(egl, null)
            view.setEnableHardwareScaler(true)
            view.setMirror(captureCamera)
            track?.addSink(view)
            remoteTrack?.addSink(view)
        }.onFailure { e ->
            onError("Video renderer failed: ${e.message ?: "unknown renderer error"}")
        }
    }

    private fun startCamera(context: Context) {
        try {
            source = rtcFactory.createVideoSource(false)
            track = rtcFactory.createVideoTrack("FRUGAL_VIDEO", source)
            onFrame?.let { sink -> track?.addSink(sink) }

            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames
                .firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: throw IllegalStateException("No camera was found on this device")

            camera = enumerator.createCapturer(name, null) as? CameraVideoCapturer
                ?: throw IllegalStateException("Unable to create the camera capturer")

            val egl = getEglContext() ?: throw IllegalStateException("WebRTC EGL context is unavailable")
            helper = SurfaceTextureHelper.create("FrugalCCTV-Camera", egl)
                ?: throw IllegalStateException("Unable to create camera texture helper")

            camera!!.initialize(helper, context, source!!.capturerObserver)
            camera!!.startCapture(960, 540, 20)
            preview?.let { track?.addSink(it) }
        } catch (e: Exception) {
            Log.e("FrugalCCTV", "Camera capture startup failed", e)
            onError("Camera capture failed: ${e.message ?: e::class.simpleName}")
            release()
        }
    }

    fun createPeer(ice: com.arhan.frugalcctv.domain.IceConfig): PeerConnection {
        peer?.let { return it }
        try {
            val servers = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            if (ice.turnUrls.isNotEmpty()) {
                servers += PeerConnection.IceServer.builder(ice.turnUrls)
                    .setUsername(ice.username)
                    .setPassword(ice.password)
                    .createIceServer()
            }

            val config = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peer = rtcFactory.createPeerConnection(config, observer())
                ?: throw IllegalStateException("Unable to create WebRTC peer connection")

            if (captureCamera) {
                track?.let { peer!!.addTrack(it, listOf("FRUGAL_CAMERA")) }
            } else {
                // Viewer must explicitly negotiate that it wants to receive video.
                peer!!.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }
            return peer!!
        } catch (e: Exception) {
            onError("WebRTC peer creation failed: ${e.message ?: e::class.simpleName}")
            throw e
        }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = onIce(c)

        override fun onTrack(t: RtpTransceiver?) {
            val video = t?.receiver?.track() as? VideoTrack ?: return
            remoteTrack?.let { old -> preview?.let { old.removeSink(it) } }
            remoteTrack = video
            video.setEnabled(true)
            preview?.let { video.addSink(it) }
            onRemoteVideo(video)
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = onConnection(s)
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(r: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    fun createOffer(done: (SessionDescription) -> Unit) {
        val current = peer ?: run {
            onError("Cannot create offer before the WebRTC peer exists")
            return
        }
        current.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                current.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = done(d)
                    override fun onSetFailure(e: String?) = onError("Setting local offer failed: ${e ?: "unknown error"}")
                }, d)
            }

            override fun onCreateFailure(e: String?) = onError("Creating offer failed: ${e ?: "unknown error"}")
        }, MediaConstraints())
    }

    fun createAnswer(done: (SessionDescription) -> Unit) {
        val current = peer ?: run {
            onError("Cannot create answer before the WebRTC peer exists")
            return
        }
        current.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                current.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = done(d)
                    override fun onSetFailure(e: String?) = onError("Setting local answer failed: ${e ?: "unknown error"}")
                }, d)
            }

            override fun onCreateFailure(e: String?) = onError("Creating answer failed: ${e ?: "unknown error"}")
        }, MediaConstraints())
    }

    fun setRemote(d: SessionDescription, onSuccess: (() -> Unit)? = null) {
        val current = peer ?: run {
            onError("Cannot set remote SDP before the WebRTC peer exists")
            return
        }
        current.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                val pending = queuedIce.toList()
                queuedIce.clear()
                pending.forEach { candidate ->
                    if (!current.addIceCandidate(candidate)) {
                        Log.w("FrugalCCTV", "Failed to add queued ICE candidate")
                    }
                }
                onSuccess?.invoke()
            }

            override fun onSetFailure(e: String?) {
                onError("Setting remote SDP failed: ${e ?: "unknown error"}")
            }
        }, d)
    }

    fun addIce(c: IceCandidate) {
        val current = peer
        if (!remoteSet || current == null) {
            queuedIce += c
        } else if (!current.addIceCandidate(c)) {
            Log.w("FrugalCCTV", "Failed to add ICE candidate")
        }
    }

    fun release() {
        runCatching { camera?.stopCapture() }
        runCatching { camera?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { preview?.release() }
        runCatching { track?.dispose() }
        runCatching { remoteTrack?.dispose() }
        runCatching { source?.dispose() }
        runCatching { peer?.dispose() }
        camera = null
        helper = null
        track = null
        remoteTrack = null
        source = null
        peer = null
        preview = null
        queuedIce.clear()
        remoteSet = false
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
