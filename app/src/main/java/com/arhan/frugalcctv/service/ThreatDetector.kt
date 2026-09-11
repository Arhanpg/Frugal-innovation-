package com.arhan.frugalcctv.service

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ThreatDetector(private val threshold: Float, private val onPerson: (Float) -> Unit) {
    private val busy = AtomicBoolean(false); private var lastRun = 0L
    private val detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().enableClassification().build())
    fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis(); if (now - lastRun < 1000L || busy.getAndSet(true)) return; lastRun = now
        val buffer = frame.buffer.toI420()
        try {
            val nv21 = toNv21(buffer)
            detector.process(InputImage.fromByteArray(nv21, buffer.width, buffer.height, frame.rotation, InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { objects -> objects.flatMap { it.labels }.filter { it.text.equals("person", true) }.maxOfOrNull { it.confidence }?.takeIf { it >= threshold }?.let(onPerson) }
                .addOnFailureListener { Log.w("FrugalCCTV", "Detection failed", it) }.addOnCompleteListener { busy.set(false) }
        } finally { buffer.release() }
    }
    private fun toNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val w=buffer.width; val h=buffer.height; val out=ByteArray(w*h+w*h/2); copyPlane(buffer.dataY,buffer.strideY,out,0,w,h); var dst=w*h
        for(row in 0 until h/2){val u=buffer.dataU.position()+row*buffer.strideU;val v=buffer.dataV.position()+row*buffer.strideV;for(col in 0 until w/2){out[dst++]=buffer.dataV.get(v+col);out[dst++]=buffer.dataU.get(u+col)}}
        return out
    }
    private fun copyPlane(src:ByteBuffer,stride:Int,out:ByteArray,offset:Int,width:Int,height:Int){val base=src.position();for(row in 0 until height){src.position(base+row*stride);src.get(out,offset+row*width,width)};src.position(base)}
    fun close()=detector.close()
}
