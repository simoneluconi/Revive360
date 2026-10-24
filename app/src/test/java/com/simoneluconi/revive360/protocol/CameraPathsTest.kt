package com.simoneluconi.revive360.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPathsTest {
    @Test
    fun acceptsDcimPaths() {
        assertTrue(CameraPaths.isValid("DCIM/100PHOTO/360_0001.JPG"))
    }

    @Test
    fun rejectsWhatTheCameraRejects() {
        listOf(
            "100PHOTO/a.JPG", "/DCIM/a.JPG", "DCIM/../etc/passwd", "DCIM/./a.JPG", "DCIM//a.JPG",
            "DCIM/a\\b.JPG", "DCIM/a\u0000.JPG", "DCIM/",
        ).forEach { assertFalse(it, CameraPaths.isValid(it)) }
    }

    @Test
    fun encodesSegmentsAndKeepsSlashes() {
        assertEquals("DCIM/100PHOTO/360_0001.JPG", CameraPaths.encode("DCIM/100PHOTO/360_0001.JPG"))
        assertEquals("DCIM/My%20Folder/caf%C3%A9%2B1.JPG", CameraPaths.encode("DCIM/My Folder/café+1.JPG"))
    }
}
