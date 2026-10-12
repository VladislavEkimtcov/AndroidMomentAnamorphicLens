package io.github.vladislavekimtcov.anamorphic.core

class ExifFormatException(message: String) : Exception(message)

/** A freshly encoded EXIF thumbnail. */
class Thumbnail(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Minimal EXIF (TIFF) reader/patcher.
 *
 * Instead of re-serializing the whole EXIF block (which would drop tags a library doesn't know
 * and break offset-based vendor data such as Google's MakerNote), it patches the few values that
 * change in place and replaces the embedded thumbnail. Every other byte is kept as it was.
 */
class ExifPatcher(app1Payload: ByteArray) {

    private val tiff: ByteArray
    private val littleEndian: Boolean
    private val ifd0: Int

    init {
        if (app1Payload.size < JpegSegment.EXIF_ID.size + 8 ||
            !app1Payload.copyOfRange(0, JpegSegment.EXIF_ID.size).contentEquals(JpegSegment.EXIF_ID)
        ) {
            throw ExifFormatException("Not an EXIF segment")
        }
        tiff = app1Payload.copyOfRange(JpegSegment.EXIF_ID.size, app1Payload.size)
        littleEndian = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> throw ExifFormatException("Bad TIFF byte order")
        }
        if (u16(2) != 42) throw ExifFormatException("Bad TIFF magic")
        ifd0 = u32Offset(4)
        readEntries(ifd0) // validate
    }

    private class Entry(val position: Int, val tag: Int, val type: Int, val count: Long, val valueField: Int)

    /** EXIF Orientation (1..8), or null when absent. */
    val orientation: Int? get() = findEntry(ifd0, TAG_ORIENTATION)?.let { readInteger(it) }

    private val ifd1: Int? get() = nextIfd(ifd0)

    /** Size of the existing JPEG thumbnail, if there is one we can read. */
    val thumbnailSize: Pair<Int, Int>?
        get() {
            val bytes = thumbnailBytes() ?: return null
            return try {
                JpegFile.parse(bytes).frameSize
            } catch (_: JpegFormatException) {
                null
            }
        }

    fun thumbnailBytes(): ByteArray? {
        val ifd = ifd1 ?: return null
        val offset = findEntry(ifd, TAG_JPEG_OFFSET)?.let { readInteger(it) } ?: return null
        val length = findEntry(ifd, TAG_JPEG_LENGTH)?.let { readInteger(it) } ?: return null
        if (offset <= 0 || length <= 0 || offset.toLong() + length > tiff.size) return null
        return tiff.copyOfRange(offset, offset + length)
    }

    val hasThumbnailIfd: Boolean get() = ifd1 != null

    /**
     * Returns a new APP1 payload with the image dimensions set to [width] x [height] (stored
     * pixel orientation) and the thumbnail replaced.
     *
     * [thumbnail] is called with the maximum number of bytes the thumbnail may take and should
     * return null when it cannot produce one that small; the thumbnail IFD is then dropped so no
     * stale (still squeezed) preview survives.
     */
    fun patched(width: Int, height: Int, thumbnail: ((maxBytes: Int) -> Thumbnail?)?): ByteArray {
        var out = tiff.copyOf()

        patchIn(out, ifd0, TAG_IMAGE_WIDTH, width)
        patchIn(out, ifd0, TAG_IMAGE_LENGTH, height)
        patchIn(out, ifd0, TAG_PIXEL_X_DIMENSION, width)
        patchIn(out, ifd0, TAG_PIXEL_Y_DIMENSION, height)
        findEntry(ifd0, TAG_EXIF_IFD)?.let { readInteger(it) }?.takeIf { isValidIfd(it) }?.let { exifIfd ->
            patchIn(out, exifIfd, TAG_PIXEL_X_DIMENSION, width)
            patchIn(out, exifIfd, TAG_PIXEL_Y_DIMENSION, height)
        }

        val ifd = ifd1
        if (ifd != null) {
            val offsetEntry = findEntry(ifd, TAG_JPEG_OFFSET)?.takeIf { readInteger(it) != null }
            val lengthEntry = findEntry(ifd, TAG_JPEG_LENGTH)?.takeIf { readInteger(it) != null }
            val oldOffset = offsetEntry?.let { readInteger(it) } ?: 0
            val oldLength = lengthEntry?.let { readInteger(it) } ?: 0
            // The usual layout keeps the thumbnail as the last thing in the block; reuse its space then.
            val atEnd = oldOffset > 0 && oldLength > 0 && oldOffset.toLong() + oldLength == out.size.toLong() &&
                nextIfdPointerPosition(ifd) + 4 <= oldOffset
            var replaced = false
            if (offsetEntry != null && lengthEntry != null && thumbnail != null) {
                var base = if (atEnd) out.copyOf(oldOffset) else out
                if (base.size % 2 == 1) base += 0
                val maxBytes = JpegSegment.MAX_PAYLOAD - JpegSegment.EXIF_ID.size - base.size
                val thumb = if (maxBytes > 0) thumbnail(maxBytes) else null
                if (thumb != null && thumb.jpeg.size <= maxBytes) {
                    out = base + thumb.jpeg
                    patchIn(out, ifd, TAG_JPEG_OFFSET, base.size)
                    patchIn(out, ifd, TAG_JPEG_LENGTH, thumb.jpeg.size)
                    patchIn(out, ifd, TAG_IMAGE_WIDTH, thumb.width)
                    patchIn(out, ifd, TAG_IMAGE_LENGTH, thumb.height)
                    replaced = true
                }
            }
            if (!replaced) {
                // Unlink IFD1 so readers don't show the stale (still squeezed) preview.
                if (atEnd) out = out.copyOf(oldOffset)
                writeU32(out, nextIfdPointerPosition(ifd0), 0)
            }
        }

        if (JpegSegment.EXIF_ID.size + out.size > JpegSegment.MAX_PAYLOAD) {
            throw ExifFormatException("EXIF block too large")
        }
        return JpegSegment.EXIF_ID + out
    }

    /** Overwrites a single inline SHORT/LONG value, widening it to LONG so any size fits. */
    private fun patchIn(out: ByteArray, ifd: Int, tag: Int, value: Int) {
        val entry = findEntry(ifd, tag) ?: return
        if (entry.count != 1L || (entry.type != TYPE_SHORT && entry.type != TYPE_LONG)) return
        writeU16(out, entry.position + 2, TYPE_LONG)
        writeU32(out, entry.position + 8, value.toLong())
    }

    // --- TIFF structure -----------------------------------------------------------------------

    private fun isValidIfd(offset: Int): Boolean = offset >= 8 && offset + 2 <= tiff.size

    private fun readEntries(ifd: Int): List<Entry> {
        if (!isValidIfd(ifd)) throw ExifFormatException("Bad IFD offset $ifd")
        val count = u16(ifd)
        if (ifd + 2 + count * 12 + 4 > tiff.size) throw ExifFormatException("Truncated IFD")
        return (0 until count).map { i ->
            val p = ifd + 2 + i * 12
            Entry(p, u16(p), u16(p + 2), u32(p + 4), p + 8)
        }
    }

    private fun findEntry(ifd: Int, tag: Int): Entry? = readEntries(ifd).firstOrNull { it.tag == tag }

    private fun nextIfdPointerPosition(ifd: Int): Int = ifd + 2 + u16(ifd) * 12

    private fun nextIfd(ifd: Int): Int? {
        val next = u32(nextIfdPointerPosition(ifd))
        if (next == 0L || next > Int.MAX_VALUE) return null
        return next.toInt().takeIf { isValidIfd(it) && runCatching { readEntries(it) }.isSuccess }
    }

    /** Reads a single SHORT or LONG value stored inline. */
    private fun readInteger(entry: Entry): Int? = when {
        entry.count != 1L -> null
        entry.type == TYPE_SHORT -> u16(entry.valueField)
        entry.type == TYPE_LONG -> u32(entry.valueField).takeIf { it <= Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun u16(pos: Int): Int {
        if (pos < 0 || pos + 2 > tiff.size) throw ExifFormatException("Out of bounds")
        val a = tiff[pos].toInt() and 0xFF
        val b = tiff[pos + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    private fun u32(pos: Int): Long {
        if (pos < 0 || pos + 4 > tiff.size) throw ExifFormatException("Out of bounds")
        val bytes = LongArray(4) { (tiff[pos + it].toLong() and 0xFF) }
        return if (littleEndian) {
            bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16) or (bytes[3] shl 24)
        } else {
            (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
        }
    }

    private fun u32Offset(pos: Int): Int {
        val value = u32(pos)
        if (value > Int.MAX_VALUE) throw ExifFormatException("Bad offset")
        return value.toInt()
    }

    private fun writeU16(out: ByteArray, pos: Int, value: Int) {
        if (littleEndian) {
            out[pos] = value.toByte(); out[pos + 1] = (value ushr 8).toByte()
        } else {
            out[pos] = (value ushr 8).toByte(); out[pos + 1] = value.toByte()
        }
    }

    private fun writeU32(out: ByteArray, pos: Int, value: Long) {
        for (i in 0 until 4) {
            val shift = if (littleEndian) 8 * i else 8 * (3 - i)
            out[pos + i] = (value ushr shift).toByte()
        }
    }

    companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4

        const val TAG_IMAGE_WIDTH = 0x0100
        const val TAG_IMAGE_LENGTH = 0x0101
        const val TAG_ORIENTATION = 0x0112
        const val TAG_JPEG_OFFSET = 0x0201
        const val TAG_JPEG_LENGTH = 0x0202
        const val TAG_EXIF_IFD = 0x8769
        const val TAG_PIXEL_X_DIMENSION = 0xA002
        const val TAG_PIXEL_Y_DIMENSION = 0xA003
    }
}
