package com.devomer.previewgallery.ui

import com.devomer.previewgallery.ui.MeasurementGeometry.Line
import com.devomer.previewgallery.ui.MeasurementGeometry.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Rectangle

class MeasurementGeometryTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rectangle(left, top, right - left, bottom - top)

    private val paddings = listOf(
        Measurement(Line(0.0, 25.0, 20.0, 25.0), 20),
        Measurement(Line(80.0, 25.0, 100.0, 25.0), 20),
        Measurement(Line(50.0, 0.0, 50.0, 10.0), 10),
        Measurement(Line(50.0, 40.0, 50.0, 50.0), 10),
    )

    @Test
    fun `side by side gives one horizontal gap at the middle of the vertical overlap`() {
        assertEquals(
            listOf(Measurement(Line(100.0, 30.0, 120.0, 30.0), 20)),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(120, 10, 160, 60)),
        )
    }

    @Test
    fun `stacked gives one vertical gap at the middle of the horizontal overlap`() {
        assertEquals(
            listOf(Measurement(Line(50.0, 50.0, 50.0, 70.0), 20)),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(10, 70, 90, 90)),
        )
    }

    @Test
    fun `diagonal gives both gaps from the selection center, each with a guide to the hovered node`() {
        assertEquals(
            listOf(
                Measurement(Line(100.0, 25.0, 150.0, 25.0), 50, Line(150.0, 25.0, 150.0, 80.0)),
                Measurement(Line(50.0, 50.0, 50.0, 80.0), 30, Line(50.0, 80.0, 150.0, 80.0)),
            ),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(150, 80, 200, 120)),
        )
    }

    @Test
    fun `a hovered node up and to the left gets its guides to its own facing edges`() {
        assertEquals(
            listOf(
                Measurement(Line(50.0, 125.0, 100.0, 125.0), 50, Line(50.0, 125.0, 50.0, 50.0)),
                Measurement(Line(125.0, 50.0, 125.0, 100.0), 50, Line(125.0, 50.0, 50.0, 50.0)),
            ),
            MeasurementGeometry.measure(rect(100, 100, 150, 150), rect(0, 0, 50, 50)),
        )
    }

    @Test
    fun `a selection inside the hovered node gives its four paddings`() {
        assertEquals(paddings, MeasurementGeometry.measure(rect(20, 10, 80, 40), rect(0, 0, 100, 50)))
    }

    @Test
    fun `a hovered node inside the selection gives its four paddings`() {
        assertEquals(paddings, MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(20, 10, 80, 40)))
    }

    @Test
    fun `partially overlapping nodes give the offsets between their matching edges`() {
        assertEquals(
            listOf(
                Measurement(Line(0.0, 37.5, 50.0, 37.5), 50),
                Measurement(Line(100.0, 37.5, 150.0, 37.5), 50),
                Measurement(Line(75.0, 0.0, 75.0, 25.0), 25),
                Measurement(Line(75.0, 50.0, 75.0, 75.0), 25),
            ),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(50, 25, 150, 75)),
        )
    }

    @Test
    fun `touching nodes measure nothing`() {
        assertEquals(emptyList<Measurement>(), MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(100, 0, 150, 50)))
    }

    @Test
    fun `nodes with the same bounds measure nothing`() {
        assertEquals(emptyList<Measurement>(), MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(0, 0, 100, 50)))
    }

    @Test
    fun `a length a whole dp value rounds to is labelled with that whole value`() {
        assertEquals("6dp", MeasurementGeometry.formatDp(17, 440))
        assertEquals("16dp", MeasurementGeometry.formatDp(44, 440))
        assertEquals("16dp", MeasurementGeometry.formatDp(16, 160))
    }

    @Test
    fun `without a whole value a half dp value that rounds to the length is used`() {
        assertEquals("1.5dp", MeasurementGeometry.formatDp(4, 440))
        assertEquals("0.5dp", MeasurementGeometry.formatDp(1, 440))
        assertEquals("16.5dp", MeasurementGeometry.formatDp(45, 440))
    }

    @Test
    fun `without a whole or half value the label is the plain conversion`() {
        assertEquals("5.8dp", MeasurementGeometry.formatDp(16, 440))
    }
}
