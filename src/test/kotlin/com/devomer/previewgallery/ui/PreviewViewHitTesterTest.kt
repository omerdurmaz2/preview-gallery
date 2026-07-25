package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

class PreviewViewHitTesterTest {

    private fun node(x: Int, y: Int, w: Int, h: Int, children: List<PreviewViewNode> = emptyList()) =
        PreviewViewNode(Rectangle(x, y, w, h), null, children)

    private fun srcNode(x: Int, y: Int, w: Int, h: Int, file: String, line: Int, children: List<PreviewViewNode> = emptyList()) =
        PreviewViewNode(Rectangle(x, y, w, h), PreviewSourceLocation(file, line, null), children)

    @Test fun `draw rect fits and centers`() {
        // 100x200 image into a 200x200 panel -> scale 1.0 by height, width 100, centered x=50
        assertEquals(Rectangle(50, 0, 100, 200), PreviewViewHitTester.imageDrawRect(Dimension(200, 200), Dimension(100, 200)))
    }

    @Test fun `panel point outside the image maps to null`() {
        val draw = Rectangle(50, 0, 100, 200)
        assertNull(PreviewViewHitTester.toRenderPoint(Point(10, 10), draw, Dimension(100, 200)))
    }

    @Test fun `panel point maps into render space`() {
        val draw = Rectangle(0, 0, 100, 200)   // 1:1
        assertEquals(Point(50, 100), PreviewViewHitTester.toRenderPoint(Point(50, 100), draw, Dimension(100, 200)))
    }

    @Test fun `innermost node wins over its parent`() {
        val child = node(10, 10, 20, 20)
        val parent = node(0, 0, 100, 100, listOf(child))
        assertEquals(child, PreviewViewHitTester.innermostAt(listOf(parent), Point(15, 15)))
        assertEquals(parent, PreviewViewHitTester.innermostAt(listOf(parent), Point(5, 5)))
    }

    @Test fun `no node contains the point`() {
        assertNull(PreviewViewHitTester.innermostAt(listOf(node(0, 0, 10, 10)), Point(50, 50)))
    }

    @Test fun `source chain is innermost first and skips sourceless nodes`() {
        // outer(has source) -> middle(no source) -> inner(has source); the chain must be inner then outer.
        val inner = srcNode(10, 10, 10, 10, "Inner.kt", 5)
        val middle = node(5, 5, 30, 30, listOf(inner))
        val outer = srcNode(0, 0, 100, 100, "Outer.kt", 1, listOf(middle))
        assertEquals(
            listOf(PreviewSourceLocation("Inner.kt", 5, null), PreviewSourceLocation("Outer.kt", 1, null)),
            PreviewViewHitTester.sourceChainAt(listOf(outer), Point(12, 12)),
        )
    }

    @Test fun `source chain drops nodes that do not contain the point`() {
        // The point is inside outer but outside inner, so only outer's source is collected.
        val inner = srcNode(80, 80, 10, 10, "Inner.kt", 5)
        val outer = srcNode(0, 0, 100, 100, "Outer.kt", 1, listOf(inner))
        assertEquals(
            listOf(PreviewSourceLocation("Outer.kt", 1, null)),
            PreviewViewHitTester.sourceChainAt(listOf(outer), Point(10, 10)),
        )
    }

    @Test fun `source chain is empty when the point misses every node`() {
        assertEquals(
            emptyList<PreviewSourceLocation>(),
            PreviewViewHitTester.sourceChainAt(listOf(srcNode(0, 0, 5, 5, "A.kt", 1)), Point(50, 50)),
        )
    }
}
