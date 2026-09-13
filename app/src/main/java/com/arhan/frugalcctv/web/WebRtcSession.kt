package com.arhan.frugalcctv.web

import android.content.Context
import com.arhan.frugalcctv.domain.IceConfig
import org.webrtc.*

class WebRtcSession(
    context: Context,
    private val captureCamera: Boolean,
    private val onIce: (IceCandidate) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onConnection: (PeerConnection.IceConnectionState) -> Unit,
    private val onFrame: ((VideoFrame) -> Unit)? = null
) {
    companion object {
        private var initialized = false
        private var factory: PeerConnectionFactory? = null
        private var eglBase: EglBase? = null

        @Synchronized
        private fun getFactory(context: Context): PeerConnectionFactory {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
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
    private var peer: PeerConnection? = null
    private val queuedIce = mutableListOf<IceCandidate>()
    private var remoteSet = false

    init {
        if (captureCamera) startCamera(context.applicationContext)
    }

    fun attachPreview(view: SurfaceViewRenderer) {
        val eglContext = getEglContext() ?: return
        view.init(eglContext, null)
        view.setEnableHardwareScaler(true)
        view.setMirror(false)
        track?.addSink(view)
    }

    private fun startCamera(context: Context) {
        source = rtcFactory.createVideoSource(false)
        track = rtcFactory.createVideoTrack("FRUGAL_VIDEO", source)
        onFrame?.let { sink -> track?.addSink { f -> sink(f) } }
        
        val en = Camera2Enumerator(context)
        val name = en.deviceNames.firstOrNull { en.isBackFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        camera = en.createCapturer(name, null) as? CameraVideoCapturer ?: return
        
        helper = SurfaceTextureHelper.create("FrugalCCTV", getEglContext())
        camera?.initialize(helper, context, source?.capturerObserver)
        camera?.startCapture(960, 540, 20)
    }

    fun createPeer(ice: IceConfig): PeerConnection {
        peer?.let { return it }
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
        }
        peer = rtcFactory.createPeerConnection(config, observer())
        track?.let { t -> peer?.addTrack(t, listOf("FRUGAL_CAMERA")) }
        return peer!!
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = onIce(c)
        override fun onTrack(t: RtpTransceiver?) {
            (t?.receiver?.track() as? VideoTrack)?.let(onRemoteVideo)
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
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                peer?.setLocalDescription(this, d)
                done(d)
            }
        }, MediaConstraints())
    }

    fun createAnswer(done: (SessionDescription) -> Unit) {
        peer?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(d: SessionDescription) {
                peer?.setLocalDescription(this, d)
                done(d)
            }
        }, MediaConstraints())
    }

    fun setRemote(d: SessionDescription) {
        peer?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                queuedIce.forEach { peer?.addIceCandidate(it) }
                queuedIce.clear()
            }
        }, d)
    }

    fun addIce(c: IceCandidate) {
        if (remoteSet) peer?.addIceCandidate(c) else queuedIce += c
    }

    fun release() {
        try { camera?.stopCapture() } catch (_: Exception) {}
        camera?.dispose()
        helper?.dispose()
        track?.dispose()
        source?.dispose()
        peer?.dispose()
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
