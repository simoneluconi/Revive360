package com.simoneluconi.revive360.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val camera = FakeCamera()
    private val client = camera.client

    @After
    fun stop() = camera.close()

    @Test
    fun helloStatusAndFiles() {
        assertEquals("C200GLU0AQK1", client.info().firmware)
        assertEquals(87, client.status().battery!!.percent)
        assertEquals(3, client.files().size)
    }

    @Test
    fun captureReturnsTheNewFiles() {
        val result = client.capture(CaptureMode.PHOTO, "sfine")
        assertEquals("360_0043.JPG", result.files.single().name)
        assertEquals(2410, result.durationMs)
    }

    @Test
    fun errorsCarryTheProtocolCode() {
        camera.busy = true
        try {
            client.capture(CaptureMode.PHOTO)
            fail("expected busy")
        } catch (e: CameraException) {
            assertEquals(409, e.httpStatus)
            assertEquals(CameraException.BUSY, e.code)
        }
    }

    @Test
    fun thumbnailOrNullWhenNone() {
        assertNotNull(client.thumbnail("DCIM/100PHOTO/360_0042.JPG"))
        assertNull(client.thumbnail("DCIM/100PHOTO/360_0042.SRW"))
    }

    @Test
    fun downloadsWholeFile() {
        val target = temp.newFile()
        var last = 0L to 0L
        client.download("DCIM/100PHOTO/360_0042.SRW", target, resume = true) { done, total -> last = done to total }
        assertArrayEquals(camera.files["DCIM/100PHOTO/360_0042.SRW"], target.readBytes())
        assertEquals(300_000L to 300_000L, last)
    }

    @Test
    fun resumesAPartialDownloadWithRange() {
        val data = camera.files["DCIM/100PHOTO/360_0042.SRW"]!!
        val target = temp.newFile().apply { writeBytes(data.copyOfRange(0, 100_000)) }
        client.download("DCIM/100PHOTO/360_0042.SRW", target, resume = true) { _, _ -> }
        assertArrayEquals(data, target.readBytes())
        assertTrue(camera.requests.any { it.endsWith("[bytes=100000-]") })
    }

    @Test
    fun completePartialFileIsLeftAlone() {
        val data = camera.files["DCIM/100PHOTO/360_0042.JPG"]!!
        val target = temp.newFile().apply { writeBytes(data) }
        client.download("DCIM/100PHOTO/360_0042.JPG", target, resume = true) { _, _ -> }
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun withoutResumeStartsOver() {
        val data = camera.files["DCIM/100PHOTO/360_0042.JPG"]!!
        val target = temp.newFile().apply { writeBytes(ByteArray(50) { 9 }) }
        client.download("DCIM/100PHOTO/360_0042.JPG", target, resume = false) { _, _ -> }
        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun deleteRemovesTheFile() {
        client.delete("DCIM/100PHOTO/360_0042.JPG")
        assertTrue("DCIM/100PHOTO/360_0042.JPG" !in camera.files)
        try {
            client.delete("DCIM/100PHOTO/360_0042.JPG")
            fail("expected not_found")
        } catch (e: CameraException) {
            assertEquals(CameraException.NOT_FOUND, e.code)
        }
    }

    @Test
    fun setTimeSendsUtcZoneAndOffset() {
        val result = client.setTime(1729780000000, "Europe/Rome", 120)
        assertEquals(listOf("system", "rtc"), result.applied)
        val body = org.json.JSONObject(camera.lastTimeBody!!)
        assertEquals(1729780000000, body.getLong("utc_ms"))
        assertEquals("Europe/Rome", body.getString("tz"))
        assertEquals(120, body.getInt("tz_offset_min"))
    }

    @Test
    fun settingsCapabilitiesSkipEntriesWithoutValues() {
        val defs = client.info().capabilities.settings
        assertEquals(listOf("mode", "lens", "video_size"), defs.map { it.id })
        assertEquals(listOf("photo", "video"), defs.first().values)
    }

    @Test
    fun readAndChangeSettings() {
        val now = client.settings()
        assertEquals("photo", now.values["mode"])
        assertTrue(now.values.containsKey("lens"))
        assertNull(now.values["lens"])
        val after = client.setSettings(mapOf("mode" to "video"))
        assertEquals("video", after.values["mode"])
        try {
            client.setSettings(mapOf("iso" to "100"))
            fail("expected bad_request")
        } catch (e: CameraException) {
            assertEquals(CameraException.BAD_REQUEST, e.code)
        }
    }

    @Test
    fun offeredFollowsWhatTheCameraListsNow() {
        val defs = client.info().capabilities
        val now = client.settings()
        assertEquals(listOf("3840x1920@30"), now.offered(defs.setting("video_size")!!))
        // A setting the camera lists nothing for offers all of its values
        assertEquals(listOf("photo", "video"), now.offered(defs.setting("mode")!!))
    }

    @Test
    fun recordNeedsVideoModeThenStartsAndStops() {
        try {
            client.record(start = true)
            fail("expected wrong_mode")
        } catch (e: CameraException) {
            assertEquals(CameraException.WRONG_MODE, e.code)
        }
        client.setSettings(mapOf("mode" to "video"))
        val started = client.record(start = true)
        assertTrue(started.recording.active)
        assertEquals(0L, started.recording.elapsedS)
        val stopped = client.record(start = false)
        assertTrue(!stopped.recording.active)
        assertEquals("360_0008.MP4", stopped.files.single().name)
    }

    @Test
    fun statusCarriesTheRecording() {
        val status = ProtocolJson.status(FakeCamera.STATUS_RECORDING)
        assertEquals(Recording(active = true, elapsedS = 42), status.recording)
        assertNull(ProtocolJson.status(FakeCamera.STATUS).recording)
    }

    @Test
    fun ledCommand() {
        client.setLed("front", LedState.ON)
        assertTrue(camera.requests.contains("POST /api/v1/led"))
    }
}
