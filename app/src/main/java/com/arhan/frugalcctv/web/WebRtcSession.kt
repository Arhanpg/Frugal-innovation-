package com.arhan.frugalcctv.web

import android.content.Context
import android.util.Log
import com.arhan.frugalcctv.domain.IceConfig
import org.webrtc.*

class WebRtcSession(
    context: Context,
    private val captureCamera: Boolean,
    private val onIce: (IceCandidate) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onConnection: (PeerConnection.IceConnectionState) -> Unit,
    private val onFrame: ((VideoFrame) -> Unit)? = null,
    private val onError: (String) -> Unit = {},
    private val onFatalError: (String) -> Unit = onError
) {
    companion object {
        private var factory: PeerConnectionFactory? = null
        private var egl: EglBase? = null

        @Synchronized
        private fun factory(context: Context): PeerConnectionFactory {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
                egl = EglBase.create()
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            return factory!!
        }

        fun eglContext() = egl?.eglBaseContext
    }

    private val f = factory(context)
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var local: VideoTrack? = null
    private var remote: VideoTrack? = null
    private var peer: PeerConnection? = null
    private var preview: SurfaceViewRenderer? = null
    private val queued = mutableListOf<IceCandidate>()
    private var remoteSet = false

    init {
        if (captureCamera) startCamera(context.applicationContext)
    }

    fun attachPreview(view: SurfaceViewRenderer) {
        preview?.let { old ->
            runCatching { local?.removeSink(old) }
            runCatching { remote?.removeSink(old) }
            runCatching { old.release() }
        }
        preview = view
        val e = eglContext() ?: return onError("WebRTC EGL is unavailable")
        runCatching {
            view.init(e, null)
            view.setEnableHardwareScaler(true)
            view.setMirror(captureCamera)
            local?.addSink(view)
            remote?.addSink(view)
        }.onFailure { onError("Renderer failed: " + (it.message ?: "unknown")) }
    }

    private fun startCamera(context: Context) {
        try {
            source = f.createVideoSource(false)
            local = f.createVideoTrack("FRUGAL_VIDEO", source)
            onFrame?.let { local?.addSink(it) }
            val e = Camera2Enumerator(context)
            val name = e.deviceNames.firstOrNull { e.isBackFacing(it) } ?: e.deviceNames.firstOrNull() ?: error("No camera found")
            capturer = e.createCapturer(name, null) as? CameraVideoCapturer ?: error("Cannot create camera capturer")
            helper = SurfaceTextureHelper.create("FrugalCCTV-Camera", eglContext() ?: error("No EGL context"))
            capturer!!.initialize(helper, context, source!!.capturerObserver)
            capturer!!.startCapture(640, 360, 20)
            preview?.let { local?.addSink(it) }
        } catch (t: Throwable) {
            Log.e("FrugalCCTV", "Camera capture failed", t)
            onFatalError("Camera capture failed: " + (t.message ?: "unknown"))
            release()
        }
    }

    fun switchCamera(onSwitched: ((Boolean) -> Unit)? = null) {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                preview?.setMirror(isFrontCamera)
                onSwitched?.invoke(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                onError("Camera switch failed: " + (errorDescription ?: "unknown"))
            }
        })
    }

    fun createPeer(ice: IceConfig = IceConfig()): PeerConnection {
        peer?.let { return it }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = f.createPeerConnection(config, observer()) ?: error("Unable to create PeerConnection")
        if (captureCamera) {
            local?.let { peer!!.addTrack(it, listOf("FRUGAL_CAMERA")); it.setEnabled(true) }
        } else {
            peer!!.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
        return peer!!
    }

    private fun attachRemote(track: VideoTrack) {
        if (remote === track) return
        remote?.let { old -> preview?.let { runCatching { old.removeSink(it) } } }
        remote = track
        track.setEnabled(true)
        preview?.let { track.addSink(it) }
        onRemoteVideo(track)
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = onIce(c)
        override fun onTrack(t: RtpTransceiver?) { (t?.receiver?.track() as? VideoTrack)?.let(::attachRemote) }
        override fun onAddStream(s: MediaStream?) { s?.videoTracks?.firstOrNull()?.let(::attachRemote) }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = onConnection(s)
        override fun onSignalingChange(s: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(r: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) = Unit
        override fun onRemoveStream(s: MediaStream?) = Unit
        override fun onDataChannel(d: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    fun createOffer(done: (SessionDescription) -> Unit) {
        val p = peer ?: return onError("Peer not ready")
        val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")) }
        p.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = done(d)
                    override fun onSetFailure(e: String?) = onError("Local offer failed: " + (e ?: "unknown"))
                }, d)
            }

            override fun onCreateFailure(e: String?) = onError("Offer failed: " + (e ?: "unknown"))
        }, constraints)
    }

    fun createAnswer(done: (SessionDescription) -> Unit) {
        val p = peer ?: return onError("Peer not ready")
        p.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = done(d)
                    override fun onSetFailure(e: String?) = onError("Local answer failed: " + (e ?: "unknown"))
                }, d)
            }

            override fun onCreateFailure(e: String?) = onError("Answer failed: " + (e ?: "unknown"))
        }, MediaConstraints())
    }

    fun setRemote(d: SessionDescription, done: (() -> Unit)? = null) {
        val p = peer ?: return onError("Peer not ready")
        p.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                queued.forEach { p.addIceCandidate(it) }
                queued.clear()
                done?.invoke()
            }

            override fun onSetFailure(e: String?) = onError("Remote SDP failed: " + (e ?: "unknown"))
        }, d)
    }

    fun addIce(c: IceCandidate) {
        if (peer == null || !remoteSet) queued += c
        else peer?.addIceCandidate(c)
    }

    fun resetPeer() {
        queued.clear()
        remoteSet = false
        remote?.let { old -> preview?.let { runCatching { old.removeSink(it) } }; runCatching { old.dispose() } }
        remote = null
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
    }

    fun release() {
        resetPeer()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { preview?.release() }
        runCatching { local?.dispose() }
        runCatching { source?.dispose() }
        capturer = null; helper = null; local = null; source = null; preview = null
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
