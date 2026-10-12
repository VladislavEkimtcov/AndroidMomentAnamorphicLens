package io.github.vladislavekimtcov.anamorphic.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Desktop stand-in for the Android Bitmap codec. */
class ImageIoCodec : JpegCodec {
    override fun decode(jpeg: ByteArray): RawImage {
        val image = ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("ImageIO could not decode")
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RawImage(image.width, image.height, pixels)
    }

    override fun encode(image: RawImage, quality: Int): ByteArray {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(buffered, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}

object TestImages {
    /** A smooth test pattern: horizontal and vertical gradients plus a soft circle. */
    fun pattern(width: Int, height: Int): RawImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val r = 255 * x / (width - 1)
            val g = 255 * y / (height - 1)
            val dx = x - width / 2.0
            val dy = y - height / 2.0
            val b = if (dx * dx + dy * dy < (minOf(width, height) / 3.0).let { it * it }) 220 else 40
            pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RawImage(width, height, pixels)
    }

    fun jpeg(width: Int, height: Int, quality: Int = 92): ByteArray = ImageIoCodec().encode(pattern(width, height), quality)
}

/** Builds EXIF blocks with a known layout for tests. */
class TestExif(private val littleEndian: Boolean) {
    class Field(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    fun short(tag: Int, value: Int) = Field(tag, 3, 1, ByteBuffer.allocate(2).order(order).putShort(value.toShort()).array())
    fun long(tag: Int, value: Int) = Field(tag, 4, 1, ByteBuffer.allocate(4).order(order).putInt(value).array())
    fun ascii(tag: Int, value: String): Field {
        val bytes = (value + "\u0000").toByteArray(Charsets.US_ASCII)
        return Field(tag, 2, bytes.size, bytes)
    }
    fun undefined(tag: Int, value: ByteArray) = Field(tag, 7, value.size, value)
    fun rationals(tag: Int, vararg values: Pair<Int, Int>): Field {
        val buffer = ByteBuffer.allocate(8 * values.size).order(order)
        values.forEach { (n, d) -> buffer.putInt(n).putInt(d) }
        return Field(tag, 5, values.size, buffer.array())
    }

    /** Returns an APP1 payload ("Exif\0\0" + TIFF). */
    fun build(
        ifd0: List<Field>,
        exif: List<Field>,
        gps: List<Field>,
        thumbnail: ByteArray?,
        thumbnailAtEnd: Boolean = true,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(70_000).order(order)
        buffer.put(if (littleEndian) "II".toByteArray() else "MM".toByteArray())
        buffer.putShort(42)
        buffer.putInt(8)

        val ifd0Fields = ifd0 + listOf(long(0x8769, 0), long(0x8825, 0))
        val (ifd0Next, ifd0Values) = writeIfd(buffer, ifd0Fields)
        val exifPos = buffer.position()
        writeIfd(buffer, exif)
        val gpsPos = buffer.position()
        writeIfd(buffer, gps)
        buffer.putInt(ifd0Values.getValue(0x8769), exifPos)
        buffer.putInt(ifd0Values.getValue(0x8825), gpsPos)

        if (thumbnail != null) {
            var thumbPos = -1
            if (!thumbnailAtEnd) {
                thumbPos = buffer.position()
                buffer.put(thumbnail)
                if (buffer.position() % 2 == 1) buffer.put(0)
            }
            val ifd1Pos = buffer.position()
            val (_, ifd1Values) = writeIfd(buffer, listOf(long(0x0201, 0), long(0x0202, thumbnail.size), short(0x0103, 6)))
            if (thumbnailAtEnd) {
                thumbPos = buffer.position()
                buffer.put(thumbnail)
            }
            buffer.putInt(ifd1Values.getValue(0x0201), thumbPos)
            buffer.putInt(ifd0Next, ifd1Pos)
        }
        val tiff = buffer.array().copyOf(buffer.position())
        return JpegSegment.EXIF_ID + tiff
    }

    /** Writes an IFD at the current position; returns (next-IFD pointer position, tag -> value field position). */
    private fun writeIfd(buffer: ByteBuffer, fields: List<Field>): Pair<Int, Map<Int, Int>> {
        val sorted = fields.sortedBy { it.tag }
        val start = buffer.position()
        var dataPos = start + 2 + 12 * sorted.size + 4
        val values = HashMap<Int, Int>()
        val pending = ArrayList<Pair<Int, ByteArray>>()
        buffer.putShort(sorted.size.toShort())
        for (field in sorted) {
            buffer.putShort(field.tag.toShort())
            buffer.putShort(field.type.toShort())
            buffer.putInt(field.count)
            values[field.tag] = buffer.position()
            if (field.value.size <= 4) {
                buffer.put(field.value)
                repeat(4 - field.value.size) { buffer.put(0) }
            } else {
                buffer.putInt(dataPos)
                pending += dataPos to field.value
                dataPos += field.value.size + (field.value.size and 1)
            }
        }
        val next = buffer.position()
        buffer.putInt(0)
        for ((pos, value) in pending) {
            buffer.position(pos)
            buffer.put(value)
        }
        buffer.position(dataPos)
        return next to values
    }
}

object Fixtures {
    val MAKER_NOTE = ByteArray(300) { (it * 7 + 3).toByte() }

    const val MOTION_PHOTO_XMP = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:xmp="http://ns.adobe.com/xap/1.0/"
        xmlns:xmpNote="http://ns.adobe.com/xmp/note/"
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="968653"
      GCamera:MicroVideo="1"
      GCamera:MicroVideoOffset="123"
      GCamera:HdrPlusMakernote="SGVsbG8="
      xmpNote:HasExtendedXMP="0123456789ABCDEF0123456789ABCDEF"
      xmp:CreatorTool="Pixel Camera">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="27"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    val TRAILER = "\u0000\u0000\u0000\u0018ftypmp42 fake motion video".toByteArray(Charsets.ISO_8859_1)

    /**
     * A camera-like JPEG: EXIF (orientation, sizes, GPS, MakerNote, thumbnail), motion photo XMP,
     * extended XMP, MPF, a comment and a video trailer.
     */
    fun cameraJpeg(width: Int = 400, height: Int = 300, littleEndian: Boolean = true, orientation: Int = 1): ByteArray {
        val e = TestExif(littleEndian)
        val thumbnail = JpegFile.parse(TestImages.jpeg(160, 120, 80)).let { JpegFile.assemble(emptyList(), it.image) }
        val exif = e.build(
            ifd0 = listOf(
                e.ascii(0x010F, "Google"),
                e.ascii(0x0110, "Pixel 3"),
                e.short(0x0112, orientation),
                e.short(0x0100, width),
                e.short(0x0101, height),
            ),
            exif = listOf(
                e.ascii(0x9003, "2020:10:11 18:30:00"),
                e.ascii(0x9011, "-05:00"),
                e.short(0xA002, width),
                e.long(0xA003, height),
                e.undefined(0x927C, MAKER_NOTE),
            ),
            gps = listOf(
                e.ascii(0x0001, "N"),
                e.rationals(0x0002, 40 to 1, 6 to 1, 3000 to 100),
                e.ascii(0x0003, "W"),
                e.rationals(0x0004, 88 to 1, 13 to 1, 4500 to 100),
            ),
            thumbnail = thumbnail,
        )
        val base = JpegFile.parse(TestImages.jpeg(width, height))
        val metadata = listOf(
            JpegSegment(JpegSegment.APP1, exif),
            XmpEditor.toSegment(MOTION_PHOTO_XMP),
            JpegSegment.withPrefix(JpegSegment.APP1, JpegSegment.XMP_EXTENSION_ID, ByteArray(64) { 1 }),
            JpegSegment.withPrefix(JpegSegment.APP2, JpegSegment.MPF_ID, ByteArray(32) { 2 }),
            JpegSegment(JpegSegment.COM, "kept comment".toByteArray()),
        )
        return JpegFile.assemble(metadata, base.image) + TRAILER
    }
}
