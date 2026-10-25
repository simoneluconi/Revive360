package com.simoneluconi.revive360.stitch

import android.content.Context
import androidx.core.content.edit

/**
 * A picture's own corrections, kept per picture: [level] turns the world (true up) into the
 * picture's frame (the camera held askew), [heading] and [pitch] (radians) are where a viewer of the
 * stitched copy looks first.
 */
data class PanoramaEdit(
    val level: FloatArray = Mat3.identity(),
    val heading: Float = 0f,
    val pitch: Float = 0f,
) {
    val isLevelled: Boolean get() = !level.contentEquals(Mat3.identity())

    /**
     * The level with the view rolled by [angle] around where [view] looks: the picture turns about
     * the middle of the screen, as when straightening a photo.
     */
    fun rolled(base: FloatArray, view: ViewState, angle: Float): PanoramaEdit {
        val v = view.viewMatrix()
        val around = Mat3.multiply(Mat3.multiply(v, Mat3.rotation(0f, 0f, angle)), Mat3.transpose(v))
        return copy(level = Mat3.multiply(base, around))
    }

    /** The equirectangular export's orientation: its centre is where the first view looks. */
    fun exportOrientation(): FloatArray = Mat3.multiply(level, Mat3.rotation(heading, 0f, 0f))

    override fun equals(other: Any?): Boolean =
        other is PanoramaEdit && level.contentEquals(other.level) && heading == other.heading && pitch == other.pitch

    override fun hashCode(): Int = (level.contentHashCode() * 31 + heading.hashCode()) * 31 + pitch.hashCode()
}

/** The edits of every picture, by a key naming it (camera, name and time). */
class PanoramaEdits(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("panorama_edits", Context.MODE_PRIVATE)

    fun get(key: String): PanoramaEdit {
        val parts = prefs.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return PanoramaEdit()
        if (parts.size != 11) return PanoramaEdit()
        return PanoramaEdit(parts.subList(0, 9).toFloatArray(), parts[9], parts[10])
    }

    fun put(key: String, edit: PanoramaEdit) {
        prefs.edit {
            if (edit == PanoramaEdit()) remove(key)
            else putString(key, (edit.level.toList() + edit.heading + edit.pitch).joinToString(","))
        }
    }
}
