package com.arhan.frugalcctv.service

import android.graphics.Rect
import android.util.Log
import com.arhan.frugalcctv.domain.SecurityEvent
import com.arhan.frugalcctv.domain.Severity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Multi-signal CCTV detector.
 *
 * Person detection no longer depends on seeing a face. ML Kit's streaming
 * object detector can return a person's bounding box even when the face is
 * turned away, partly hidden, or outside the frame. Frame statistics provide
 * an independent tamper signal for lens obstruction and sudden camera motion.
 */
class ThreatDetector(private val threshold: Float, private val onEvent: (SecurityEvent) -> Unit) {
    private val busy = AtomicBoolean(false)
    private var lastRun = 0L
    private var previousLuma: ByteArray? = null
    private var previousAverage = -1.0
    private var darkSince = 0L
    private var obstructionSince = 0L
    private var motionSince = 0L
    private var lastTamperAlert = 0L

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .setClassificationConfidenceThreshold(0.45f)
            .build()
    )

    fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 650L || busy.getAndSet(true)) return
        lastRun = now
        val buffer = frame.buffer.toI420() ?: run { busy.set(false); return }
        try {
            val nv21 = toNv21(buffer)
            analyzeTamper(buffer.dataY, buffer.strideY, buffer.width, buffer.height, now)
            detector.process(InputImage.fromByteArray(nv21, buffer.width, buffer.height, frame.rotation, InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { objects -> handleObjects(objects) }
                .addOnFailureListener { Log.w("FrugalCCTV", "Object detection failed", it) }
                .addOnCompleteListener { busy.set(false) }
        } catch (e: Exception) {
            Log.e("FrugalCCTV", "Detection process failed", e)
            busy.set(false)
        } finally {
            buffer.release()
        }
    }

    private fun handleObjects(objects: List<DetectedObject>) {
        var bestPerson = 0f
        for (obj in objects) {
            for (label in obj.labels) {
                if (label.text.equals("Person", ignoreCase = true)) bestPerson = max(bestPerson, label.confidence)
            }
        }
        if (bestPerson >= threshold) {
            onEvent(SecurityEvent("Person detected • ${(bestPerson * 100).toInt()}% confidence", Severity.CRITICAL))
        }
    }

    private fun analyzeTamper(yPlane: ByteBuffer, stride: Int, width: Int, height: Int, now: Long) {
        val sampleW = 32
        val sampleH = 18
        val sample = ByteArray(sampleW * sampleH)
        var sum = 0L
        var idx = 0
        for (sy in 0 until sampleH) {
            val y = sy * height / sampleH
            val row = yPlane.position() + y * stride
            for (sx in 0 until sampleW) {
                val x = sx * width / sampleW
                val value = yPlane.get(row + x).toInt() and 0xFF
                sample[idx++] = value.toByte()
                sum += value
            }
        }
        val avg = sum.toDouble() / sample.size
        val previous = previousLuma
        if (previous != null) {
            var changed = 0
            for (i in sample.indices) if (abs((sample[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)) > 32) changed++
            val changeRatio = changed.toDouble() / sample.size
            if (changeRatio > 0.62) {
                if (motionSince == 0L) motionSince = now
                if (now - motionSince > 1_800L) emitTamper("Camera moved or view changed suddenly")
            } else motionSince = 0L
        }

        if (avg < 8.0) {
            if (darkSince == 0L) darkSince = now
            if (now - darkSince > 2_500L) emitTamper("Camera view is unusually dark — possible lens obstruction")
        } else darkSince = 0L

        val centerVariance = variance(sample)
        if (avg < 18.0 && centerVariance < 14.0) {
            if (obstructionSince == 0L) obstructionSince = now
            if (now - obstructionSince > 2_000L) emitTamper("Camera lens may be covered or obstructed")
        } else obstructionSince = 0L

        previousLuma = sample
        previousAverage = avg
    }

    private fun emitTamper(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastTamperAlert < 15_000L) return
        lastTamperAlert = now
        onEvent(SecurityEvent("TAMPER WARNING • $message", Severity.WARNING))
    }

    private fun variance(values: ByteArray): Double {
        val mean = values.map { it.toInt() and 0xFF }.average()
        return values.map { val d = (it.toInt() and 0xFF) - mean; d * d }.average()
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
