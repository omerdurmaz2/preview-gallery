package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Presentation-only assertions against [SimpleColoredComponent][com.intellij.ui.SimpleColoredComponent]'s
 * fragment/icon state, via the plain [javax.swing.JTree] the renderer's own `customizeCellRenderer` never
 * touches (no IDE Application/Project context required).
 */
class PreviewTreeCellRendererTest {

    private val tree = JTree()

    private fun render(node: PreviewNode): PreviewTreeCellRenderer {
        val renderer = PreviewTreeCellRenderer()
        val leaf = node is PreviewNode.PreviewLeaf
        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(node), false, false, leaf, 0, false)
        return renderer
    }

    private fun fragments(renderer: PreviewTreeCellRenderer): List<Pair<String, SimpleTextAttributes>> {
        val result = mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iterator = renderer.iterator()
        while (iterator.hasNext()) {
            val text = iterator.next()
            result += text to iterator.textAttributes
        }
        return result
    }

    @Test
    fun `a module row uses the module icon, grey name, and a small grey count`() {
        val renderer = render(PreviewNode.ModuleNode("app", 3, emptyList()))

        assertEquals(AllIcons.Nodes.Module, renderer.icon)
        assertEquals(
            listOf(
                "app" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
                "  (3)" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
        assertNull(renderer.toolTipText)
    }

    @Test
    fun `a package row uses the package icon and grey text`() {
        val renderer = render(PreviewNode.PackageNode("com.example", emptyList()))

        assertEquals(AllIcons.Nodes.Package, renderer.icon)
        assertEquals(listOf("com.example" to SimpleTextAttributes.GRAYED_ATTRIBUTES), fragments(renderer))
        assertNull(renderer.toolTipText)
    }

    @Test
    fun `a supported preview leaf is prominent with the function icon and an FQN tooltip`() {
        val row = testRow(displayName = "BarPreview", functionName = "BarPreview")

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertEquals(AllIcons.Nodes.Function, renderer.icon)
        assertEquals(listOf("BarPreview" to SimpleTextAttributes.REGULAR_ATTRIBUTES), fragments(renderer))
        assertEquals(row.indexed.composableFqn, renderer.toolTipText)
    }

    @Test
    fun `a freeform preview name keeps the function name as a grey suffix`() {
        val row = testRow(displayName = "Dark tab", functionName = "TabsPreview")

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertEquals(
            listOf(
                "Dark tab" to SimpleTextAttributes.REGULAR_ATTRIBUTES,
                "  TabsPreview" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
            ),
            fragments(renderer),
        )
    }

    @Test
    fun `an unsupported preview leaf is greyed out with a disabled icon and no text badge`() {
        val row = testRow().let { it.copy(indexed = it.indexed.copy(unsupportedReason = "declared inside a class")) }

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertNotSame(AllIcons.Nodes.Function, renderer.icon)
        assertEquals(listOf("BarPreview" to SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES), fragments(renderer))
    }

    @Test
    fun `private and PreviewParameter badges are a short grey suffix`() {
        val row = testRow().let { it.copy(indexed = it.indexed.copy(isPrivate = true, hasPreviewParameter = true)) }

        val renderer = render(PreviewNode.PreviewLeaf(row))

        val badge = fragments(renderer).last()
        assertEquals("  private · @PreviewParameter", badge.first)
        assertEquals(SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, badge.second)
    }
}
