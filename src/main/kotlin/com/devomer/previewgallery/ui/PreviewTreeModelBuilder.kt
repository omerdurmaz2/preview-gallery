package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/**
 * Builds the module -> package -> preview tree. Module counts reflect the filtered result, not the
 * whole project.
 *
 * Sorting is case-insensitive at every level, matching the search filter, so a freeform
 * `@Preview(name = ...)` does not sort away from the PascalCase names around it.
 */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, query: String): List<PreviewNode.ModuleNode> =
        PreviewSearchFilter.filter(rows, query)
            .groupBy { it.moduleName }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (moduleName, moduleRows) ->
                val packages = moduleRows
                    .groupBy { it.indexed.packageName }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (packageName, packageRows) ->
                        PreviewNode.PackageNode(
                            packageName = packageName,
                            previews = packageRows
                                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName })
                                .map { PreviewNode.PreviewLeaf(it) },
                        )
                    }
                PreviewNode.ModuleNode(moduleName, moduleRows.size, packages)
            }
}
