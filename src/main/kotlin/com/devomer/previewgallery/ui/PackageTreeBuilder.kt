package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/**
 * Turns one module's rows into a nested package tree.
 *
 * Chains that neither fork nor hold previews of their own are compacted into a single row
 * (`com.trendyol.buy` rather than `com` > `trendyol` > `buy`), matching the IDE's own "compact middle packages"
 * behaviour: branching should start where the packages actually diverge. A branch that holds previews is never
 * compacted away — its leaves need a row to hang from. A branch whose children are only distinguishable by
 * case (`Buy` next to `buy`) is compacted through the same way a single child would be, so the tree never
 * shows two sibling rows that read the same.
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
            val leaf = PreviewNode.PreviewLeaf(row)
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
            .flatMap { freeze(it, "") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.segment })

    /**
     * [prefix] carries the segments already compacted into this row, empty at a fresh branching point.
     *
     * Returns a list rather than a single row because a node whose children collide case-insensitively
     * (`Buy` and `buy`) is not a safe place to stop: showing both as sibling rows under the same parent would
     * put two identical-looking labels side by side. Such a node is pushed through like a single child, but
     * since there is more than one of them, each one keeps compacting on its own and the results come back up
     * as separate rows at the level above instead of one.
     */
    private fun freeze(branch: MutableBranch, prefix: String): List<PreviewNode.PackageBranch> {
        val label = if (prefix.isEmpty()) branch.segment else "$prefix.${branch.segment}"
        // A single distinct label covers both the plain "one child" case and several children that only look
        // like a fork once case is ignored; either way there is nothing here worth stopping compaction for.
        val distinctChildLabels = sortedSetOf(String.CASE_INSENSITIVE_ORDER, *branch.children.keys.toTypedArray()).size
        val allChildrenLookTheSame = branch.children.isNotEmpty() && distinctChildLabels == 1
        if (branch.previews.isEmpty() && allChildrenLookTheSame) {
            return branch.children.values.flatMap { freeze(it, label) }
        }

        val branches = freezeAll(branch.children)
        val previews = sortLeaves(branch.previews)
        return listOf(
            PreviewNode.PackageBranch(
                segment = label,
                branches = branches,
                previews = previews,
                count = previews.size + branches.sumOf { it.count },
            ),
        )
    }

    private fun sortLeaves(leaves: List<PreviewNode.PreviewLeaf>): List<PreviewNode.PreviewLeaf> =
        leaves.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.row.indexed.displayName })

    private class MutableBranch(val segment: String) {
        val children = LinkedHashMap<String, MutableBranch>()
        val previews = mutableListOf<PreviewNode.PreviewLeaf>()
    }
}
