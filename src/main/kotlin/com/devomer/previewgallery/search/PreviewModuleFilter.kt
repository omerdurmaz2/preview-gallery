package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow

/** Restricts rows to the module of the file currently open in the editor. */
object PreviewModuleFilter {

    fun <T : PreviewRow> apply(rows: List<T>, activeModuleName: String?, enabled: Boolean): List<T> = when {
        !enabled -> rows
        activeModuleName == null -> emptyList()
        else -> rows.filter { it.moduleName == activeModuleName }
    }
}
