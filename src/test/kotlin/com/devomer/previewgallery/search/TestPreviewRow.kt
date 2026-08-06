package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/** A `PreviewRow` with no `VirtualFile`, so pure logic can be tested without an IDE fixture. */
data class TestPreviewRow(
    override val indexed: IndexedPreview,
    override val moduleName: String,
    override val coverage: SnapshotCoverage = SnapshotCoverage.Uncovered,
    override val snapshots: List<TestPreviewRow> = emptyList(),
) : PreviewRow

fun testRow(
    displayName: String = "BarPreview",
    functionName: String = "BarPreview",
    packageName: String = "com.example",
    moduleName: String = "app",
    isSnapshotTest: Boolean = false,
    targets: List<String> = emptyList(),
): TestPreviewRow = TestPreviewRow(
    indexed = IndexedPreview(
        displayName = displayName,
        functionName = functionName,
        packageName = packageName,
        jvmClassName = "$packageName.FooKt",
        composableFqn = "$packageName.FooKt.$functionName",
        offset = 0,
        annotationKind = AnnotationKind.ANDROIDX,
        isPrivate = false,
        hasPreviewParameter = false,
        previewGroup = null,
        unsupportedReason = null,
        isSnapshotTest = isSnapshotTest,
        targets = targets,
    ),
    moduleName = moduleName,
)
