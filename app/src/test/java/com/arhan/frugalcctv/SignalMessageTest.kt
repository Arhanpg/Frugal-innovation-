package com.arhan.frugalcctv

import com.arhan.frugalcctv.data.decodeSignalMessage
import com.arhan.frugalcctv.data.encodeSignalMessage
import com.arhan.frugalcctv.domain.SignalMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SignalMessageTest {

    @Test
    fun testEncodeAndDecodeSignalMessage() {
        val original = SignalMessage(
            type = "status",
            from = "camera123",
            to = "viewer456",
            armed = true,
            torch = true,
            siren = false,
            battery = 88,
            charging = true,
            ipAddress = "192.168.1.50"
        )

        val encoded = encodeSignalMessage(original)
        assertNotNull(encoded)

        val decoded = decodeSignalMessage(encoded)
        assertNotNull(decoded)
        assertEquals("status", decoded?.type)
        assertEquals("camera123", decoded?.from)
        assertEquals("viewer456", decoded?.to)
        assertEquals(true, decoded?.armed)
        assertEquals(true, decoded?.torch)
        assertEquals(false, decoded?.siren)
        assertEquals(88, decoded?.battery)
        assertEquals(true, decoded?.charging)
        assertEquals("192.168.1.50", decoded?.ipAddress)
    }

    @Test
    fun testAlertMessageSerialization() {
        val alertMsg = SignalMessage(
            type = "alert",
            from = "cam1",
            text = "PERSON DETECTED • 95% confidence"
        )

        val encoded = encodeSignalMessage(alertMsg)
        val decoded = decodeSignalMessage(encoded)

        assertEquals("alert", decoded?.type)
        assertEquals("PERSON DETECTED • 95% confidence", decoded?.text)
    }
}
