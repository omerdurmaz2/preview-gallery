package com.devomer.previewgallery.model

/**
 * The subset of a preview that search, grouping and detail rendering need. Keeping this free of `VirtualFile`
 * lets those components be unit-tested without an IDE fixture.
 */
interface PreviewRow {
    val indexed: IndexedPreview
    val moduleName: String

    /** Resolved at query time, not indexed: it is a cross-file relation. */
    val coverage: SnapshotCoverage

    /** The snapshot rows that show the same composable as this preview. */
    val snapshots: List<PreviewRow> get() = emptyList()
}
