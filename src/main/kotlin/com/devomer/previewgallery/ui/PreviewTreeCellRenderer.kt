package com.devomer.previewgallery.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

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
                append(node.moduleName)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is PreviewNode.PackageNode -> append(node.packageName)

            is PreviewNode.PreviewLeaf -> {
                val indexed = node.row.indexed
                append(indexed.displayName)
                if (indexed.displayName != indexed.functionName) {
                    append("  ${indexed.functionName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                val badges = buildList {
                    if (indexed.isPrivate) add("private")
                    if (indexed.hasPreviewParameter) add("@PreviewParameter")
                    if (indexed.unsupportedReason != null) add("unsupported")
                }
                if (badges.isNotEmpty()) {
                    append("  ${badges.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }
}
