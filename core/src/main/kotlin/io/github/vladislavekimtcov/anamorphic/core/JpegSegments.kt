package io.github.vladislavekimtcov.anamorphic.core

import java.io.ByteArrayOutputStream

class JpegFormatException(message: String) : Exception(message)

/** One marker segment that carries a length field, e.g. APP1 or COM. [payload] excludes marker and length. */
class JpegSegment(val marker: Int, val payload: ByteArray) {

    fun startsWith(prefix: ByteArray): Boolean {
        if (payload.size < prefix.size) return false
        for (i in prefix.indices) if (payload[i] != prefix[i]) return false
        return true
    }

    val isApp0Jfif get() = marker == APP0 && startsWith(JFIF_ID)
    val isExif get() = marker == APP1 && startsWith(EXIF_ID)
    val isXmp get() = marker == APP1 && startsWith(XMP_ID)
    val isExtendedXmp get() = marker == APP1 && startsWith(XMP_EXTENSION_ID)
    val isIccProfile get() = marker == APP2 && startsWith(ICC_ID)
    val isMpf get() = marker == APP2 && startsWith(MPF_ID)
    val isAdobe get() = marker == APP14 && startsWith(ADOBE_ID)

    companion object {
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP14 = 0xEE
        const val COM = 0xFE
        const val MAX_PAYLOAD = 0xFFFF - 2

        val JFIF_ID = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1)
        val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val XMP_EXTENSION_ID = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
        val ICC_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
        val MPF_ID = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)
        val ADOBE_ID = "Adobe".toByteArray(Charsets.ISO_8859_1)

        fun withPrefix(marker: Int, prefix: ByteArray, body: ByteArray): JpegSegment =
            JpegSegment(marker, prefix + body)
    }
}

/**
 * A JPEG split into the metadata segments that precede the image ([metadata]: APPn and COM),
 * the [image] itself (everything from the first other marker up to and including EOI), and any
 * [trailer] appended after EOI (motion photo videos, gain maps, ...).
 */
class JpegFile(
    val metadata: List<JpegSegment>,
    val image: ByteArray,
    val trailer: ByteArray,
) {
    /** Width and height from the frame header (SOFn). */
    val frameSize: Pair<Int, Int> by lazy { readFrameSize(image) }

    fun toByteArray(): ByteArray = assemble(metadata, image)

    companion object {
        private const val SOI = 0xD8
        private const val EOI = 0xD9
        private const val SOS = 0xDA

        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == SOI.toByte() && bytes[2] == 0xFF.toByte()

        fun parse(bytes: ByteArray): JpegFile {
            if (!isJpeg(bytes)) throw JpegFormatException("Not a JPEG file")
            val metadata = ArrayList<JpegSegment>()
            var pos = 2
            // Leading APPn / COM segments.
            while (true) {
                val markerPos = pos
                val marker = readMarker(bytes, pos) ?: throw JpegFormatException("Truncated JPEG")
                pos = marker.second
                val code = marker.first
                if (code in 0xE0..0xEF || code == JpegSegment.COM) {
                    val length = u16(bytes, pos)
                    if (length < 2 || pos + length > bytes.size) throw JpegFormatException("Bad segment length")
                    metadata += JpegSegment(code, bytes.copyOfRange(pos + 2, pos + length))
                    pos += length
                } else {
                    val end = findImageEnd(bytes, markerPos)
                    return JpegFile(
                        metadata = metadata,
                        image = bytes.copyOfRange(markerPos, end),
                        trailer = bytes.copyOfRange(end, bytes.size),
                    )
                }
            }
        }

        fun assemble(metadata: List<JpegSegment>, image: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(image.size + metadata.sumOf { it.payload.size + 4 } + 2)
            out.write(0xFF)
            out.write(SOI)
            for (segment in metadata) {
                require(segment.payload.size <= JpegSegment.MAX_PAYLOAD) { "Segment too large" }
                val length = segment.payload.size + 2
                out.write(0xFF)
                out.write(segment.marker)
                out.write(length ushr 8)
                out.write(length and 0xFF)
                out.write(segment.payload)
            }
            out.write(image)
            return out.toByteArray()
        }

        /** Returns (marker code, position after the marker), skipping fill bytes. */
        private fun readMarker(bytes: ByteArray, start: Int): Pair<Int, Int>? {
            var pos = start
            if (pos >= bytes.size || bytes[pos] != 0xFF.toByte()) throw JpegFormatException("Expected marker at $pos")
            while (pos < bytes.size && bytes[pos] == 0xFF.toByte()) pos++
            if (pos >= bytes.size) return null
            return (bytes[pos].toInt() and 0xFF) to pos + 1
        }

        /** Walks the segments and entropy-coded data from [start] and returns the offset just after EOI. */
        private fun findImageEnd(bytes: ByteArray, start: Int): Int {
            var pos = start
            while (pos < bytes.size) {
                val (code, next) = readMarker(bytes, pos) ?: throw JpegFormatException("Truncated JPEG")
                pos = next
                when {
                    code == EOI -> return pos
                    code == 0x01 || code in 0xD0..0xD7 -> Unit // standalone markers
                    else -> {
                        val length = u16(bytes, pos)
                        if (length < 2 || pos + length > bytes.size) throw JpegFormatException("Bad segment length")
                        pos += length
                        if (code == SOS) pos = skipEntropyCodedData(bytes, pos)
                    }
                }
            }
            throw JpegFormatException("Missing EOI")
        }

        private fun skipEntropyCodedData(bytes: ByteArray, start: Int): Int {
            var pos = start
            while (pos + 1 < bytes.size) {
                if (bytes[pos] == 0xFF.toByte()) {
                    val next = bytes[pos + 1].toInt() and 0xFF
                    if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) return pos
                }
                pos++
            }
            throw JpegFormatException("Truncated scan data")
        }

        private fun readFrameSize(image: ByteArray): Pair<Int, Int> {
            var pos = 0
            while (pos < image.size) {
                val (code, next) = readMarker(image, pos) ?: break
                pos = next
                if (code == EOI || code == SOS) break
                if (code == 0x01 || code in 0xD0..0xD7) continue
                val length = u16(image, pos)
                val isSof = code in 0xC0..0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC
                if (isSof && pos + 7 <= image.size) {
                    val height = u16(image, pos + 3)
                    val width = u16(image, pos + 5)
                    return width to height
                }
                pos += length
            }
            throw JpegFormatException("No frame header")
        }

        private fun u16(bytes: ByteArray, pos: Int): Int {
            if (pos + 2 > bytes.size) throw JpegFormatException("Truncated JPEG")
            return ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        }
    }
}
