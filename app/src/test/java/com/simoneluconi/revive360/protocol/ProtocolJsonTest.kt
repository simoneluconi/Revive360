package com.simoneluconi.revive360.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolJsonTest {
    @Test
    fun readsInfoFromTheContractExample() {
        val info = ProtocolJson.info(FakeCamera.INFO)
        assertEquals(1, info.protocol)
        assertEquals("g360rd", info.serverName)
        assertEquals("SM-C200", info.model)
        assertEquals("C200GLU0AQK1", info.firmware)
        assertEquals("a0:b1:c2:65:ef:1e", info.mac)
        val caps = info.capabilities
        assertEquals(listOf(CaptureMode.PHOTO, CaptureMode.RAW, CaptureMode.RAW_PHOTO), caps.captureModes)
        assertEquals(listOf("sfine", "fine", "norm"), caps.photoQualities)
        assertTrue(caps.listFiles && caps.download && caps.range && caps.delete && caps.thumbnails)
        assertTrue(caps.battery && caps.storage)
        assertFalse(caps.liveview)
        assertEquals(2, caps.leds.size)
        assertEquals(listOf(LedState.OFF, LedState.ON, LedState.BLINK, LedState.AUTO), caps.leds[0].states)
    }

    @Test
    fun missingCapabilitiesMeanNotSupported() {
        val info = ProtocolJson.info("""{"protocol":1,"camera":{"model_raw":"SMR210","firmware":"R210GLU0ARB2"}}""")
        assertEquals("SMR210", info.model)
        val caps = info.capabilities
        assertFalse(caps.canCapture)
        assertFalse(caps.listFiles)
        assertTrue(caps.leds.isEmpty())
    }

    @Test
    fun unknownModesAndFieldsAreIgnored() {
        val caps = ProtocolJson.capabilities(
            org.json.JSONObject("""{"capture":{"modes":["photo","hdr+burst"]},"future":{"x":1}}""")
        )
        assertEquals(listOf(CaptureMode.PHOTO), caps.captureModes)
    }

    @Test
    fun readsStatusWithAndWithoutTemperature() {
        val status = ProtocolJson.status(FakeCamera.STATUS)
        assertEquals(87, status.battery!!.percent)
        assertEquals(31.5, status.battery.temperatureC!!, 0.001)
        assertEquals(127865454592L, status.storage!!.totalBytes)
        assertFalse(status.busy)
        val noTemp = ProtocolJson.status("""{"battery":{"percent":5,"temperature_c":null},"busy":true,"uptime_s":3}""")
        assertNull(noTemp.battery!!.temperatureC)
        assertNull(noTemp.storage)
        assertTrue(noTemp.busy)
    }

    @Test
    fun readsFilesAndKinds() {
        val files = ProtocolJson.files(FakeCamera.FILES)
        assertEquals(3, files.size)
        assertEquals(FileKind.RAW, files[0].kind)
        assertEquals("360_0042", files[0].baseName)
        assertEquals(FileKind.VIDEO, files[2].kind)
    }

    @Test
    fun readsErrorBodies() {
        val error = ProtocolJson.error(409, """{"error":{"code":"busy","message":"capture running"}}""")!!
        assertEquals(409, error.httpStatus)
        assertEquals(CameraException.BUSY, error.code)
        assertNull(ProtocolJson.error(500, "<html>oops</html>"))
    }

    @Test
    fun writesRequests() {
        assertSameJson("""{"mode":"raw+photo","quality":"fine"}""", ProtocolJson.captureRequest(CaptureMode.RAW_PHOTO, "fine", null))
        assertSameJson("""{"id":"front","state":"blink","on_ms":250,"off_ms":250}""", ProtocolJson.ledRequest("front", LedState.BLINK, 250, 250))
        assertSameJson("""{"id":"front","state":"on"}""", ProtocolJson.ledRequest("front", LedState.ON, 250, 250))
    }

    /** Key order is not part of JSON. */
    private fun assertSameJson(expected: String, actual: String) = assertEquals(fields(expected), fields(actual))

    private fun fields(text: String): Map<String, String> {
        val json = org.json.JSONObject(text)
        return json.keys().asSequence().associateWith { json.get(it).toString() }
    }
}
