package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import java.io.File

/**
 * A dual-fisheye picture split into its two lenses (square halves), plus the gains that even out
 * their brightness. The halves are decoded straight from the JPEG, never the whole picture at once.
 */
class DualFisheyeImage(
    val front: Bitmap,
    val back: Bitmap,
    /** Front r, g, b then back r, g, b (see [LensGains]). */
    val gains: FloatArray,
) : Panorama {
    val side: Int get() = front.height

    fun recycle() {
        front.recycle()
        back.recycle()
    }

    companion object {
        /** [maxSide]: the largest texture the GPU takes; bigger halves are decoded at half size. */
        fun load(file: File, model: DualFisheye, maxSide: Int = Int.MAX_VALUE): DualFisheyeImage {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            require(width > 0 && width == height * 2) { "not a dual-fisheye picture (${width}x$height)" }
            var sample = 1
            while (height / sample > maxSide) sample *= 2
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.path, false)
            } ?: error("can't read ${file.name}")
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val front = decoder.decodeRegion(Rect(0, 0, height, height), options)
                val back = decoder.decodeRegion(Rect(height, 0, width, height), options)
                val preview = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                val gains = try {
                    LensGains.estimate(preview, model)
                } finally {
                    preview.recycle()
                }
                return DualFisheyeImage(front, back, gains)
            } finally {
                decoder.recycle()
            }
        }
    }
}
