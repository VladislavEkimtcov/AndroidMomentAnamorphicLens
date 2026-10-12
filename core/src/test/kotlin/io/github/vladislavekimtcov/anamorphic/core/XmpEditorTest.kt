package io.github.vladislavekimtcov.anamorphic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpEditorTest {

    private val marker = StretchMarker(4.0 / 3.0, Axis.WIDTH, 4032, 3024)

    @Test
    fun removesMotionPhotoAndKeepsTheRest() {
        val out = XmpEditor.rewrite(Fixtures.MOTION_PHOTO_XMP, marker)!!
        assertFalse(out.contains("MotionPhoto"))
        assertFalse(out.contains("MicroVideo"))
        assertFalse(out.contains("Container"))
        assertFalse(out.contains("HasExtendedXMP"))
        assertTrue(out.contains("HdrPlusMakernote"))
        assertTrue(out.contains("Pixel Camera"))
        assertTrue(out.contains("xpacket"))
        val read = XmpEditor.readMarker(out)!!
        assertEquals(Axis.WIDTH, read.axis)
        assertEquals(4032, read.originalWidth)
        assertEquals(3024, read.originalHeight)
        assertEquals(4.0 / 3.0, read.factor, 1e-5)
    }

    @Test
    fun createsAPacketWhenThereIsNone() {
        val out = XmpEditor.rewrite(null, marker)!!
        assertEquals(marker.originalWidth, XmpEditor.readMarker(out)!!.originalWidth)
        assertNull(XmpEditor.rewrite(null, null))
    }

    @Test
    fun removesTheMarkerOnUnstretch() {
        val stretched = XmpEditor.rewrite(Fixtures.MOTION_PHOTO_XMP, marker)!!
        val restored = XmpEditor.rewrite(stretched, null)!!
        assertNull(XmpEditor.readMarker(restored))
        assertFalse(restored.contains(XmpEditor.NAMESPACE))
        assertTrue(restored.contains("Pixel Camera"))
    }

    @Test
    fun replacesAnExistingMarker() {
        val first = XmpEditor.rewrite(null, marker)!!
        val second = XmpEditor.rewrite(first, marker.copy(axis = Axis.HEIGHT))!!
        assertEquals(Axis.HEIGHT, XmpEditor.readMarker(second)!!.axis)
    }

    @Test
    fun brokenXmpIsKeptOnlyWhenHarmless() {
        assertEquals("<not really xml", XmpEditor.rewrite("<not really xml", marker))
        assertNull(XmpEditor.rewrite("<broken GCamera:MotionPhoto=\"1\"", marker))
        assertNull(XmpEditor.readMarker("<not really xml"))
    }

    @Test
    fun ignoresDoctypes() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""
        assertNull(XmpEditor.readMarker(evil))
    }

    @Test
    fun segmentRoundTrip() {
        val segment = XmpEditor.toSegment("<x/>")
        assertTrue(segment.isXmp)
        assertNotNull(XmpEditor.fromSegment(segment))
        assertEquals("<x/>", XmpEditor.fromSegment(segment))
    }
}
