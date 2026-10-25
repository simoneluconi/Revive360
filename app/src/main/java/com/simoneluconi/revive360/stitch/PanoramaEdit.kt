package com.simoneluconi.revive360.stitch

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * How a picture is framed as an ordinary photo, kept per picture: the [style] of view (a name the
 * screen gives), the frame's [ratio], the view ([yaw], [pitch], [fov], [roll]) and where the frame
 * sits on the viewer ([frameX], [frameY]: its centre, [frameHeight]: its height, as fractions of the
 * viewer; negative = centred and as large as fits).
 */
data class Framing(
    val style: String = "",
    val ratio: Float = 16f / 9f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val fov: Float = 90f,
    val roll: Float = 0f,
    val frameX: Float = -1f,
    val frameY: Float = -1f,
    val frameHeight: Float = -1f,
)

/**
 * The edits and framings of every picture, by a key naming it (name and time), and the choices a
 * new picture starts with (the last ones made).
 */
class PanoramaEdits(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("panorama_edits", Context.MODE_PRIVATE)

    fun get(key: String): PanoramaEdit = read(EDIT + key) { o ->
        PanoramaEdit(floats(o.getJSONArray("level")), o.getDouble("heading").toFloat(), o.getDouble("pitch").toFloat())
    } ?: PanoramaEdit()

    fun put(key: String, edit: PanoramaEdit) = write(EDIT + key, if (edit == PanoramaEdit()) null else JSONObject()
        .put("level", JSONArray(edit.level.map { it.toDouble() }))
        .put("heading", edit.heading.toDouble())
        .put("pitch", edit.pitch.toDouble()))

    /** The picture's framing, or null when it was never saved as an ordinary photo. */
    fun framing(key: String): Framing? = read(FRAMING + key) { o ->
        Framing(
            o.optString("style"), f(o, "ratio"), f(o, "yaw"), f(o, "pitch"), f(o, "fov"), f(o, "roll"),
            f(o, "frameX"), f(o, "frameY"), f(o, "frameHeight"),
        )
    }

    fun putFraming(key: String, framing: Framing) = write(FRAMING + key, JSONObject()
        .put("style", framing.style).put("ratio", framing.ratio.toDouble())
        .put("yaw", framing.yaw.toDouble()).put("pitch", framing.pitch.toDouble())
        .put("fov", framing.fov.toDouble()).put("roll", framing.roll.toDouble())
        .put("frameX", framing.frameX.toDouble()).put("frameY", framing.frameY.toDouble())
        .put("frameHeight", framing.frameHeight.toDouble()))

    /** Whether the picture was last worked on as an ordinary photo; null when never opened. */
    fun flat(key: String): Boolean? = if (prefs.contains(FLAT + key)) prefs.getBoolean(FLAT + key, false) else null

    fun putFlat(key: String, flat: Boolean) = prefs.edit { putBoolean(FLAT + key, flat).putBoolean(LAST_FLAT, flat) }

    /** What a picture opened for the first time starts with. */
    val lastFlat: Boolean get() = prefs.getBoolean(LAST_FLAT, false)
    var lastRatio: Float
        get() = prefs.getFloat(LAST_RATIO, 16f / 9f)
        set(value) = prefs.edit { putFloat(LAST_RATIO, value) }

    private fun <T> read(key: String, parse: (JSONObject) -> T): T? =
        prefs.getString(key, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    private fun write(key: String, json: JSONObject?) = prefs.edit {
        if (json == null) remove(key) else putString(key, json.toString())
    }

    private fun floats(a: JSONArray): FloatArray = FloatArray(a.length()) { a.getDouble(it).toFloat() }.also {
        require(it.size == 9) { "not a 3x3 matrix" }
    }

    private fun f(o: JSONObject, name: String): Float = o.getDouble(name).toFloat()

    private companion object {
        const val EDIT = "edit:"
        const val FRAMING = "framing:"
        const val FLAT = "flat:"
        const val LAST_FLAT = "last_flat"
        const val LAST_RATIO = "last_ratio"
    }
}
