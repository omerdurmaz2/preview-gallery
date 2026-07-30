package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/**
 * Turns one module's rows into a nested package tree.
 *
 * Chains that neither fork nor hold previews of their own are compacted into a single row
 * (`com.trendyol.buy` rather than `com` > `trendyol` > `buy`), matching the IDE's own "compact middle packages"
 * behaviour: branching should start where the packages actually diverge. A branch that holds previews is never
 * compacted away — its leaves need a row to hang from.
 *
 * Segments are collected in exact-match maps and only the *output* is sorted case-insensitively: a map ordered
 * by `CASE_INSENSITIVE_ORDER` would treat `Buy` and `buy` as one key and silently drop a subtree.
 */
object PackageTreeBuilder {

    data class PackageTree(
        val branches: List<PreviewNode.PackageBranch>,
        /** Leaves with no package at all; the caller hangs them off its own row. */
        val previews: List<PreviewNode.PreviewLeaf>,
    )

    fun <T : PreviewRow> build(rows: List<T>): PackageTree {
        val roots = LinkedHashMap<String, MutableBranch>()
        val rootPreviews = mutableListOf<PreviewNode.PreviewLeaf>()

        for (row in rows) {
            val leaf = PreviewNode.PreviewLeaf(row, row.snapshots.map { PreviewNode.SnapshotLeaf(it) })
            val segments = row.indexed.packageName.split('.').filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                rootPreviews += leaf
                continue
            }
            var level = roots
            var branch: MutableBranch? = null
            for (segment in segments) {
                val child = level.getOrPut(segment) { MutableBranch(segment) }
                branch = child
                level = child.children
            }
            branch?.previews?.add(leaf)
        }

        return PackageTree(freezeAll(roots), sortLeaves(rootPreviews))
    }

    private fun freezeAll(level: Map<String, MutableBranch>): List<PreviewNode.PackageBranch> =
        level.values
            .map { freeze(it, "") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.segment })

    /** [prefix] carries the segments already compacted into this row, empty at a fresh branching point. */
    private fun freeze(branch: MutableBranch, prefix: String): PreviewNode.PackageBranch {
        val label = if (prefix.isEmpty()) branch.segment else "$prefix.${branch.segment}"
        if (branch.previews.isEmpty() && branch.children.size == 1) {
            return freeze(branch.children.values.single(), label)
        }

        val branches = freezeAll(branch.children)
        val previews = sortLeaves(branch.previews)
        return PreviewNode.PackageBranch(
            segment = label,
            branches = branches,
            previews = previews,
            count = previews.size + branches.sumOf { it.count },
        )
    }

    private fun sortLeaves(leaves: List<PreviewNode.PreviewLeaf>): List<PreviewNode.PreviewLeaf> =
        leaves.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.row.indexed.displayName })

    private class MutableBranch(val segment: String) {
        val children = LinkedHashMap<String, MutableBranch>()
        val previews = mutableListOf<PreviewNode.PreviewLeaf>()
    }
}
