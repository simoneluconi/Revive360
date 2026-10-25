package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan

/** How a view maps the sphere onto a flat picture. */
enum class Projection {
    /** An ordinary camera picture: straight lines stay straight. */
    RECTILINEAR,
    /** Stereographic: looking down it is the "little planet", looking up the "tunnel". */
    STEREOGRAPHIC,
}

/**
 * Where a view looks: [yaw] and [pitch] (radians; positive pitch looks down), its vertical field of
 * view [fov] (degrees) and [projection]. [level] is the picture's own correction (column-major
 * world-to-picture rotation, see [PanoramaEdit]).
 */
data class ViewState(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val fov: Float = 90f,
    val projection: Projection = Projection.RECTILINEAR,
    val level: FloatArray = Mat3.identity(),
) {
    /** View to world, column-major. */
    fun viewMatrix(): FloatArray = Mat3.multiply(Mat3.rotation(yaw, 0f, 0f), Mat3.rotation(0f, pitch, 0f))

    /** The shader's scale: half the view's height on the projection plane. */
    fun scale(): Float = when (projection) {
        Projection.RECTILINEAR -> tan(Math.toRadians(fov / 2.0)).toFloat()
        Projection.STEREOGRAPHIC -> tan(Math.toRadians(fov / 4.0)).toFloat()
    }

    override fun equals(other: Any?): Boolean = other is ViewState && yaw == other.yaw && pitch == other.pitch &&
        fov == other.fov && projection == other.projection && level.contentEquals(other.level)

    override fun hashCode(): Int = ((yaw.hashCode() * 31 + pitch.hashCode()) * 31 + fov.hashCode()) * 31 + projection.hashCode()
}

/**
 * Draws a [Panorama] as seen from a [ViewState], in the GL context current on the calling thread:
 * the on-screen viewer and the flat-picture export share it, so what is saved is what was shown.
 */
class ViewRenderer(private val model: DualFisheye) {
    private class ViewProgram(fragment: String) {
        val id = Gl.program(Gl.FULLSCREEN_VERTEX, fragment)
        val viewport = GLES30.glGetUniformLocation(id, "uViewport")
        val view = GLES30.glGetUniformLocation(id, "uView")
        val level = GLES30.glGetUniformLocation(id, "uLevel")
        val scale = GLES30.glGetUniformLocation(id, "uScale")
        val projection = GLES30.glGetUniformLocation(id, "uProjection")
        val flip = GLES30.glGetUniformLocation(id, "uFlipY")
    }

    private var dual: ViewProgram? = null
    private var equirect: ViewProgram? = null
    private var binding: SphereBinding? = null
    private var textures: IntArray? = null
    private var shown: Panorama? = null

    /** In a new context: everything of an old one is gone with it. */
    fun create() {
        textures = null
        shown = null
        dual = ViewProgram(DUAL_FRAGMENT).also { binding = SphereBinding(it.id) }
        equirect = ViewProgram(EQUIRECT_FRAGMENT)
    }

    fun dropTextures() {
        textures?.let { GLES30.glDeleteTextures(it.size, it, 0) }
        textures = null
        shown = null
    }

    fun release() {
        dropTextures()
        dual?.let { GLES30.glDeleteProgram(it.id) }
        equirect?.let { GLES30.glDeleteProgram(it.id) }
        dual = null
        equirect = null
    }

    /** Into the bound framebuffer, [width] x [height]; [flipY] for a picture read back with glReadPixels. */
    fun draw(picture: Panorama, width: Int, height: Int, view: ViewState, flipY: Boolean = false) {
        if (shown !== picture) {
            dropTextures()
            textures = when (picture) {
                is DualFisheyeImage -> intArrayOf(Gl.texture(picture.front), Gl.texture(picture.back))
                // Longitude wraps around: the texture repeats across
                is EquirectImage -> intArrayOf(Gl.texture(picture.bitmap)).also {
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                }
            }
            shown = picture
        }
        val tex = textures ?: return
        val program = when (picture) {
            is DualFisheyeImage -> dual?.also {
                GLES30.glUseProgram(it.id)
                binding?.bind(model, tex[0], tex[1], picture.gains)
            }
            is EquirectImage -> equirect?.also {
                GLES30.glUseProgram(it.id)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glUniform1i(GLES30.glGetUniformLocation(it.id, "uPano"), 0)
            }
        } ?: return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUniform2f(program.viewport, width.toFloat(), height.toFloat())
        GLES30.glUniformMatrix3fv(program.view, 1, false, view.viewMatrix(), 0)
        GLES30.glUniformMatrix3fv(program.level, 1, false, view.level, 0)
        GLES30.glUniform1f(program.scale, view.scale())
        GLES30.glUniform1i(program.projection, view.projection.ordinal)
        GLES30.glUniform1f(program.flip, if (flipY) -1f else 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    companion object {
        /** The picture direction of this pixel, from the view uniforms. */
        private const val VIEW_RAY = """
uniform vec2 uViewport;
uniform mat3 uView;
uniform mat3 uLevel;
uniform float uScale;
uniform int uProjection;
uniform float uFlipY;
out vec4 color;

vec3 viewRay() {
    vec2 p = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
    p = vec2(p.x * uViewport.x / uViewport.y, p.y * uFlipY) * uScale;
    vec3 r;
    if (uProjection == 1) {
        float q = dot(p, p);
        r = vec3(2.0 * p, 1.0 - q) / (1.0 + q);
    } else {
        r = normalize(vec3(p, 1.0));
    }
    return uLevel * (uView * r);
}
"""

        private const val DUAL_FRAGMENT = "#version 300 es\nprecision highp float;\n" + SphereShader.UNIFORMS + VIEW_RAY +
            SphereShader.FUNCTIONS + """
void main() {
    color = vec4(sphereColor(viewRay()), 1.0);
}
"""

        // The level of detail from the coordinates' own derivatives, with longitude's jump at the
        // back taken out (it would pick the smallest mip level there)
        private const val EQUIRECT_FRAGMENT = "#version 300 es\nprecision highp float;\nuniform sampler2D uPano;\n" +
            VIEW_RAY + """
void main() {
    vec3 d = viewRay();
    vec2 uv = vec2(atan(d.x, d.z) / 6.28318531 + 0.5, 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.14159265);
    vec2 dx = dFdx(uv);
    vec2 dy = dFdy(uv);
    dx.x -= round(dx.x);
    dy.x -= round(dy.x);
    color = vec4(textureGrad(uPano, uv, dx, dy).rgb, 1.0);
}
"""

        /**
         * The view as a [width] x [height] picture, off screen. Call off the main thread: it makes
         * its own GL context.
         */
        fun render(picture: Panorama, model: DualFisheye, view: ViewState, width: Int, height: Int): Bitmap =
            OffscreenGl().use {
                val renderer = ViewRenderer(model).apply { create() }
                val target = IntArray(1)
                val fbo = IntArray(1)
                try {
                    GLES30.glGenTextures(1, target, 0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target[0])
                    GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
                    GLES30.glGenFramebuffers(1, fbo, 0)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target[0], 0)
                    check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete" }
                    // Read back bottom row first: drawn upside down, the rows land top first
                    renderer.draw(picture, width, height, view, flipY = true)
                    val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                    GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    Gl.check("view read back")
                    pixels.position(0)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
                } finally {
                    GLES30.glDeleteFramebuffers(1, fbo, 0)
                    GLES30.glDeleteTextures(1, target, 0)
                    renderer.release()
                }
            }
    }
}
