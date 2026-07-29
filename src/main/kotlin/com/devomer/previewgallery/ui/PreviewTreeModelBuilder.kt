package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/**
 * Builds the module -> package branch -> preview tree. Module counts reflect the filtered result, not the
 * whole project.
 *
 * Only the filtering and the module level live here; [PackageTreeBuilder] owns the nesting and compaction of
 * package segments below each module.
 *
 * Modules sort case-insensitively, matching the search filter, so a freeform `@Preview(name = ...)` does not
 * sort away from the PascalCase names around it. The level sorts a list rather than building a comparator-keyed
 * map: a `TreeMap` ordered by `CASE_INSENSITIVE_ORDER` treats names differing only in case as one key, which
 * would silently drop a whole module.
 */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, query: String): List<PreviewNode.ModuleNode> =
        PreviewSearchFilter.filter(rows, query)
            .groupBy { it.moduleName }
            .entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .map { (moduleName, moduleRows) ->
                val tree = PackageTreeBuilder.build(moduleRows)
                PreviewNode.ModuleNode(
                    moduleName = moduleName,
                    count = moduleRows.size,
                    branches = tree.branches,
                    previews = tree.previews,
                )
            }
}
