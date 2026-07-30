package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/** A Swing-free tree shape, so grouping can be tested without a `JTree`. */
sealed interface PreviewNode {

    /**
     * One row of the module tree, nested the same way [PackageBranch] nests packages one level down (see
     * [ModuleTreeBuilder]). [segment] is the label as shown — after compaction it can carry several joined path
     * segments (`features.buy`), which is why it is not called `name`. [count] is every preview in the whole
     * subtree, including nested [modules], so a collapsed row still says how much it holds — [orphans] is
     * deliberately excluded, since those are snapshots, not previews. [branches] and [previews] are this module's
     * own rows — the ones whose `moduleName` resolves to exactly this node, not to one of its child [modules] —
     * built by [PackageTreeBuilder].
     */
    data class ModuleNode(
        val segment: String,
        val count: Int,
        val modules: List<ModuleNode>,
        val branches: List<PackageBranch>,
        val previews: List<PreviewLeaf>,
        /** Snapshots in this module that match no preview. Null when there are none, so no row is drawn. */
        val orphans: OrphanSnapshotBranch? = null,
    ) : PreviewNode

    /**
     * One row of the package tree. [segment] is the label as shown — after compaction it can carry several
     * joined package segments (`com.trendyol`), which is why it is not called `name`. [count] is the number of
     * previews in the whole subtree, so a collapsed row still says how much it holds.
     */
    data class PackageBranch(
        val segment: String,
        val branches: List<PackageBranch>,
        val previews: List<PreviewLeaf>,
        val count: Int,
    ) : PreviewNode

    data class PreviewLeaf(val row: PreviewRow, val snapshots: List<SnapshotLeaf> = emptyList()) : PreviewNode

    /** A `@PreviewTest` function. Selecting it shows reference images, never a live render. */
    data class SnapshotLeaf(val row: PreviewRow) : PreviewNode

    /** A module's snapshots that matched no preview by target, shown as their own branch below its previews. */
    data class OrphanSnapshotBranch(val snapshots: List<SnapshotLeaf>, val count: Int) : PreviewNode
}
