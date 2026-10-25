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
import com.simoneluconi.revive360.stitch.Gl
import com.simoneluconi.revive360.stitch.Mat3
import com.simoneluconi.revive360.stitch.SphereBinding
import com.simoneluconi.revive360.stitch.SphereShader
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * Looks around a dual-fisheye picture as a sphere, stitched on the fly by the same shader the
 * export uses: drag to turn, pinch to zoom, double tap to go back to the front lens.
 */
@SuppressLint("ViewConstructor")
class PanoramaView(context: Context, private val model: DualFisheye) : GLSurfaceView(context) {
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var fov = DEFAULT_FOV
    @Volatile private var image: DualFisheyeImage? = null
    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** The picture to show; the view uploads it and keeps it until replaced. */
    fun show(picture: DualFisheyeImage?) {
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

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var binding: SphereBinding? = null
        private var textures: IntArray? = null
        private var shown: DualFisheyeImage? = null
        private var viewportLoc = 0
        private var viewLoc = 0
        private var tanLoc = 0

        fun dropTextures() {
            textures?.let { GLES30.glDeleteTextures(2, it, 0) }
            textures = null
            shown = null
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // A new context: the old one's objects are gone with it
            textures = null
            shown = null
            program = Gl.program(Gl.FULLSCREEN_VERTEX, FRAGMENT)
            binding = SphereBinding(program)
            viewportLoc = GLES30.glGetUniformLocation(program, "uViewport")
            viewLoc = GLES30.glGetUniformLocation(program, "uView")
            tanLoc = GLES30.glGetUniformLocation(program, "uTanHalf")
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
                textures = intArrayOf(Gl.texture(picture.front), Gl.texture(picture.back))
                shown = picture
            }
            val tex = textures ?: return
            GLES30.glUseProgram(program)
            binding?.bind(model, tex[0], tex[1], picture.gains)
            GLES30.glUniform2f(viewportLoc, width.toFloat(), height.toFloat())
            val view = Mat3.multiply(Mat3.rotation(yaw, 0f, 0f), Mat3.rotation(0f, pitch, 0f))
            GLES30.glUniformMatrix3fv(viewLoc, 1, false, view, 0)
            GLES30.glUniform1f(tanLoc, tan(Math.toRadians(fov / 2.0)).toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }

    private companion object {
        const val DEFAULT_FOV = 90f
        const val MIN_FOV = 20f
        const val MAX_FOV = 120f
        val MAX_PITCH = Math.toRadians(89.0).toFloat()

        const val FRAGMENT = "#version 300 es\nprecision highp float;\n" + SphereShader.UNIFORMS + """
uniform vec2 uViewport;
uniform mat3 uView;
uniform float uTanHalf;
out vec4 color;
""" + SphereShader.FUNCTIONS + """
void main() {
    vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
    vec3 ray = normalize(vec3(ndc.x * uTanHalf * uViewport.x / uViewport.y, ndc.y * uTanHalf, 1.0));
    color = vec4(sphereColor(uView * ray), 1.0);
}
"""
    }
}
