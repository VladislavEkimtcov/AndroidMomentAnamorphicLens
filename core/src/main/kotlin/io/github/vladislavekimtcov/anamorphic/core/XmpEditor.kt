package io.github.vladislavekimtcov.anamorphic.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** What this app records in XMP when it stretches a photo, so Unstretch can undo it exactly. */
data class StretchMarker(
    val factor: Double,
    val axis: Axis,
    val originalWidth: Int,
    val originalHeight: Int,
)

/**
 * Edits the main XMP packet of a JPEG.
 *
 * Resizing invalidates XMP that describes data embedded in the original file (motion photo
 * videos, depth maps, gain maps, panorama crops), so those properties are removed. Everything
 * else is kept. The app also stores a small [StretchMarker] here.
 */
object XmpEditor {
    const val NAMESPACE = "https://github.com/VladislavEkimtcov/AndroidMomentAnamorphicLens/ns/1.0/"
    private const val PREFIX = "anamorphic"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"

    private const val GCAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val XMP_NOTE = "http://ns.adobe.com/xmp/note/"

    /** Namespaces whose properties all describe embedded or size-dependent data. */
    private val DROPPED_NAMESPACES = setOf(
        "http://ns.google.com/photos/1.0/container/",
        "http://ns.google.com/photos/1.0/container/item/",
        "http://ns.google.com/photos/1.0/depthmap/",
        "http://ns.google.com/photos/1.0/image/",
        "http://ns.google.com/photos/1.0/panorama/",
        "http://ns.adobe.com/hdr-gain-map/1.0/",
    )

    private fun isDropped(namespace: String?, localName: String?): Boolean {
        if (namespace == null || localName == null) return false
        return when (namespace) {
            in DROPPED_NAMESPACES -> true
            GCAMERA -> localName.startsWith("MicroVideo") || localName.startsWith("MotionPhoto") ||
                localName == "SpecialTypeID"
            XMP_NOTE -> localName == "HasExtendedXMP"
            NAMESPACE -> true // our own marker is always rewritten
            else -> false
        }
    }

    private val PROPERTY_NAMES = listOf("Factor", "Axis", "OriginalWidth", "OriginalHeight")

    fun readMarker(xmp: String): StretchMarker? {
        val doc = parse(xmp) ?: return null
        for (description in descriptions(doc.documentElement)) {
            val values = PROPERTY_NAMES.associateWith { name ->
                description.getAttributeNodeNS(NAMESPACE, name)?.value
                    ?: childElements(description).firstOrNull { it.namespaceURI == NAMESPACE && it.localName == name }
                        ?.textContent?.trim()
            }
            if (values.values.any { it == null }) continue
            val factor = values["Factor"]?.toDoubleOrNull() ?: continue
            val axis = Axis.fromXmpName(values["Axis"]) ?: continue
            val width = values["OriginalWidth"]?.toIntOrNull() ?: continue
            val height = values["OriginalHeight"]?.toIntOrNull() ?: continue
            if (factor <= 1.0 || width <= 0 || height <= 0) continue
            return StretchMarker(factor, axis, width, height)
        }
        return null
    }

    /**
     * Returns the cleaned packet with [marker] written into it (or with any marker removed when
     * it is null). Returns null when the packet should be dropped altogether.
     */
    fun rewrite(xmp: String?, marker: StretchMarker?): String? {
        if (xmp == null) return marker?.let { rewrite(EMPTY_PACKET, it) }
        val doc = parse(xmp)
        if (doc == null) {
            // Can't edit it safely: keep it unless it refers to data we are about to drop.
            val stale = DROPPED_NAMESPACES.any { xmp.contains(it) } || xmp.contains("MicroVideo") ||
                xmp.contains("MotionPhoto") || xmp.contains("HasExtendedXMP")
            return if (stale) null else xmp
        }
        val root = doc.documentElement
        val all = descriptions(root)
        for (description in all) {
            val attributes = description.attributes
            for (i in attributes.length - 1 downTo 0) {
                val attribute = attributes.item(i)
                val dropDeclaration = attribute.namespaceURI == XMLNS &&
                    (attribute.nodeValue in DROPPED_NAMESPACES || attribute.nodeValue == NAMESPACE)
                if (dropDeclaration || isDropped(attribute.namespaceURI, attribute.localName)) {
                    description.removeAttributeNode(attribute as org.w3c.dom.Attr)
                }
            }
            for (child in childElements(description)) {
                if (isDropped(child.namespaceURI, child.localName)) description.removeChild(child)
            }
        }
        if (marker != null) {
            var target = all.firstOrNull()
            if (target == null) {
                val rdf = findRdf(root) ?: return serialize(doc)
                target = doc.createElementNS(RDF, "rdf:Description").also {
                    it.setAttributeNS(RDF, "rdf:about", "")
                    rdf.appendChild(it)
                }
            }
            target!!.setAttributeNS(XMLNS, "xmlns:$PREFIX", NAMESPACE)
            target.setAttributeNS(NAMESPACE, "$PREFIX:Factor", String.format(Locale.ROOT, "%.6f", marker.factor))
            target.setAttributeNS(NAMESPACE, "$PREFIX:Axis", marker.axis.xmpName)
            target.setAttributeNS(NAMESPACE, "$PREFIX:OriginalWidth", marker.originalWidth.toString())
            target.setAttributeNS(NAMESPACE, "$PREFIX:OriginalHeight", marker.originalHeight.toString())
        }
        return serialize(doc)
    }

    private val EMPTY_PACKET = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="$RDF">
          <rdf:Description rdf:about=""/>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    private fun parse(xmp: String): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw java.io.IOException("External entities are not allowed") }
        builder.parse(ByteArrayInputStream(xmp.trim().toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }

    private fun serialize(doc: org.w3c.dom.Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun findRdf(element: Element): Element? {
        if (element.namespaceURI == RDF && element.localName == "RDF") return element
        for (child in childElements(element)) findRdf(child)?.let { return it }
        return null
    }

    private fun descriptions(root: Element): List<Element> {
        val rdf = findRdf(root) ?: return emptyList()
        return childElements(rdf).filter { it.namespaceURI == RDF && it.localName == "Description" }
    }

    private fun childElements(element: Element): List<Element> {
        val result = ArrayList<Element>()
        var node: Node? = element.firstChild
        while (node != null) {
            if (node is Element) result += node
            node = node.nextSibling
        }
        return result
    }

    /** Extracts the packet text from an APP1 XMP payload. */
    fun fromSegment(segment: JpegSegment): String =
        String(segment.payload, JpegSegment.XMP_ID.size, segment.payload.size - JpegSegment.XMP_ID.size, Charsets.UTF_8)
            .trimEnd('\u0000')

    fun toSegment(xmp: String): JpegSegment =
        JpegSegment.withPrefix(JpegSegment.APP1, JpegSegment.XMP_ID, xmp.toByteArray(Charsets.UTF_8))
}
