package com.arhan.frugalcctv.web

import android.content.Context
import com.arhan.frugalcctv.domain.IceConfig
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcSession(context: Context, private val captureCamera: Boolean, private val onIce:(IceCandidate)->Unit, private val onRemoteVideo:(VideoTrack)->Unit, private val onConnection:(PeerConnection.IceConnectionState)->Unit, private val onFrame:((VideoFrame)->Unit)?=null) {
    companion object { private var initialized=false; @Synchronized private fun factory(context:Context):Pair<PeerConnectionFactory,EglBase>{if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions());initialized=true};val egl=EglBase.create();return PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory() to egl} }
    private val (factory,eglBase)=factory(context); private var camera:CameraVideoCapturer?=null;private var helper:SurfaceTextureHelper?=null;private var source:VideoSource?=null;private var track:VideoTrack?=null;private var peer:PeerConnection?=null;private val queuedIce=mutableListOf<IceCandidate>();private var remoteSet=false
    init{if(captureCamera)startCamera(context.applicationContext)}
    fun attachPreview(view:SurfaceViewRenderer){view.init(eglBase.eglBaseContext,null);view.setEnableHardwareScaler(true);view.setMirror(false);track?.addSink(view)}
    private fun startCamera(context:Context){source=factory.createVideoSource(false);track=factory.createVideoTrack("FRUGAL_VIDEO",source);onFrame?.let{sink->source?.addSink(VideoSink{f->sink(f)})};val en=Camera2Enumerator(context);val name=en.deviceNames.firstOrNull{en.isBackFacing(it)}?:en.deviceNames.firstOrNull()?:return;camera=en.createCapturer(name,null) as? CameraVideoCapturer?:return;helper=SurfaceTextureHelper.create("FrugalCCTV",eglBase.eglBaseContext);camera?.initialize(helper,context,source?.capturerObserver);camera?.startCapture(960,540,20)}
    fun createPeer(ice:IceConfig):PeerConnection{peer?.let{return it};val servers=mutableListOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());if(ice.turnUrls.isNotEmpty())servers+=PeerConnection.IceServer.builder(ice.turnUrls).setUsername(ice.username).setPassword(ice.password).createIceServer();return factory.createPeerConnection(PeerConnection.RTCConfiguration(servers).apply{sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN},observer())!!.also{peer=it;track?.let{t->it.addTrack(t,listOf("FRUGAL_CAMERA"))}}}
    private fun observer()=object:PeerConnection.Observer{override fun onIceCandidate(c:IceCandidate)=onIce(c);override fun onTrack(t:org.webrtc.RtpTransceiver?){(t?.receiver?.track() as? VideoTrack)?.let(onRemoteVideo)};override fun onIceConnectionChange(s:PeerConnection.IceConnectionState)=onConnection(s);override fun onAddStream(s:org.webrtc.MediaStream)=Unit;override fun onDataChannel(d:org.webrtc.DataChannel?)=Unit;override fun onSignalingChange(s:PeerConnection.SignalingState?)=Unit;override fun onIceConnectionReceivingChange(r:Boolean)=Unit;override fun onIceGatheringChange(s:PeerConnection.IceGatheringState?)=Unit;override fun onRemoveStream(s:org.webrtc.MediaStream?)=Unit;override fun onRenegotiationNeeded()=Unit;override fun onIceCandidatesRemoved(c:Array<out IceCandidate>?)=Unit;override fun onConnectionChange(s:PeerConnection.PeerConnectionState?)=Unit;override fun onStandardizedIceConnectionChange(s:PeerConnection.IceConnectionState?)=Unit;override fun onSelectedCandidatePairChanged(e:PeerConnection.CandidatePairChangeEvent?)=Unit}
    fun createOffer(done:(SessionDescription)->Unit)=peer?.createOffer(object:SdpObserverAdapter(){override fun onCreateSuccess(d:SessionDescription){peer?.setLocalDescription(this,d);done(d)}},MediaConstraints())
    fun createAnswer(done:(SessionDescription)->Unit)=peer?.createAnswer(object:SdpObserverAdapter(){override fun onCreateSuccess(d:SessionDescription){peer?.setLocalDescription(this,d);done(d)}},MediaConstraints())
    fun setRemote(d:SessionDescription){peer?.setRemoteDescription(object:SdpObserverAdapter(){override fun onSetSuccess(){remoteSet=true;queuedIce.forEach{peer?.addIceCandidate(it)};queuedIce.clear()}},d)}
    fun addIce(c:IceCandidate){if(remoteSet)peer?.addIceCandidate(c)else queuedIce+=c}
    fun release(){try{camera?.stopCapture()}catch(_:Exception){};camera?.dispose();helper?.dispose();track?.dispose();source?.dispose();peer?.dispose();factory.dispose();eglBase.release()}
}
open class SdpObserverAdapter:SdpObserver{override fun onCreateSuccess(d:SessionDescription)=Unit;override fun onSetSuccess()=Unit;override fun onCreateFailure(e:String?)=Unit;override fun onSetFailure(e:String?)=Unit}
