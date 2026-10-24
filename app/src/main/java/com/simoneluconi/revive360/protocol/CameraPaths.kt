package com.simoneluconi.revive360.protocol

/** File paths as the protocol wants them in URLs (§2 Paths): each segment percent-encoded, `/` kept. */
object CameraPaths {
    private const val HEX = "0123456789ABCDEF"

    /** The checks the camera makes too: rejected here so a bad path never leaves the phone. */
    fun isValid(path: String): Boolean {
        if (!path.startsWith("DCIM/") || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun encode(path: String): String {
        require(isValid(path)) { "bad camera path: $path" }
        return path.split('/').joinToString("/", transform = ::encodeSegment)
    }

    /** RFC 3986: unreserved characters stay, everything else becomes %XX of its UTF-8 bytes. */
    fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }
}
