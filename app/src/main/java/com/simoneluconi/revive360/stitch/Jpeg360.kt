package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes an equirectangular JPEG that 360 viewers recognise: the Photo Sphere XMP (GPano) block,
 * plus the original's shooting details (date, exposure, camera) copied from its Exif.
 */
object Jpeg360 {
    private const val QUALITY = 93
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

    /** Exif tags carried over; sizes and orientation are the new picture's own. */
    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    fun write(bitmap: Bitmap, original: File?, target: File, initialHeadingDeg: Float = 0f) {
        val jpeg = ByteArrayOutputStream(bitmap.width * bitmap.height / 3).also {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "JPEG encoding failed" }
        }.toByteArray()
        target.outputStream().use { out ->
            out.write(jpeg, 0, 2)                       // SOI
            out.write(xmpSegment(bitmap.width, bitmap.height, initialHeadingDeg))
            var pos = 2
            // The encoder's own JFIF header goes: the Exif written next takes its place
            while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte() && jpeg[pos + 1] == 0xE0.toByte()) {
                pos += 2 + ((jpeg[pos + 2].toInt() and 0xFF) shl 8 or (jpeg[pos + 3].toInt() and 0xFF))
            }
            out.write(jpeg, pos, jpeg.size - pos)
        }
        if (original != null) copyExif(original, target, bitmap.width, bitmap.height)
    }

    private fun copyExif(original: File, target: File, width: Int, height: Int) {
        val from = runCatching { ExifInterface(original.path) }.getOrNull() ?: return
        val to = ExifInterface(target.path)
        for (tag in COPIED_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }

    private fun xmpSegment(width: Int, height: Int, headingDeg: Float): ByteArray {
        val xmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
   GPano:UsePanoramaViewer="True"
   GPano:ProjectionType="equirectangular"
   GPano:FullPanoWidthPixels="$width"
   GPano:FullPanoHeightPixels="$height"
   GPano:CroppedAreaImageWidthPixels="$width"
   GPano:CroppedAreaImageHeightPixels="$height"
   GPano:CroppedAreaLeftPixels="0"
   GPano:CroppedAreaTopPixels="0"
   GPano:InitialViewHeadingDegrees="${"%.1f".format(java.util.Locale.ROOT, headingDeg)}"
   GPano:StitchingSoftware="Revive360"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(Charsets.UTF_8)
        val length = 2 + XMP_HEADER.size + xmp.size
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + XMP_HEADER + xmp
    }
}
