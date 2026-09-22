package com.arhan.frugalcctv.service

import android.util.Log
import com.arhan.frugalcctv.domain.SecurityEvent
import com.arhan.frugalcctv.domain.Severity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Multi-signal CCTV detector. Person recognition uses ML Kit image labeling,
 * whose bundled model includes a Person label, rather than requiring a full
 * frontal face. Independent frame statistics detect likely lens obstruction,
 * sudden camera movement and abrupt view changes.
 */
class ThreatDetector(private val threshold: Float, private val onEvent: (SecurityEvent) -> Unit) {
    private val busy = AtomicBoolean(false)
    private var lastRun = 0L
    private var previousLuma: ByteArray? = null
    private var darkSince = 0L
    private var obstructionSince = 0L
    private var motionSince = 0L
    private var lastTamperAlert = 0L

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.25f)
            .build()
    )

    fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 160L || busy.getAndSet(true)) return
        lastRun = now
        val buffer = frame.buffer.toI420() ?: run { busy.set(false); return }
        try {
            val nv21 = toNv21(buffer, 256, 144)
            analyzeTamper(buffer.dataY, buffer.strideY, buffer.width, buffer.height, now)
            labeler.process(InputImage.fromByteArray(nv21, 256, 144, frame.rotation, InputImage.IMAGE_FORMAT_NV21))
                .addOnSuccessListener { labels ->
                    val person = labels.firstOrNull { it.text.equals("Person", ignoreCase = true) }
                    if (person != null && person.confidence >= threshold) {
                        onEvent(SecurityEvent("Person detected • ${(person.confidence * 100).toInt()}% confidence", Severity.CRITICAL))
                    }
                }
                .addOnFailureListener { Log.w("FrugalCCTV", "Person detection failed", it) }
                .addOnCompleteListener { busy.set(false) }
        } catch (e: Exception) {
            Log.e("FrugalCCTV", "Detection process failed", e)
            busy.set(false)
        } finally {
            buffer.release()
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
        previousLuma?.let { previous ->
            var changed = 0
            for (i in sample.indices) if (abs((sample[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)) > 32) changed++
            val changeRatio = changed.toDouble() / sample.size
            if (changeRatio > 0.62) {
                if (motionSince == 0L) motionSince = now
                if (now - motionSince > 400L) emitTamper("Camera moved or its view changed suddenly")
            } else motionSince = 0L
        }

        if (avg < 8.0) {
            if (darkSince == 0L) darkSince = now
            if (now - darkSince > 400L) emitTamper("Camera view is unusually dark — possible lens obstruction")
        } else darkSince = 0L

        val variance = sample.map { it.toInt() and 0xFF }.let { values ->
            val mean = values.average()
            values.map { value -> val d = value - mean; d * d }.average()
        }
        if (avg < 18.0 && variance < 14.0) {
            if (obstructionSince == 0L) obstructionSince = now
            if (now - obstructionSince > 500L) emitTamper("Camera lens may be covered or obstructed")
        } else obstructionSince = 0L

        previousLuma = sample
    }

    private fun emitTamper(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastTamperAlert < 15_000L) return
        lastTamperAlert = now
        onEvent(SecurityEvent("TAMPER WARNING • $message", Severity.WARNING))
    }

    private fun toNv21(buffer: VideoFrame.I420Buffer, outW: Int, outH: Int): ByteArray {
        val out = ByteArray(outW * outH + outW * outH / 2)
        for (y in 0 until outH) {
            val sy = y * buffer.height / outH
            val row = buffer.dataY.position() + sy * buffer.strideY
            for (x in 0 until outW) {
                val sx = x * buffer.width / outW
                out[y * outW + x] = buffer.dataY.get(row + sx)
            }
        }
        var dst = outW * outH
        for (y in 0 until outH / 2) {
            val sy = y * (buffer.height / 2) / (outH / 2)
            val u = buffer.dataU.position() + sy * buffer.strideU
            val v = buffer.dataV.position() + sy * buffer.strideV
            for (x in 0 until outW / 2) {
                val sx = x * (buffer.width / 2) / (outW / 2)
                out[dst++] = buffer.dataV.get(v + sx)
                out[dst++] = buffer.dataU.get(u + sx)
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

    fun close() = labeler.close()
}
