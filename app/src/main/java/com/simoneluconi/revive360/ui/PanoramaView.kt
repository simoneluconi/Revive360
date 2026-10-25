package com.simoneluconi.revive360.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.DualFisheyeImage
import com.simoneluconi.revive360.stitch.EquirectImage
import com.simoneluconi.revive360.stitch.Panorama
import com.simoneluconi.revive360.stitch.Gl
import com.simoneluconi.revive360.stitch.Mat3
import com.simoneluconi.revive360.stitch.SphereBinding
import com.simoneluconi.revive360.stitch.SphereShader
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.log2
import kotlin.math.tan

/**
 * Looks around a 360 picture: a dual-fisheye original, stitched on the fly by the same shader the
 * export uses, or a stitched copy. Drag to turn, pinch to zoom, double tap to face the front again.
 */
@SuppressLint("ViewConstructor")
class PanoramaView(context: Context, private val model: DualFisheye) : GLSurfaceView(context) {
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var fov = DEFAULT_FOV
    @Volatile private var image: Panorama? = null
    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** The picture to show; the view uploads it and keeps it until replaced. */
    fun show(picture: Panorama?) {
        image = picture
        queueEvent { renderer.dropTextures() }
        requestRender()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fov = (fov / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // The picture follows the finger: one screen height turns by the vertical field of view
            val perPixel = 2f * tan(Math.toRadians(fov / 2.0).toFloat()) / height.coerceAtLeast(1)
            yaw += dx * perPixel
            pitch = (pitch + dy * perPixel).coerceIn(-MAX_PITCH, MAX_PITCH)
            requestRender()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            yaw = 0f
            pitch = 0f
            fov = DEFAULT_FOV
            requestRender()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** A program and the view uniforms every one of them has. */
    private class ViewProgram(fragment: String) {
        val id = Gl.program(Gl.FULLSCREEN_VERTEX, fragment)
        val viewport = GLES30.glGetUniformLocation(id, "uViewport")
        val view = GLES30.glGetUniformLocation(id, "uView")
        val tanHalf = GLES30.glGetUniformLocation(id, "uTanHalf")
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var dual: ViewProgram? = null
        private var equirect: ViewProgram? = null
        private var binding: SphereBinding? = null
        private var textures: IntArray? = null
        private var shown: Panorama? = null

        fun dropTextures() {
            textures?.let { GLES30.glDeleteTextures(it.size, it, 0) }
            textures = null
            shown = null
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // A new context: the old one's objects are gone with it
            textures = null
            shown = null
            dual = ViewProgram(DUAL_FRAGMENT).also { binding = SphereBinding(it.id) }
            equirect = ViewProgram(EQUIRECT_FRAGMENT)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val picture = image ?: return
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
            val tanHalf = tan(Math.toRadians(fov / 2.0)).toFloat()
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
                    // The mip level by hand: atan's jump at the back would pick the smallest one
                    val texelsPerPixel = picture.bitmap.width / (2f * PI.toFloat()) * 2f * tanHalf / height.coerceAtLeast(1)
                    GLES30.glUniform1f(GLES30.glGetUniformLocation(it.id, "uLod"), log2(texelsPerPixel.coerceAtLeast(1f)))
                }
            } ?: return
            GLES30.glUniform2f(program.viewport, width.toFloat(), height.toFloat())
            val view = Mat3.multiply(Mat3.rotation(yaw, 0f, 0f), Mat3.rotation(0f, pitch, 0f))
            GLES30.glUniformMatrix3fv(program.view, 1, false, view, 0)
            GLES30.glUniform1f(program.tanHalf, tanHalf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }

    private companion object {
        const val DEFAULT_FOV = 90f
        const val MIN_FOV = 20f
        const val MAX_FOV = 120f
        val MAX_PITCH = Math.toRadians(89.0).toFloat()

        /** The world direction of this pixel, from the view uniforms. */
        const val VIEW_RAY = """
uniform vec2 uViewport;
uniform mat3 uView;
uniform float uTanHalf;
out vec4 color;

vec3 viewRay() {
    vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
    return uView * normalize(vec3(ndc.x * uTanHalf * uViewport.x / uViewport.y, ndc.y * uTanHalf, 1.0));
}
"""

        const val DUAL_FRAGMENT = "#version 300 es\nprecision highp float;\n" + SphereShader.UNIFORMS + VIEW_RAY +
            SphereShader.FUNCTIONS + """
void main() {
    color = vec4(sphereColor(viewRay()), 1.0);
}
"""

        const val EQUIRECT_FRAGMENT = "#version 300 es\nprecision highp float;\nuniform sampler2D uPano;\nuniform float uLod;\n" +
            VIEW_RAY + """
void main() {
    vec3 d = viewRay();
    vec2 uv = vec2(atan(d.x, d.z) / 6.28318531 + 0.5, 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.14159265);
    color = vec4(textureLod(uPano, uv, uLod).rgb, 1.0);
}
"""
    }
}
