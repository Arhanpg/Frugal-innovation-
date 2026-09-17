package com.arhan.frugalcctv.service

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ThreatDetector(private val threshold: Float, private val onPerson: (Float) -> Unit) {
    private val busy = AtomicBoolean(false)
    private var lastRun = 0L
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 900L || busy.getAndSet(true)) return
        lastRun = now
        val buffer = frame.buffer.toI420() ?: run { busy.set(false); return }
        try {
            val nv21 = toNv21(buffer)
            detector.process(InputImage.fromByteArray(nv21, buffer.width, buffer.height, frame.rotation, InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) onPerson(1.0f)
                }
                .addOnFailureListener { Log.w("FrugalCCTV", "Face detection failed", it) }
                .addOnCompleteListener { busy.set(false) }
        } catch (e: Exception) {
            Log.e("FrugalCCTV", "Detection process failed", e)
            busy.set(false)
        } finally {
            buffer.release()
        }
    }

    private fun toNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val w = buffer.width
        val h = buffer.height
        val out = ByteArray(w * h + w * h / 2)
        copyPlane(buffer.dataY, buffer.strideY, out, 0, w, h)
        var dst = w * h
        for (row in 0 until h / 2) {
            val u = buffer.dataU.position() + row * buffer.strideU
            val v = buffer.dataV.position() + row * buffer.strideV
            for (col in 0 until w / 2) {
                out[dst++] = buffer.dataV.get(v + col)
                out[dst++] = buffer.dataU.get(u + col)
            }
        }
        return out
    }

    private fun copyPlane(src: ByteBuffer, stride: Int, out: ByteArray, offset: Int, width: Int, height: Int) {
        val base = src.position()
        for (row in 0 until height) {
            src.position(base + row * stride)
            src.get(out, offset + row * width, width)
        }
        src.position(base)
    }

    fun close() = detector.close()
}
