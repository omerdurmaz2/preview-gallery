package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewRow
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
                val store = project?.let { SnapshotVerifyStore.getInstance(it) }
                val failureBadge = store?.let { s ->
                    previewFailureBadge(node.row.snapshots, s::measurementFor) { measurement ->
                        s.isStale(measurement) { tree.repaint() }
                    }
                }
                if (failureBadge != null) {
                    append("  ${differsLabel(failureBadge.stale)}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }

            is PreviewNode.SnapshotLeaf -> {
                icon = AllIcons.FileTypes.Image
                append(node.row.indexed.functionName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                // Matched on methodName alone, not variant: a row is one function, and a function whose `phone`
                // variant differs is worth flagging regardless of what `small` did.
                val store = project?.let { SnapshotVerifyStore.getInstance(it) }
                val verify = store?.measurementFor(node.row.moduleName)
                if (store != null && verify != null && verify.results.any {
                        it.methodName == node.row.indexed.functionName && it.status == SnapshotVerifyResults.Status.FAILED
                    }
                ) {
                    val label = if (store.isStale(verify) { tree.repaint() }) {
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

    /** [PreviewNode.SnapshotLeaf]'s own badge wording (PG20-5), reused verbatim for [PreviewNode.PreviewLeaf]'s
     *  rolled-up badge (H3) so the two rows never drift into saying the same fact two different ways. */
    private fun differsLabel(stale: Boolean): String =
        if (stale) {
            "${PreviewGalleryBundle.message("verify.differs")} · ${PreviewGalleryBundle.message("verify.stale")}"
        } else {
            PreviewGalleryBundle.message("verify.differs")
        }

    /** [previewFailureBadge]'s result: a preview row's badge is warranted, and whether it should read stale. */
    internal data class FailureBadge(val stale: Boolean)

    companion object {
        /**
         * The orphan branch's row text. Owned here because this is what actually draws it;
         * [PreviewGalleryPanel]'s label bookkeeping reads it from here rather than repeating the literal, since a
         * label path is only useful if it names the row the user sees. Not a bundle key: this renderer is
         * unit-tested as plain JUnit, with no `Application` to resolve a `DynamicBundle` against.
         */
        const val ORPHAN_BRANCH_LABEL = "Snapshots without a preview"

        /**
         * H3: whether [snapshots] — a preview row's own covering snapshots ([PreviewRow.snapshots]) — include a
         * FAILED result anywhere, and whether that verdict should read stale. A user hit this: a snapshot whose
         * body calls a design-system composable directly gets filed under *that* composable's preview by the
         * coverage matcher, in another branch of the tree, not under the preview the user was actually reading —
         * so a failure the plugin had already measured was invisible unless the collapsed branch happened to be
         * opened. Rolling the badge up to the preview row fixes that without changing where the fact itself lives.
         *
         * Each [snapshots] entry is looked up against its OWN [PreviewRow.moduleName] via [measurementFor], never
         * the covering preview's — the exact case above, a snapshot in a different module from the preview it
         * covers. [isStale] is the non-blocking [SnapshotVerifyStore.isStale] overload; the caller must pass that
         * one, never the blocking source-tree walk, since this runs on Swing's per-row paint callback.
         *
         * Pure and unit-tested without a [SnapshotVerifyStore] or [com.intellij.openapi.project.Project]
         * ([PreviewTreeCellRendererTest]) — [measurementFor] and [isStale] are its only IDE-touching facts, kept
         * as parameters exactly as [RenderModelResolver][com.devomer.previewgallery.render.RenderModelResolver]
         * keeps its own AS-touching facts as parameters to its pure decisions.
         *
         * `null` means no badge: no covering snapshot has a measurement, or every measured one passed. A non-null
         * result's [FailureBadge.stale] is true if ANY failing snapshot's own measurement is stale — the same
         * direction [SnapshotVerifyStore.markAllStale] already chose (a stale badge understates confidence, a
         * fresh one overstates it), so one stale contributor is enough to call the whole badge stale.
         */
        internal fun previewFailureBadge(
            snapshots: List<PreviewRow>,
            measurementFor: (String) -> SnapshotVerifyStore.Measurement?,
            isStale: (SnapshotVerifyStore.Measurement) -> Boolean,
        ): FailureBadge? {
            var failing = false
            var stale = false
            for (snapshot in snapshots) {
                val measurement = measurementFor(snapshot.moduleName) ?: continue
                val snapshotFailed = measurement.results.any {
                    it.methodName == snapshot.indexed.functionName && it.status == SnapshotVerifyResults.Status.FAILED
                }
                if (snapshotFailed) {
                    failing = true
                    if (isStale(measurement)) stale = true
                }
            }
            return if (failing) FailureBadge(stale) else null
        }
    }
}
