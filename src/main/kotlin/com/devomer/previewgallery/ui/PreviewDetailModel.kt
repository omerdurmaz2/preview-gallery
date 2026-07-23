package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.PreviewRow

data class DetailField(val label: String, val value: String)

/** The label/value pairs the detail panel renders. Kept free of Swing so it can be asserted directly. */
object PreviewDetailModel {

    /** @param line zero-based, as `Document.getLineNumber` returns it; displayed one-based. */
    fun fields(row: PreviewRow, fileName: String, line: Int?): List<DetailField> {
        val indexed = row.indexed
        val fields = mutableListOf(
            DetailField("Name", indexed.displayName),
            DetailField("Function", indexed.functionName),
            DetailField("FQN", indexed.composableFqn),
            DetailField("Module", row.moduleName),
            DetailField("File", if (line == null) fileName else "$fileName:${line + 1}"),
            DetailField(
                "Annotation",
                when (indexed.annotationKind) {
                    AnnotationKind.ANDROIDX -> "androidx"
                    AnnotationKind.JETBRAINS -> "org.jetbrains"
                    AnnotationKind.UNKNOWN -> "unknown"
                },
            ),
        )
        if (indexed.isPrivate) fields += DetailField("Private", "yes")
        if (indexed.hasPreviewParameter) fields += DetailField("PreviewParameter", "yes")
        indexed.previewGroup?.let { fields += DetailField("Group", it) }
        indexed.unsupportedReason?.let { fields += DetailField("Unsupported", it) }
        return fields
    }
}
