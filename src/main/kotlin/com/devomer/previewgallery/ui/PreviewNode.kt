package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/** A Swing-free tree shape, so grouping can be tested without a `JTree`. */
sealed interface PreviewNode {

    data class ModuleNode(
        val moduleName: String,
        val count: Int,
        val branches: List<PackageBranch>,
        val previews: List<PreviewLeaf> = emptyList(),
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

    data class PreviewLeaf(val row: PreviewRow) : PreviewNode
}
