package com.devomer.previewgallery.ui

import java.awt.Rectangle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object MeasurementGeometry {

    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    data class Measurement(val line: Line, val lengthPx: Int, val guide: Line? = null)

    fun measure(selected: Rectangle, hovered: Rectangle): List<Measurement> {
        val sx = Span(selected.x, selected.x + selected.width)
        val sy = Span(selected.y, selected.y + selected.height)
        val hx = Span(hovered.x, hovered.x + hovered.width)
        val hy = Span(hovered.y, hovered.y + hovered.height)
        val disjointX = sx.disjointFrom(hx)
        val disjointY = sy.disjointFrom(hy)
        val measurements = if (!disjointX && !disjointY) {
            edgeOffsets(sx, hx, sy.overlapCenter(hy), Axis.X) + edgeOffsets(sy, hy, sx.overlapCenter(hx), Axis.Y)
        } else {
            listOfNotNull(
                if (disjointX) gap(sx, hx, sy, hy, Axis.X) else null,
                if (disjointY) gap(sy, hy, sx, hx, Axis.Y) else null,
            )
        }
        return measurements.filter { it.lengthPx > 0 }
    }

    fun formatDp(lengthPx: Int, dpi: Int): String =
        String.format(Locale.ROOT, "%.1f", lengthPx * ZoomMath.contentScale(dpi)).removeSuffix(".0") + "dp"

    private enum class Axis { X, Y }

    private class Span(val start: Int, val end: Int) {
        val center: Double get() = (start + end) / 2.0

        fun disjointFrom(other: Span): Boolean = end <= other.start || other.end <= start

        fun overlapCenter(other: Span): Double = (max(start, other.start) + min(end, other.end)) / 2.0
    }

    private fun edgeOffsets(s: Span, h: Span, across: Double, axis: Axis): List<Measurement> = listOf(
        segment(min(s.start, h.start), max(s.start, h.start), across, axis),
        segment(min(s.end, h.end), max(s.end, h.end), across, axis),
    )

    private fun gap(s: Span, h: Span, sAcross: Span, hAcross: Span, axis: Axis): Measurement {
        val hAfter = s.end <= h.start
        val from = if (hAfter) s.end else h.end
        val to = if (hAfter) h.start else s.start
        if (!sAcross.disjointFrom(hAcross)) return segment(from, to, sAcross.overlapCenter(hAcross), axis)
        val across = sAcross.center
        val hEdge = (if (hAfter) to else from).toDouble()
        val hFacing = (if (hAcross.start >= sAcross.end) hAcross.start else hAcross.end).toDouble()
        return segment(from, to, across, axis).copy(guide = line(hEdge, across, hEdge, hFacing, axis))
    }

    private fun segment(from: Int, to: Int, across: Double, axis: Axis): Measurement =
        Measurement(line(from.toDouble(), across, to.toDouble(), across, axis), to - from)

    private fun line(along1: Double, across1: Double, along2: Double, across2: Double, axis: Axis): Line = when (axis) {
        Axis.X -> Line(along1, across1, along2, across2)
        Axis.Y -> Line(across1, along1, across2, along2)
    }
}
