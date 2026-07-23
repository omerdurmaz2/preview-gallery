package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow

/**
 * Case-insensitive substring search over the preview name, function name and package.
 *
 * A plain scan is enough: the reference corpus is under 1000 entries and the tool window filters below 10k.
 */
object PreviewSearchFilter {

    fun matches(row: PreviewRow, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val indexed = row.indexed
        return indexed.displayName.contains(trimmed, ignoreCase = true) ||
            indexed.functionName.contains(trimmed, ignoreCase = true) ||
            indexed.packageName.contains(trimmed, ignoreCase = true)
    }

    fun <T : PreviewRow> filter(rows: List<T>, query: String): List<T> = rows.filter { matches(it, query) }
}
