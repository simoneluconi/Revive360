package com.simoneluconi.revive360.stitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** A picture the 360 viewer can show: a camera original ([DualFisheyeImage]) or a stitched copy. */
sealed interface Panorama

/** An equirectangular (2:1) picture: longitude across, latitude down, the front in the middle. */
class EquirectImage(val bitmap: Bitmap) : Panorama {
    companion object {
        /** [maxWidth]: the largest texture the GPU takes; wider pictures are decoded smaller. */
        fun load(file: File, maxWidth: Int): EquirectImage {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight * 2) {
                "not a 360 picture (${bounds.outWidth}x${bounds.outHeight})"
            }
            var sample = 1
            while (bounds.outWidth / sample > maxWidth) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("can't decode ${file.name}")
            return EquirectImage(bitmap)
        }
    }
}
