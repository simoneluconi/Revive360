package com.simoneluconi.revive360.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawPairsTest {
    private fun file(path: String, kind: FileKind) = CameraFile(path, path.substringAfterLast('/'), 1, 0, kind)

    private val photo = file("DCIM/100PHOTO/360_0384.JPG", FileKind.PHOTO)
    private val front = file("DCIM/RAW/360_0384_front_7776x3888x3950.raw", FileKind.OTHER)
    private val rear = file("DCIM/RAW/360_0384_rear_7776x3888x3950.raw", FileKind.OTHER)

    @Test
    fun aPairLeavesTheListAndMarksItsPhoto() {
        val other = file("DCIM/100PHOTO/360_0383.JPG", FileKind.PHOTO)
        val pairs = RawPairs.of(listOf(photo, rear, front, other))
        assertEquals(listOf(photo, other), pairs.shown)
        assertEquals(RawPair(front, rear), pairs.of(photo))
        assertNull(pairs.of(other))
    }

    @Test
    fun halfAPairOrADumpWithoutItsPhotoStaysAFile() {
        val orphan = file("DCIM/RAW/ssif_front_7776x3888x3950_0_10bit_16bit.raw", FileKind.OTHER)
        val pairs = RawPairs.of(listOf(photo, front, orphan))
        assertEquals(listOf(photo, front, orphan), pairs.shown)
        assertNull(pairs.of(photo))
    }

    @Test
    fun theLayoutComesFromTheName() {
        assertEquals(RawLayout(7776, 3888, 3950), RawLayout.of(front.name))
        assertEquals(RawLayout(7776, 3888, 3950), RawLayout.of("ssif_rear_7776x3888x3950_1_10bit_16bit.raw"))
        assertNull(RawLayout.of("360_0384.JPG"))
    }
}
