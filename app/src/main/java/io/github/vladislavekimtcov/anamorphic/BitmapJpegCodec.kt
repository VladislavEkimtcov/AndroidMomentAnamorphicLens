package io.github.vladislavekimtcov.anamorphic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import io.github.vladislavekimtcov.anamorphic.core.JpegCodec
import io.github.vladislavekimtcov.anamorphic.core.RawImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.IntBuffer

/**
 * JPEG codec on top of the platform decoder/encoder.
 *
 * Pixels move with copyPixelsToBuffer/copyPixelsFromBuffer, which copy the raw values: unlike
 * getPixels/setPixels they don't convert to sRGB, so wide-gamut (Display P3) photos stay intact.
 * The decoded color space travels in [RawImage.colorInfo] and the encoder embeds its ICC profile.
 */
class BitmapJpegCodec : JpegCodec {

    override fun decode(jpeg: ByteArray): RawImage {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IOException("This photo could not be decoded")
        try {
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                bitmap = converted ?: throw IOException("This photo could not be decoded")
            }
            if (bitmap.rowBytes != bitmap.width * 4) throw IOException("Unsupported bitmap layout")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.copyPixelsToBuffer(IntBuffer.wrap(pixels))
            return RawImage(bitmap.width, bitmap.height, pixels, bitmap.colorSpace)
        } finally {
            bitmap.recycle()
        }
    }

    override fun encode(image: RawImage, quality: Int): ByteArray {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val colorSpace = image.colorInfo as? ColorSpace ?: srgb
        val bitmap = try {
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false, colorSpace)
        } catch (_: IllegalArgumentException) {
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false, srgb)
        }
        try {
            bitmap.copyPixelsFromBuffer(IntBuffer.wrap(image.pixels))
            val out = ByteArrayOutputStream(image.width * image.height / 3)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("JPEG encoding failed")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
