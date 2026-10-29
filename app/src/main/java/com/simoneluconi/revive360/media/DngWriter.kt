package com.simoneluconi.revive360.media

import com.simoneluconi.revive360.protocol.RawLayout
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * A DNG from the two RAW dumps of a C200 photo, both lenses side by side like the camera's JPEG: the
 * phone's port of tools/raw/ssif2dng.py (format: docs/firmware/14). Streamed row by row, so it needs
 * no more memory than a row: the picture first, its tags after it (TIFF allows the IFD anywhere),
 * since the white balance is measured on the way.
 *
 * Each dump: 16-bit little-endian words, 12-bit values shifted left by 4; rows 0-15 optical black,
 * the picture the width x width square from row 40. Bayer RGGB, white 4095.
 *
 * White balance: the camera's own when the photo's JPEG thumbnail is given ([Reference]), else grey
 * world, which outdoors (sky, grass) comes out too blue and the DNG yellowish.
 */
object DngWriter {
    private const val SHIFT = 4
    private const val BLACK_ROWS = 16
    private const val OB_ROWS = 40
    private const val WHITE = 4095
    private const val STRIP_OFFSET = 16L
    /** A raw value from here up counts as clipped */
    private const val CLIPPED = 4000
    /** Fewer grey cells than this in the thumbnail: grey world instead */
    private const val MIN_GREY_CELLS = 16

    /** The photo's JPEG thumbnail as ARGB pixels (320 x 160 on the C200), the same framing as the DNG */
    class Reference(val width: Int, val height: Int, val argb: IntArray)

    /**
     * XYZ (D65) -> camera, fitted against the camera's own JPEGs (tools/raw/ccmfit.py, shot 360_0382):
     * a raw developer then gives colours close to the camera's.
     */
    private val COLOR_MATRIX = doubleArrayOf(
        1.5444, -0.6246, -0.1031,
        0.1190, 0.7907, 0.0884,
        0.2728, -0.0814, 0.2876,
    )

    /**
     * Writes the DNG to [out]. The white balance comes from [reference] when it shows enough neutral
     * grey; answers how many grey cells it used, 0 for grey world.
     */
    fun write(front: File, rear: File, layout: RawLayout, out: OutputStream, reference: Reference? = null): Int {
        val lenses = listOf(front, rear)
        val expected = layout.rowBytes.toLong() * layout.rows
        for (f in lenses) if (f.length() != expected) throw IOException("${f.name}: ${f.length()} bytes, expected $expected")
        val side = layout.width
        val wordsPerRow = layout.rowBytes / 2
        if (side > wordsPerRow || OB_ROWS + side > layout.rows) throw IOException("unexpected RAW layout $layout")
        val width = side * lenses.size
        val height = side
        val stripBytes = width.toLong() * height * 2

        // White balance: grey world over the centre of the side-by-side picture (as ssif2dng.py)
        val y0 = (height / 4) and 1.inv()
        val x0 = (width / 4) and 1.inv()
        val sums = DoubleArray(4)
        val counts = LongArray(4)
        val black = DoubleArray(4)
        // Per thumbnail cell: the sum and count of each CFA site, and whether any pixel clipped
        val cells = reference?.let { it.width * it.height } ?: 0
        val cellSums = DoubleArray(4 * cells)
        val cellCounts = IntArray(4 * cells)
        val cellClipped = BooleanArray(cells)
        val cellX = reference?.let { ref -> IntArray(width) { (it.toLong() * ref.width / width).toInt() } }

        val ins = lenses.map { DataInputStream(it.inputStream().buffered(1 shl 20)) }
        try {
            val raw = ByteArray(layout.rowBytes)
            val words = Array(lenses.size) { IntArray(side) }
            fun readRow(lens: Int) {
                ins[lens].readFully(raw)
                val w = words[lens]
                for (x in 0 until side) w[x] = ((raw[2 * x].toInt() and 0xff) or ((raw[2 * x + 1].toInt() and 0xff) shl 8)) ushr SHIFT
            }

            val sink = BufferedOutputStream(out, 1 shl 20)
            sink.write(header(STRIP_OFFSET + stripBytes))
            sink.write(ByteArray((STRIP_OFFSET - 8).toInt()))
            // Optical black, per CFA site, both lenses averaged
            for (y in 0 until OB_ROWS) {
                for (lens in lenses.indices) {
                    readRow(lens)
                    if (y < BLACK_ROWS) for (x in 0 until side) black[(y and 1) * 2 + (x and 1)] += words[lens][x].toDouble()
                }
            }
            val perSite = BLACK_ROWS / 2.0 * side / 2.0 * lenses.size
            for (i in 0..3) black[i] /= perSite

            val row = ByteArray(width * 2)
            for (y in 0 until height) {
                for (lens in lenses.indices) {
                    readRow(lens)
                    val w = words[lens]
                    val base = lens * side
                    for (x in 0 until side) {
                        val v = w[x]
                        row[2 * (base + x)] = v.toByte()
                        row[2 * (base + x) + 1] = (v ushr 8).toByte()
                    }
                    if (reference != null && cellX != null) {
                        val rowCell = (y.toLong() * reference.height / height).toInt() * reference.width
                        val site = (y and 1) * 2
                        for (x in 0 until side) {
                            val cell = rowCell + cellX[base + x]
                            val at = 4 * cell + site + ((base + x) and 1)
                            cellSums[at] += w[x].toDouble()
                            cellCounts[at]++
                            if (w[x] >= CLIPPED) cellClipped[cell] = true
                        }
                    }
                    if (y >= y0 && y < y0 + height / 2) {
                        val site = (y and 1) * 2
                        for (x in maxOf(x0, base) until minOf(x0 + width / 2, base + side)) {
                            sums[site + (x and 1)] += w[x - base].toDouble()
                            counts[site + (x and 1)]++
                        }
                    }
                }
                sink.write(row)
            }

            val camera = reference?.let { cameraWhite(it, cellSums, cellCounts, cellClipped, black) }
            val neutral = camera?.first ?: run {
                val r = sums[0] / counts[0] - black[0]
                val g = (sums[1] / counts[1] - black[1] + sums[2] / counts[2] - black[2]) / 2
                val b = sums[3] / counts[3] - black[3]
                doubleArrayOf(r / g, 1.0, b / g)
            }
            sink.write(ifd(STRIP_OFFSET + stripBytes, width, height, black, neutral))
            sink.flush()
            return camera?.second ?: 0
        } finally {
            ins.forEach { runCatching { it.close() } }
        }
    }

    /**
     * The camera's white balance as raw (R/G, 1, B/G): medians over the cells whose thumbnail pixel
     * is neutral grey (linear max 0.15..0.85, (max - min) / max < 0.06) and whose raw pixels did not
     * clip; with the number of such cells. Null when fewer than [MIN_GREY_CELLS]. As ssif2dng.py.
     */
    private fun cameraWhite(
        ref: Reference, sums: DoubleArray, counts: IntArray, clipped: BooleanArray, black: DoubleArray,
    ): Pair<DoubleArray, Int>? {
        fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val rg = ArrayList<Double>()
        val bg = ArrayList<Double>()
        for (cell in clipped.indices) {
            val p = ref.argb[cell]
            val lin = doubleArrayOf(linear((p ushr 16) and 0xff), linear((p ushr 8) and 0xff), linear(p and 0xff))
            val max = lin.max()
            val min = lin.min()
            if (clipped[cell] || max <= 0.15 || max >= 0.85 || (max - min) / max >= 0.06) continue
            fun mean(site: Int) = sums[4 * cell + site] / maxOf(counts[4 * cell + site], 1)
            val r = mean(0) - black[0]
            val g = (mean(1) - black[1] + mean(2) - black[2]) / 2
            val b = mean(3) - black[3]
            if (g <= 20) continue
            rg += r / g
            bg += b / g
        }
        if (rg.size < MIN_GREY_CELLS) return null
        return doubleArrayOf(median(rg), 1.0, median(bg)) to rg.size
    }

    private fun median(v: MutableList<Double>): Double {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
    }

    private fun header(ifdOffset: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte())
            .putShort(42).putInt(ifdOffset.toInt()).array()

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun ifd(offset: Long, width: Int, height: Int, black: DoubleArray, neutral: DoubleArray): ByteArray {
        val entries = ArrayList<Entry>()
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun bytes(tag: Int, vararg v: Int) = entries.add(Entry(tag, 1, v.size, ByteArray(v.size) { v[it].toByte() }))
        fun ascii(tag: Int, s: String) = (s.toByteArray(Charsets.US_ASCII) + 0.toByte()).let { entries.add(Entry(tag, 2, it.size, it)) }
        fun shorts(tag: Int, vararg v: Int) = entries.add(Entry(tag, 3, v.size, le(2 * v.size).apply { v.forEach { putShort(it.toShort()) } }.array()))
        fun longs(tag: Int, vararg v: Long) = entries.add(Entry(tag, 4, v.size, le(4 * v.size).apply { v.forEach { putInt(it.toInt()) } }.array()))
        fun rationals(tag: Int, type: Int, den: Int, v: DoubleArray) =
            entries.add(Entry(tag, type, v.size, le(8 * v.size).apply { v.forEach { putInt((it * den).roundToLong().toInt()).putInt(den) } }.array()))

        longs(254, 0)                              // NewSubFileType: the main image
        longs(256, width.toLong())
        longs(257, height.toLong())
        shorts(258, 16)
        shorts(259, 1)                             // uncompressed
        shorts(262, 32803)                         // CFA
        ascii(271, "Samsung")
        ascii(272, "Gear 360 (SM-C200)")
        longs(273, STRIP_OFFSET)
        shorts(274, 1)
        shorts(277, 1)
        longs(278, height.toLong())
        longs(279, width.toLong() * height * 2)
        shorts(284, 1)
        ascii(305, "Revive360")
        shorts(33421, 2, 2)                        // CFARepeatPatternDim
        bytes(33422, 0, 1, 1, 2)                   // CFAPattern RGGB
        bytes(50706, 1, 4, 0, 0)                   // DNGVersion
        bytes(50707, 1, 1, 0, 0)                   // DNGBackwardVersion
        ascii(50708, "Samsung Gear 360 SM-C200")   // UniqueCameraModel
        shorts(50713, 2, 2)                        // BlackLevelRepeatDim
        rationals(50714, 5, 100, black)            // BlackLevel per CFA site
        longs(50717, WHITE.toLong())
        rationals(50721, 10, 10000, COLOR_MATRIX)  // ColorMatrix1
        rationals(50728, 5, 1000000, neutral)      // AsShotNeutral
        shorts(50778, 21)                          // CalibrationIlluminant1 = D65
        entries.sortBy { it.tag }

        // The IFD, then the values that do not fit in its 4-byte slots
        val ifdSize = 2 + entries.size * 12 + 4
        val extraAt = offset + ifdSize
        val table = le(ifdSize).putShort(entries.size.toShort())
        val extra = java.io.ByteArrayOutputStream()
        for (e in entries) {
            table.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (e.data.size <= 4) {
                table.put(e.data.copyOf(4))
            } else {
                table.putInt((extraAt + extra.size()).toInt())
                extra.write(e.data)
                if (extra.size() % 2 != 0) extra.write(0)
            }
        }
        table.putInt(0)                            // no next IFD
        return table.array() + extra.toByteArray()
    }
}
