package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/** A Swing-free tree shape, so grouping can be tested without a `JTree`. */
sealed interface PreviewNode {

    data class ModuleNode(
        val moduleName: String,
        val count: Int,
        val packages: List<PackageNode>,
    ) : PreviewNode

    data class PackageNode(
        val packageName: String,
        val previews: List<PreviewLeaf>,
    ) : PreviewNode

    data class PreviewLeaf(val row: PreviewRow) : PreviewNode
}
