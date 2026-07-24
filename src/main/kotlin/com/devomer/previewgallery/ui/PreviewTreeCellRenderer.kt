package com.devomer.previewgallery.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Presentation only: node kind (icon, text emphasis), never structure, sorting or filtering, which stay in
 * [PreviewTreeModelBuilder] / [PreviewNode]. Module and package rows are visually secondary so the preview name
 * — what someone is actually scanning the tree for — reads as the prominent element on each row.
 *
 * Icons are verified to exist in this SDK (`javap` against the bundled `AllIcons$Nodes`), per the same
 * API-stability discipline the render pipeline uses for AS-internal classes (see `LiveRenderer`).
 */
class PreviewTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject as? PreviewNode ?: return
        // The detail panel that used to show the composable FQN is gone (Fix PG2-10); surface it as a tooltip
        // instead so the information is not lost. Explicitly cleared for non-leaf rows: this renderer instance
        // is reused across cells, so a stale tooltip would otherwise leak from a previously rendered leaf.
        toolTipText = (node as? PreviewNode.PreviewLeaf)?.row?.indexed?.composableFqn
        when (node) {
            is PreviewNode.ModuleNode -> {
                icon = AllIcons.Nodes.Module
                append(node.moduleName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }

            is PreviewNode.PackageNode -> {
                icon = AllIcons.Nodes.Package
                append(node.packageName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is PreviewNode.PreviewLeaf -> {
                val indexed = node.row.indexed
                val unsupported = indexed.unsupportedReason != null
                // A disabled-looking icon plus a grayed/italic name conveys "unsupported" on sight, without a
                // "unsupported" text badge competing with the name for attention.
                icon = if (unsupported) IconLoader.getDisabledIcon(AllIcons.Nodes.Function) else AllIcons.Nodes.Function
                val nameAttributes = if (unsupported) SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append(indexed.displayName, nameAttributes)
                if (indexed.displayName != indexed.functionName) {
                    append("  ${indexed.functionName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                val badges = buildList {
                    if (indexed.isPrivate) add("private")
                    if (indexed.hasPreviewParameter) add("@PreviewParameter")
                }
                if (badges.isNotEmpty()) {
                    append("  ${badges.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }
}
