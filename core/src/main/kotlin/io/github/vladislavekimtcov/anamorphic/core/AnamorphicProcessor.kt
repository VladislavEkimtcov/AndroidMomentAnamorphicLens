package io.github.vladislavekimtcov.anamorphic.core

import kotlin.math.max
import kotlin.math.roundToInt

/** Platform JPEG codec (Android: BitmapFactory / Bitmap.compress). Must not apply EXIF rotation. */
interface JpegCodec {
    fun decode(jpeg: ByteArray): RawImage
    fun encode(image: RawImage, quality: Int): ByteArray
}

data class ProcessOptions(
    val mode: Mode,
    val factor: SqueezeFactor,
    /** The lens was rotated 90 degrees with the hex tool (see [Desqueeze.axisFor]). */
    val rotated: Boolean,
    val quality: Int = 95,
)

sealed class ProcessResult {
    class Success(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val axis: Axis,
        /** Non-fatal problems, e.g. metadata that had to be kept or dropped as-is. */
        val warnings: List<String>,
    ) : ProcessResult()

    /** The photo carries this app's marker, i.e. it was already stretched. */
    data object AlreadyStretched : ProcessResult()
}

/**
 * Stretches or unstretches one JPEG and carries its metadata over.
 *
 * The new file is made of the original's metadata segments (EXIF patched in place, XMP cleaned)
 * followed by the re-encoded image. Data that only made sense for the old pixels is dropped:
 * extended XMP (depth maps), MPF and anything after EOI (motion photo video, HDR gain map).
 */
class AnamorphicProcessor(private val codec: JpegCodec) {

    fun process(input: ByteArray, options: ProcessOptions): ProcessResult {
        val original = JpegFile.parse(input)
        val warnings = ArrayList<String>()

        val xmpSegment = original.metadata.firstOrNull { it.isXmp }
        val xmp = xmpSegment?.let { XmpEditor.fromSegment(it) }
        val marker = xmp?.let { XmpEditor.readMarker(it) }
        if (options.mode == Mode.STRETCH && marker != null) return ProcessResult.AlreadyStretched

        val (width, height) = original.frameSize

        val axis: Axis
        val newLength: Int
        if (options.mode == Mode.UNSTRETCH && marker != null) {
            axis = marker.axis
            val keptSide = if (axis == Axis.WIDTH) height else width
            val markerKeptSide = if (axis == Axis.WIDTH) marker.originalHeight else marker.originalWidth
            val markerLength = if (axis == Axis.WIDTH) marker.originalWidth else marker.originalHeight
            val current = if (axis == Axis.WIDTH) width else height
            newLength = if (keptSide == markerKeptSide && Desqueeze.stretched(markerLength, marker.factor) == current) {
                markerLength
            } else {
                Desqueeze.unstretched(current, marker.factor) // edited since; undo the factor only
            }
        } else {
            axis = Desqueeze.axisFor(width, height, options.rotated)
            val current = if (axis == Axis.WIDTH) width else height
            newLength = when (options.mode) {
                Mode.STRETCH -> Desqueeze.stretched(current, options.factor.value)
                Mode.UNSTRETCH -> Desqueeze.unstretched(current, options.factor.value)
            }
        }

        // Don't keep the decoded source referenced: on a phone the two buffers are ~50 MB each.
        val result = Resampler.resample(decode(input, width, height), axis, newLength)
        val encoded = JpegFile.parse(codec.encode(result, options.quality))

        val newMarker = if (options.mode == Mode.STRETCH) {
            StretchMarker(options.factor.value, axis, width, height)
        } else {
            null
        }

        val metadata = ArrayList<JpegSegment>()
        original.metadata.firstOrNull { it.isApp0Jfif }?.let { metadata += it }

        original.metadata.firstOrNull { it.isExif }?.let { exif ->
            metadata += try {
                val patcher = ExifPatcher(exif.payload)
                val oldThumbSize = patcher.thumbnailSize
                JpegSegment(JpegSegment.APP1, patcher.patched(result.width, result.height) { maxBytes ->
                    makeThumbnail(result, oldThumbSize, maxBytes)
                })
            } catch (e: ExifFormatException) {
                warnings += "EXIF could not be updated (${e.message}); copied unchanged"
                exif
            }
        }

        val newXmp = XmpEditor.rewrite(xmp, newMarker)
        if (newXmp != null) {
            val segment = XmpEditor.toSegment(newXmp)
            if (segment.payload.size <= JpegSegment.MAX_PAYLOAD) {
                metadata += segment
            } else if (xmpSegment != null) {
                warnings += "XMP too large to update; copied unchanged"
                metadata += xmpSegment
            }
        } else if (xmp != null) {
            warnings += "XMP could not be parsed and referenced embedded data; dropped"
        }

        // The encoder's ICC profile describes the pixels it wrote.
        metadata += encoded.metadata.filter { it.isIccProfile }

        // Everything else (comments, IPTC, vendor APPn) is copied as-is, except segments that
        // describe the old image data or would change how the new data is decoded.
        metadata += original.metadata.filter {
            !it.isApp0Jfif && !it.isExif && !it.isXmp && !it.isExtendedXmp && !it.isIccProfile &&
                !it.isMpf && !it.isAdobe
        }

        val jpeg = JpegFile.assemble(metadata, encoded.image)
        return ProcessResult.Success(jpeg, result.width, result.height, axis, warnings)
    }

    private fun decode(input: ByteArray, width: Int, height: Int): RawImage {
        val image = codec.decode(input)
        if (image.width != width || image.height != height) throw JpegFormatException("Unexpected decoded size")
        return image
    }

    private fun makeThumbnail(image: RawImage, oldSize: Pair<Int, Int>?, maxBytes: Int): Thumbnail? {
        val oldLongSide = oldSize?.let { max(it.first, it.second) } ?: DEFAULT_THUMBNAIL_SIDE
        val longSide = oldLongSide.coerceIn(MIN_THUMBNAIL_SIDE, MAX_THUMBNAIL_SIDE)
        for ((side, quality) in listOf(longSide to 85, longSide to 70, longSide * 3 / 4 to 70, 256 to 60, 160 to 50)) {
            val scale = side.toDouble() / max(image.width, image.height)
            if (scale >= 1.0) continue
            val w = max(1, (image.width * scale).roundToInt())
            val h = max(1, (image.height * scale).roundToInt())
            val bytes = codec.encode(Resampler.resize(image, w, h), quality)
            // Thumbnails are plain baseline JPEGs: strip the encoder's APPn segments.
            val thumb = JpegFile.assemble(emptyList(), JpegFile.parse(bytes).image)
            if (thumb.size <= maxBytes) return Thumbnail(thumb, w, h)
        }
        return null
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIDE = 512
        const val MIN_THUMBNAIL_SIDE = 160
        const val MAX_THUMBNAIL_SIDE = 640
    }
}
