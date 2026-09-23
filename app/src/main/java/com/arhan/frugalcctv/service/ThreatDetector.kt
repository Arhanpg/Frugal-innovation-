package com.arhan.frugalcctv.service

import android.os.SystemClock
import android.util.Log
import com.arhan.frugalcctv.domain.SecurityEvent
import com.arhan.frugalcctv.domain.Severity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ThreatDetector(
    private val threshold: Float,
    private val onEvent: (SecurityEvent) -> Unit
) {
    companion object {
        private const val SW=24
        private const val SH=14
        private const val ML_W=256
        private const val ML_H=144
        private const val ML_INTERVAL=180L
        private const val MOTION_COOLDOWN=2000L
        private const val TAMPER_COOLDOWN=3000L
    }
    private val busy=AtomicBoolean(false)
    private var lastMl=0L
    private var previous:ByteArray?=null
    private var motionStreak=0
    private var lastMotion=0L
    private var moveSince=0L
    private var darkSince=0L
    private var coverSince=0L
    private var lastTamper=0L
    private val labeler=ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(.25f).build())

    fun onFrame(frame:VideoFrame){
        val now=SystemClock.elapsedRealtime()
        val b=runCatching{frame.buffer.toI420()}.getOrNull()?:return
        try{
            fastPath(b,now)
            if(now-lastMl>=ML_INTERVAL&&busy.compareAndSet(false,true)){
                lastMl=now
                val bytes=toNv21(b,ML_W,ML_H)
                labeler.process(InputImage.fromByteArray(bytes,ML_W,ML_H,frame.rotation,InputImage.IMAGE_FORMAT_NV21))
                    .addOnSuccessListener{labels->
                        val p=labels.asSequence().filter{
                            when(it.text.lowercase()){
                                "person","human","people","man","woman"->true
                                else->false
                            }
                        }.maxByOrNull{it.confidence}
                        if(p!=null&&p.confidence>=threshold)
                            onEvent(SecurityEvent("PERSON DETECTED • "+(p.confidence*100).toInt()+"% confidence",Severity.CRITICAL))
                    }
                    .addOnFailureListener{Log.w("FrugalCCTV","ML Kit detection failed",it)}
                    .addOnCompleteListener{busy.set(false)}
            }
        }catch(t:Throwable){Log.e("FrugalCCTV","Detection failed",t);busy.set(false)}
        finally{b.release()}
    }

    private fun fastPath(b:VideoFrame.I420Buffer,now:Long){
        val s=ByteArray(SW*SH);var sum=0L;var i=0
        for(y in 0 until SH){
            val sy=y*b.height/SH;val row=b.dataY.position()+sy*b.strideY
            for(x in 0 until SW){
                val sx=x*b.width/SW;val v=b.dataY.get(row+sx).toInt() and 255
                s[i++]=v.toByte();sum+=v
            }
        }
        val avg=sum.toDouble()/s.size;val old=previous
        if(old!=null){
            var changed=0;var borderChanged=0;var borderTotal=0
            for(y in 0 until SH)for(x in 0 until SW){
                val k=y*SW+x
                val d=abs((s[k].toInt() and 255)-(old[k].toInt() and 255))
                val border=x<=2||y<=2||x>=SW-3||y>=SH-3
                if(d>=14){changed++;if(border)borderChanged++}
                if(border)borderTotal++
            }
            val ratio=changed.toDouble()/s.size
            val borderRatio=if(borderTotal==0)0.0 else borderChanged.toDouble()/borderTotal
            if(ratio>=.15){
                motionStreak++
                if(motionStreak>=2&&now-lastMotion>=MOTION_COOLDOWN){
                    lastMotion=now
                    onEvent(SecurityEvent("UNUSUAL ACTIVITY • rapid movement detected",Severity.WARNING))
                }
            }else motionStreak=0
            if(ratio>=.44&&borderRatio>=.40){
                if(moveSince==0L)moveSince=now
                if(now-moveSince>=350&&now-lastTamper>=TAMPER_COOLDOWN){
                    lastTamper=now
                    onEvent(SecurityEvent("TAMPER WARNING • camera position or viewing angle changed",Severity.WARNING))
                }
            }else moveSince=0L
            val oldAvg=old.asSequence().map{it.toInt() and 255}.average()
            if(avg<20&&oldAvg-avg>=22){
                if(darkSince==0L)darkSince=now
                if(now-darkSince>=350&&now-lastTamper>=TAMPER_COOLDOWN){
                    lastTamper=now
                    onEvent(SecurityEvent("TAMPER WARNING • camera view became unexpectedly dark",Severity.WARNING))
                }
            }else if(avg>=22)darkSince=0L
            var variance=0.0
            s.forEach{val d=(it.toInt() and 255)-avg;variance+=d*d}
            variance/=s.size
            if(avg<24&&variance<34){
                if(coverSince==0L)coverSince=now
                if(now-coverSince>=450&&now-lastTamper>=TAMPER_COOLDOWN){
                    lastTamper=now
                    onEvent(SecurityEvent("TAMPER WARNING • lens may be covered or obstructed",Severity.WARNING))
                }
            }else coverSince=0L
        }
        previous=s
    }

    private fun toNv21(b:VideoFrame.I420Buffer,w:Int,h:Int):ByteArray{
        val out=ByteArray(w*h+w*h/2)
        for(y in 0 until h){
            val sy=y*b.height/h;val row=b.dataY.position()+sy*b.strideY
            for(x in 0 until w)out[y*w+x]=b.dataY.get(row+x*b.width/w)
        }
        var d=w*h
        for(y in 0 until h/2){
            val sy=y*(b.height/2)/(h/2)
            val ur=b.dataU.position()+sy*b.strideU;val vr=b.dataV.position()+sy*b.strideV
            for(x in 0 until w/2){
                val sx=x*(b.width/2)/(w/2)
                out[d++]=b.dataV.get(vr+sx);out[d++]=b.dataU.get(ur+sx)
            }
        }
        return out
    }
    fun close()=labeler.close()
}