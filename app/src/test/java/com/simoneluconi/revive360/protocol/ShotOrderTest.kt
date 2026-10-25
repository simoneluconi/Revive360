package com.simoneluconi.revive360.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ShotOrderTest {
    private fun file(path: String, mtime: Long) =
        CameraFile(path, path.substringAfterLast('/'), 1, mtime, FileKind.PHOTO)

    @Test
    fun newestShotFirstWhateverTheClock() {
        val files = listOf(
            file("DCIM/100PHOTO/360_0331.JPG", 1_747_990_000),
            file("DCIM/100PHOTO/360_0334.JPG", 1_451_574_748),   // shot with the clock reset
            file("DCIM/101PHOTO/360_0001.JPG", 1_700_000_000),   // the next folder
            file("DCIM/100PHOTO/360_0376.JPG", 1_792_886_288),
        )
        assertEquals(
            listOf("360_0001.JPG", "360_0376.JPG", "360_0334.JPG", "360_0331.JPG"),
            files.sortedWith(ShotOrder.newestFirst).map { it.name },
        )
    }
}
