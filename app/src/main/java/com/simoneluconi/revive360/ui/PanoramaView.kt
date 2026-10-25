package com.simoneluconi.revive360.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.simoneluconi.revive360.stitch.DualFisheye
import com.simoneluconi.revive360.stitch.Panorama
import com.simoneluconi.revive360.stitch.Projection
import com.simoneluconi.revive360.stitch.ViewRenderer
import com.simoneluconi.revive360.stitch.ViewState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * Looks around a 360 picture: a dual-fisheye original, stitched on the fly by the same shader the
 * export uses, or a stitched copy. Drag to turn, pinch to zoom, double tap for the [home] view.
 */
@SuppressLint("ViewConstructor")
class PanoramaView(context: Context, model: DualFisheye) : GLSurfaceView(context) {
    @Volatile private var image: Panorama? = null
    private val renderer = ViewRenderer(model)

    /** Where the view looks now; the screen reads it to save a framing or a first view. */
    @Volatile var view: ViewState = ViewState()
        private set

    /** Where a double tap goes back to. */
    @Volatile var home: ViewState = ViewState()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = renderer.create()

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

            override fun onDrawFrame(gl: GL10?) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                image?.let { renderer.draw(it, width, height, view) }
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** The picture to show; the view uploads it and keeps it until replaced. */
    fun show(picture: Panorama?) {
        if (picture === image) return
        image = picture
        queueEvent { renderer.dropTextures() }
        requestRender()
    }

    fun setView(state: ViewState) {
        view = state
        requestRender()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val (min, max) = fovRange(view.projection)
            setView(view.copy(fov = (view.fov / detector.scaleFactor).coerceIn(min, max)))
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // The picture follows the finger: one screen height turns by about the field of view
            val v = view
            val perPixel = 2f * tan(Math.toRadians(minOf(v.fov, 120f) / 2.0).toFloat()) / height.coerceAtLeast(1)
            val maxPitch = if (v.projection == Projection.STEREOGRAPHIC) HALF_PI else MAX_PITCH
            setView(v.copy(yaw = v.yaw + dx * perPixel, pitch = (v.pitch + dy * perPixel).coerceIn(-maxPitch, maxPitch)))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            setView(home.copy(level = view.level))
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

    companion object {
        private const val HALF_PI = (Math.PI / 2).toFloat()
        private val MAX_PITCH = Math.toRadians(89.0).toFloat()

        /** Field of view limits, degrees: a stereographic view goes well past 180. */
        fun fovRange(projection: Projection): Pair<Float, Float> = when (projection) {
            Projection.RECTILINEAR -> 20f to 120f
            Projection.STEREOGRAPHIC -> 60f to 320f
        }
    }
}
