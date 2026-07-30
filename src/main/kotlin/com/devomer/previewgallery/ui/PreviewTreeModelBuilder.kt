package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/**
 * Applies the query, then builds the module -> package branch -> preview tree over what survives.
 *
 * Only the filtering lives here now; the module level's own nesting and compaction (a module name is itself a
 * path, e.g. `features.buy.basket`) is [ModuleTreeBuilder]'s job, and [PackageTreeBuilder] still owns the nesting
 * and compaction of package segments below each module. This function is left as the single entry point so
 * callers do not need to know the tree is now built in two layers.
 *
 * [rows] and [orphans] are filtered by the same [query], independently (spec D11): a surviving preview keeps all
 * of its own snapshot children regardless of whether their names match, and the orphan branch is filtered by its
 * own snapshot names — a snapshot name never pulls an otherwise-filtered-out preview back into the tree.
 */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, orphans: List<T>, query: String): List<PreviewNode.ModuleNode> =
        ModuleTreeBuilder.build(
            PreviewSearchFilter.filter(rows, query),
            PreviewSearchFilter.filter(orphans, query),
        )
}
