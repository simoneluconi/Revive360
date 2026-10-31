package com.simoneluconi.revive360.protocol

/**
 * One RAW dump (docs/firmware/14, 15): a lens's sensor data, 16-bit words per row. Its name carries
 * the layout: `360_0384_front_7776x3888x3950.raw` = row bytes x width x rows (the R210's: 5824x2912x2896).
 */
data class RawLayout(val rowBytes: Int, val width: Int, val rows: Int) {
    companion object {
        private val DIMS = Regex("""_(\d+)x(\d+)x(\d+)""")

        fun of(name: String): RawLayout? = DIMS.find(name)?.destructured?.let { (b, w, r) ->
            RawLayout(b.toInt(), w.toInt(), r.toInt())
        }
    }
}

/** A photo's sensor data: one dump per lens. */
data class RawPair(val front: CameraFile, val rear: CameraFile) {
    val size: Long get() = front.size + rear.size
}

/**
 * The camera's file list as shown: the RAW dumps g360rd named after their photo
 * (`DCIM/RAW/360_0384_front_….raw` and `_rear_…` for `360_0384.JPG`) leave the list and mark the
 * photo instead, so a shot is one entry. Dumps without their photo stay files of their own.
 */
class RawPairs private constructor(
    /** The list without the dumps that belong to a photo, in its order. */
    val shown: List<CameraFile>,
    private val byPhoto: Map<String, RawPair>,
) {
    /** The RAW pair of a photo, or null when it has none. */
    fun of(photo: CameraFile): RawPair? = byPhoto[photo.path]

    companion object {
        private val DUMP = Regex("""^DCIM/RAW/(.+)_(front|rear)_\d+x\d+x\d+\.raw$""")

        fun of(files: List<CameraFile>): RawPairs {
            val fronts = HashMap<String, CameraFile>()
            val rears = HashMap<String, CameraFile>()
            for (file in files) {
                val (base, lens) = DUMP.find(file.path)?.destructured ?: continue
                (if (lens == "front") fronts else rears)[base] = file
            }
            val byPhoto = HashMap<String, RawPair>()
            val paired = HashSet<String>()
            for (file in files) {
                if (file.kind != FileKind.PHOTO) continue
                val front = fronts[file.baseName] ?: continue
                val rear = rears[file.baseName] ?: continue
                byPhoto[file.path] = RawPair(front, rear)
                paired += front.path
                paired += rear.path
            }
            return RawPairs(if (paired.isEmpty()) files else files.filterNot { it.path in paired }, byPhoto)
        }
    }
}
