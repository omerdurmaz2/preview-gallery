package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/** Builds the module -> package -> preview tree. Module counts reflect the filtered result, not the whole project. */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, query: String): List<PreviewNode.ModuleNode> =
        PreviewSearchFilter.filter(rows, query)
            .groupBy { it.moduleName }
            .toSortedMap()
            .map { (moduleName, moduleRows) ->
                val packages = moduleRows
                    .groupBy { it.indexed.packageName }
                    .toSortedMap()
                    .map { (packageName, packageRows) ->
                        PreviewNode.PackageNode(
                            packageName = packageName,
                            previews = packageRows
                                .sortedBy { it.indexed.displayName }
                                .map { PreviewNode.PreviewLeaf(it) },
                        )
                    }
                PreviewNode.ModuleNode(moduleName, moduleRows.size, packages)
            }
}
