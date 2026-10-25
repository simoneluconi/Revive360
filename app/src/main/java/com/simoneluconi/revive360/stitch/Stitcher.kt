package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binds a program built on [SphereShader] to a picture's two lens textures. */
class SphereBinding(private val program: Int) {
    private val front = GLES30.glGetUniformLocation(program, "uFront")
    private val back = GLES30.glGetUniformLocation(program, "uBack")
    private val focal = GLES30.glGetUniformLocation(program, "uFocal")
    private val centers = GLES30.glGetUniformLocation(program, "uCenters")
    private val backRot = GLES30.glGetUniformLocation(program, "uBackRot")
    private val maxTheta = GLES30.glGetUniformLocation(program, "uMaxTheta")
    private val gainFront = GLES30.glGetUniformLocation(program, "uGainFront")
    private val gainBack = GLES30.glGetUniformLocation(program, "uGainBack")
    private val vignette = GLES30.glGetUniformLocation(program, "uVignette")

    /** With [program] in use. */
    fun bind(model: DualFisheye, frontTexture: Int, backTexture: Int, gains: FloatArray) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frontTexture)
        GLES30.glUniform1i(front, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backTexture)
        GLES30.glUniform1i(back, 1)
        GLES30.glUniform1f(focal, model.focal)
        GLES30.glUniform4f(centers, model.frontCenterX, model.frontCenterY, model.backCenterX, model.backCenterY)
        GLES30.glUniformMatrix3fv(backRot, 1, false, model.backMatrix(), 0)
        GLES30.glUniform1f(maxTheta, model.maxTheta)
        GLES30.glUniform3f(gainFront, gains[0], gains[1], gains[2])
        GLES30.glUniform3f(gainBack, gains[3], gains[4], gains[5])
        GLES30.glUniform2f(vignette, model.vignetteStrength, model.vignettePower)
    }
}

/** Dual fisheye to equirectangular (2:1, the format every 360 viewer reads), on the GPU. */
object Stitcher {
    private const val FRAGMENT = "#version 300 es\nprecision highp float;\n" + SphereShader.UNIFORMS + """
uniform vec2 uOutSize;
uniform float uRow0;
uniform mat3 uOrient;
out vec4 color;
""" + SphereShader.FUNCTIONS + """
void main() {
    vec2 px = vec2(gl_FragCoord.x, uRow0 + gl_FragCoord.y);
    float lon = px.x / uOutSize.x * 6.28318531 - 3.14159265;
    float lat = 1.57079633 - px.y / uOutSize.y * 3.14159265;
    vec3 d = uOrient * vec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon));
    color = vec4(sphereColor(d), 1.0);
}
"""
    /** Rows rendered per pass: keeps each draw short (the GPU watchdog) and the target small. */
    private const val STRIP = 256

    /**
     * The picture as a [width] x width/2 equirectangular bitmap, the front lens in the middle.
     * [orientation]: world rotation applied first (horizon levelling), column-major.
     * Call off the main thread: it makes its own GL context.
     */
    fun equirect(image: DualFisheyeImage, model: DualFisheye, width: Int, orientation: FloatArray = Mat3.identity()): Bitmap {
        val height = width / 2
        OffscreenGl().use {
            val program = Gl.program(Gl.FULLSCREEN_VERTEX, FRAGMENT)
            val frontTexture = Gl.texture(image.front)
            val backTexture = Gl.texture(image.back)
            val target = IntArray(1)
            val fbo = IntArray(1)
            try {
                GLES30.glGenTextures(1, target, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target[0])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, STRIP)
                GLES30.glGenFramebuffers(1, fbo, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target[0], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete" }

                GLES30.glUseProgram(program)
                SphereBinding(program).bind(model, frontTexture, backTexture, image.gains)
                GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uOutSize"), width.toFloat(), height.toFloat())
                GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program, "uOrient"), 1, false, orientation, 0)
                val row0 = GLES30.glGetUniformLocation(program, "uRow0")
                GLES30.glViewport(0, 0, width, STRIP)

                val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                val strip = ByteBuffer.allocateDirect(width * STRIP * 4).order(ByteOrder.nativeOrder())
                var y = 0
                while (y < height) {
                    val rows = minOf(STRIP, height - y)
                    GLES30.glUniform1f(row0, y.toFloat())
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                    strip.clear()
                    // Row 0 read back is the strip's first output row (uRow0 + 0.5)
                    GLES30.glReadPixels(0, 0, width, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, strip)
                    Gl.check("strip at row $y")
                    strip.limit(width * rows * 4)
                    strip.position(0)
                    pixels.position(y * width * 4)
                    pixels.put(strip)
                    y += rows
                }
                pixels.position(0)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
            } finally {
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                GLES30.glDeleteTextures(3, intArrayOf(frontTexture, backTexture, target[0]), 0)
                GLES30.glDeleteProgram(program)
            }
        }
    }
}
