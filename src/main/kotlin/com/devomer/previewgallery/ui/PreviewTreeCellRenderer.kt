package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.service.SnapshotVerifyResults
import com.devomer.previewgallery.service.SnapshotVerifyStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
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
 * Icons are verified to exist in this SDK (`javap` against the bundled `AllIcons$Nodes` / `AllIcons$FileTypes`),
 * per the same API-stability discipline the render pipeline uses for AS-internal classes (see `LiveRenderer`).
 *
 * [project] reaches [SnapshotVerifyStore] for the snapshot-row failure badge (PG20-5). Defaulted to null, not
 * required, so [PreviewTreeCellRendererTest] keeps constructing this with no `Application`/`Project` context at
 * all (its own class doc) — a null project simply never badges a row, which is also correct for that test's plain
 * [javax.swing.JTree] fixture.
 */
class PreviewTreeCellRenderer(private val project: Project? = null) : ColoredTreeCellRenderer() {

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
        // instead so the information is not lost. A snapshot row gets the same treatment — its own FQN, which is
        // the fact that identifies it, since its row text is only the function name. Explicitly null for every
        // other row: this renderer instance is reused across cells, so a stale tooltip would otherwise leak from
        // a previously rendered leaf.
        toolTipText = when (node) {
            is PreviewNode.PreviewLeaf -> node.row.indexed.composableFqn
            is PreviewNode.SnapshotLeaf -> node.row.indexed.composableFqn
            else -> null
        }
        when (node) {
            is PreviewNode.ModuleNode -> {
                icon = AllIcons.Nodes.Module
                append(node.segment, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }

            is PreviewNode.PackageBranch -> {
                icon = AllIcons.Nodes.Package
                append(node.segment, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
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
                append("  ${coverageText(node.row.coverage)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }

            is PreviewNode.SnapshotLeaf -> {
                icon = AllIcons.FileTypes.Image
                append(node.row.indexed.functionName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                // Matched on methodName alone, not variant: a row is one function, and a function whose `phone`
                // variant differs is worth flagging regardless of what `small` did.
                val store = project?.let { SnapshotVerifyStore.getInstance(it) }
                val verify = store?.forModule(node.row.moduleName)
                val failed = verify?.results.orEmpty()
                    .any { it.methodName == node.row.indexed.functionName && it.status == SnapshotVerifyResults.Status.FAILED }
                if (failed && store != null && verify != null) {
                    val label = if (store.isStale(verify)) {
                        "${PreviewGalleryBundle.message("verify.differs")} · ${PreviewGalleryBundle.message("verify.stale")}"
                    } else {
                        PreviewGalleryBundle.message("verify.differs")
                    }
                    append("  $label", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }

            is PreviewNode.OrphanSnapshotBranch -> {
                icon = AllIcons.Nodes.Folder
                append(ORPHAN_BRANCH_LABEL, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    /** Text, not an icon alone: a first-time user of the plugin cannot be expected to decode a glyph. */
    private fun coverageText(coverage: SnapshotCoverage): String = when (coverage) {
        is SnapshotCoverage.Covered ->
            if (coverage.count == 1) "· 1 snapshot" else "· ${coverage.count} snapshots"
        SnapshotCoverage.Uncovered -> "· no snapshot"
    }

    companion object {
        /**
         * The orphan branch's row text. Owned here because this is what actually draws it;
         * [PreviewGalleryPanel]'s label bookkeeping reads it from here rather than repeating the literal, since a
         * label path is only useful if it names the row the user sees. Not a bundle key: this renderer is
         * unit-tested as plain JUnit, with no `Application` to resolve a `DynamicBundle` against.
         */
        const val ORPHAN_BRANCH_LABEL = "Snapshots without a preview"
    }
}
