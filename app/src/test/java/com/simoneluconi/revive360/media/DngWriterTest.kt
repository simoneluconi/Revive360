package com.simoneluconi.revive360.media

import com.simoneluconi.revive360.protocol.RawLayout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A dump as the camera writes it: [side] px per row, 40 rows above the picture, values << 4 */
    private fun dump(name: String, side: Int, value: (y: Int, x: Int) -> Int): File {
        val rows = 40 + side
        val buf = ByteBuffer.allocate(side * 2 * rows).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until rows) for (x in 0 until side) buf.putShort((value(y, x) shl 4).toShort())
        return temp.newFile(name).apply { writeBytes(buf.array()) }
    }

    private class Tiff(bytes: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tags = HashMap<Int, Triple<Int, Int, Int>>()   // tag -> type, count, value or offset

        init {
            val ifd = b.getInt(4)
            val n = b.getShort(ifd).toInt()
            for (i in 0 until n) {
                val at = ifd + 2 + i * 12
                tags[b.getShort(at).toInt() and 0xffff] = Triple(b.getShort(at + 2).toInt(), b.getInt(at + 4), b.getInt(at + 8))
            }
        }

        fun long(tag: Int) = tags.getValue(tag).third
        fun rationals(tag: Int): List<Double> {
            val (_, count, at) = tags.getValue(tag)
            return (0 until count).map { b.getInt(at + 8 * it).toDouble() / b.getInt(at + 8 * it + 4) }
        }
        fun pixel(y: Int, x: Int): Int = b.getShort(long(273) + 2 * (y * long(256) + x)).toInt() and 0xffff
    }

    @Test
    fun bothLensesSideBySideWithBlackAndWhiteBalance() {
        val side = 8
        // Black rows 128 (R sites 130); the picture: front R 1128, G 2128, B 628; rear the y*side+x ramp
        val cfa = { y: Int, x: Int -> if (y % 2 == 0 && x % 2 == 0) 1128 else if (y % 2 == 1 && x % 2 == 1) 628 else 2128 }
        val front = dump("f.raw", side) { y, x -> if (y < 40) (if (y % 2 == 0 && x % 2 == 0) 130 else 128) else cfa(y, x) }
        val rear = dump("r.raw", side) { y, x -> if (y < 40) (if (y % 2 == 0 && x % 2 == 0) 130 else 128) else cfa(y, x) }
        val out = temp.newFile("o.dng")
        out.outputStream().use { DngWriter.write(front, rear, RawLayout(side * 2, side, 40 + side), it) }
        val t = Tiff(out.readBytes())

        assertEquals(16, t.long(256))
        assertEquals(8, t.long(257))
        assertEquals(1128, t.pixel(0, 0))
        assertEquals(2128, t.pixel(0, 9))
        assertEquals(628, t.pixel(7, 15))
        assertEquals(listOf(130.0, 128.0, 128.0, 128.0), t.rationals(50714))
        // Grey world over the centre: (R - black) / (G - black) and (B - black) / (G - black)
        val neutral = t.rationals(50728)
        assertEquals(998.0 / 2000.0, neutral[0], 1e-6)
        assertEquals(1.0, neutral[1], 1e-6)
        assertEquals(500.0 / 2000.0, neutral[2], 1e-6)
        assertEquals(4095, t.long(50717))
    }

    /** The real dumps, when RAW_TEST_DIR names a folder with 360_0384_front/rear_…raw: writes 360_0384_app.dng there */
    @Test
    fun realDumps() {
        val dir = System.getenv("RAW_TEST_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "360_0384_front_7776x3888x3950.raw").exists())
        val front = File(dir, "360_0384_front_7776x3888x3950.raw")
        val rear = File(dir, "360_0384_rear_7776x3888x3950.raw")
        File(dir, "360_0384_app.dng").outputStream().use { DngWriter.write(front, rear, RawLayout.of(front.name)!!, it) }
    }
}
