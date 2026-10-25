package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a dual-fisheye picture maps onto the sphere: two circles side by side, each a square half of
 * the picture, the left one looking forward (+z) and the right one backward. World axes: x right,
 * y up, z forward.
 *
 * The model is equidistant (a point [theta] off the lens axis lands [focal] * theta from the
 * circle's centre). Everything is relative to the half's side, so one model fits every resolution
 * the camera writes with the same optics.
 */
data class DualFisheye(
    /** Pixels per radian, as a fraction of a half's side. */
    val focal: Float = C200_FOCAL,
    /** Circle centres, as fractions of a half's side (0.5 = the middle). */
    val frontCenterX: Float = 0.5f,
    val frontCenterY: Float = 0.5f,
    val backCenterX: Float = 0.5f,
    val backCenterY: Float = 0.5f,
    /** The back lens's misalignment from exactly opposite, radians (yaw, pitch, roll). */
    val backYaw: Float = 0f,
    val backPitch: Float = 0f,
    val backRoll: Float = 0f,
    /** The widest angle off the axis a lens still sees. */
    val maxTheta: Float = Math.toRadians(98.0).toFloat(),
    /**
     * Light fall-off towards the circle's edge: brightness at theta is multiplied by
     * exp(-[vignetteStrength] * (theta / 90 deg) ^ [vignettePower]); the shader divides it out.
     */
    val vignetteStrength: Float = C200_VIGNETTE_STRENGTH,
    val vignettePower: Float = C200_VIGNETTE_POWER,
) {
    /** What a lens pixel at [theta] is multiplied by to undo the fall-off. */
    fun vignetteGain(theta: Float): Float =
        exp(vignetteStrength * (min(theta, maxTheta) / (PI.toFloat() / 2f)).pow(vignettePower))

    /** World to back lens, column-major (as OpenGL wants it). */
    fun backMatrix(): FloatArray = Mat3.multiply(Mat3.rotation(backYaw, backPitch, backRoll), Mat3.rotation(PI.toFloat(), 0f, 0f))

    companion object {
        /**
         * The C200's lenses: 97.5 degrees off the axis lands 0.503 of a side from the centre (both
         * circles overflow their squares a little). Fitted on matched features of the overlap of
         * several pictures; every camera of the model shares it, its unit's small misalignment is
         * left to the seam alignment.
         */
        val C200_FOCAL = (1.006 * 0.5 / Math.toRadians(97.5)).toFloat()

        /**
         * The C200's fall-off, fitted on the overlap (the same spot seen near one lens's edge and
         * further in on the other): 25% darker at 90 degrees, most of it in the last 15. Without it
         * the seam shows as a darker band.
         */
        const val C200_VIGNETTE_STRENGTH = 0.28f
        const val C200_VIGNETTE_POWER = 8f
    }
}

/** 3x3 matrices, column-major float arrays. */
object Mat3 {
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Rotation by yaw (about y), then pitch (about x), then roll (about z). */
    fun rotation(yaw: Float, pitch: Float, roll: Float): FloatArray {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        val ry = floatArrayOf(cy, 0f, -sy, 0f, 1f, 0f, sy, 0f, cy)
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cp, sp, 0f, -sp, cp)
        val rz = floatArrayOf(cr, sr, 0f, -sr, cr, 0f, 0f, 0f, 1f)
        return multiply(rz, multiply(rx, ry))
    }

    fun transpose(m: FloatArray): FloatArray = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (c in 0 until 3) for (r in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[k * 3 + r] * b[c * 3 + k]
            out[c * 3 + r] = s
        }
        return out
    }

    fun apply(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[3] * y + m[6] * z,
        m[1] * x + m[4] * y + m[7] * z,
        m[2] * x + m[5] * y + m[8] * z,
    )
}

/**
 * The two lenses' brightness difference, from the band both see: per lens a gain per channel that
 * meets in the middle, so the seam has no step and neither half turns much brighter or darker.
 */
object LensGains {
    private const val MAX_GAIN = 1.3f

    /** [preview]: the whole dual-fisheye picture, small (a few hundred pixels high is plenty). */
    fun estimate(preview: Bitmap, model: DualFisheye): FloatArray {
        val side = preview.height
        val pixels = IntArray(preview.width * preview.height)
        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        val back = model.backMatrix()
        val sum0 = DoubleArray(3)
        val sum1 = DoubleArray(3)
        var n = 0
        var lon = 80.0
        while (lon <= 100.0) {
            var lat = -70.0
            while (lat <= 70.0) {
                for (sign in intArrayOf(1, -1)) {
                    val lo = Math.toRadians(lon * sign)
                    val la = Math.toRadians(lat)
                    val x = (cos(la) * sin(lo)).toFloat()
                    val y = sin(la).toFloat()
                    val z = (cos(la) * cos(lo)).toFloat()
                    val a = lensPixel(model, x, y, z, model.frontCenterX, model.frontCenterY, side) ?: continue
                    val v = Mat3.apply(back, x, y, z)
                    val b = lensPixel(model, v[0], v[1], v[2], model.backCenterX, model.backCenterY, side) ?: continue
                    val va = model.vignetteGain(acos(z.coerceIn(-1f, 1f)))
                    val vb = model.vignetteGain(acos(v[2].coerceIn(-1f, 1f)))
                    val pa = pixels[a.second * preview.width + a.first]
                    val pb = pixels[b.second * preview.width + side + b.first]
                    // Clipped or black pixels say nothing about the exposure
                    if (!usable(pa) || !usable(pb)) continue
                    for (c in 0 until 3) {
                        sum0[c] += (pa shr (16 - 8 * c) and 0xFF) * va.toDouble()
                        sum1[c] += (pb shr (16 - 8 * c) and 0xFF) * vb.toDouble()
                    }
                    n++
                }
                lat += 1.0
            }
            lon += 1.0
        }
        if (n < 200) return floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f)
        val gains = FloatArray(6)
        for (c in 0 until 3) {
            val g = sqrt(sum1[c] / sum0[c]).toFloat().coerceIn(1f / MAX_GAIN, MAX_GAIN)
            gains[c] = g
            gains[3 + c] = 1f / g
        }
        return gains
    }

    private fun usable(p: Int): Boolean {
        val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
        return min(r, min(g, b)) > 12 && maxOf(r, g, b) < 245
    }

    /** The lens pixel (inside its square half) a direction lands on, or null. */
    private fun lensPixel(model: DualFisheye, x: Float, y: Float, z: Float, cx: Float, cy: Float, side: Int): Pair<Int, Int>? {
        val theta = acos(z.coerceIn(-1f, 1f))
        if (theta > model.maxTheta) return null
        val r = model.focal * side * theta
        val len = hypot(x, y)
        val px = cx * side + if (len > 1e-6f) r * x / len else 0f
        val py = cy * side - if (len > 1e-6f) r * y / len else 0f
        val ix = px.toInt(); val iy = py.toInt()
        if (ix < 1 || iy < 1 || ix >= side - 1 || iy >= side - 1) return null
        return ix to iy
    }

}

/**
 * GLSL ES 3.00: the colour of a world direction on the sphere, from the two lens textures. Where
 * both lenses see it the two are blended in three frequency bands, each over its own width: fine
 * detail switches over a narrow band (no ghosting), broad shading over a wide one (no visible
 * step). The bands come from the textures' mipmaps.
 */
object SphereShader {
    const val UNIFORMS = """
uniform sampler2D uFront;
uniform sampler2D uBack;
uniform float uFocal;
uniform vec4 uCenters;
uniform mat3 uBackRot;
uniform float uMaxTheta;
uniform vec3 uGainFront;
uniform vec3 uGainBack;
uniform vec2 uVignette;
"""

    const val FUNCTIONS = """
const float NARROW = 0.025;
const float MID = 0.07;
const float WIDE = 0.16;

// Where direction v (lens frame) lands in the lens texture, the gain undoing the fall-off there,
// and how far (radians) it is from the edge of what the lens sees: negative outside
float lensPoint(vec3 v, vec2 center, out vec2 uv, out float vignette) {
    float th = acos(clamp(v.z, -1.0, 1.0));
    vignette = exp(uVignette.x * pow(min(th, uMaxTheta) / 1.57079633, uVignette.y));
    float len = length(v.xy);
    vec2 dir = len > 1e-6 ? v.xy / len : vec2(1.0, 0.0);
    uv = center + uFocal * th * vec2(dir.x, -dir.y);
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)) / uFocal;
    return min(uMaxTheta - th, edge);
}

float weight(float t, float width, float overlap) {
    return clamp(0.5 + t / (2.0 * min(width, overlap)), 0.0, 1.0);
}

vec3 sphereColor(vec3 d) {
    vec2 uvF;
    vec2 uvB;
    float vF;
    float vB;
    float mF = lensPoint(d, uCenters.xy, uvF, vF);
    float mB = lensPoint(uBackRot * d, uCenters.zw, uvB, vB);
    vec3 gF = uGainFront * vF;
    vec3 gB = uGainBack * vB;
    if (mB <= 0.0) return mF > 0.0 ? clamp(texture(uFront, uvF).rgb * gF, 0.0, 1.0) : vec3(0.0);
    if (mF <= 0.0) return clamp(texture(uBack, uvB).rgb * gB, 0.0, 1.0);
    vec3 f0 = textureLod(uFront, uvF, 0.0).rgb * gF;
    vec3 f2 = textureLod(uFront, uvF, 2.5).rgb * gF;
    vec3 f5 = textureLod(uFront, uvF, 5.0).rgb * gF;
    vec3 b0 = textureLod(uBack, uvB, 0.0).rgb * gB;
    vec3 b2 = textureLod(uBack, uvB, 2.5).rgb * gB;
    vec3 b5 = textureLod(uBack, uvB, 5.0).rgb * gB;
    float t = mF - mB;
    float overlap = mF + mB;
    vec3 hi = mix(b0 - b2, f0 - f2, weight(t, NARROW, overlap));
    vec3 mid = mix(b2 - b5, f2 - f5, weight(t, MID, overlap));
    vec3 lo = mix(b5, f5, weight(t, WIDE, overlap));
    return clamp(lo + mid + hi, 0.0, 1.0);
}
"""
}
