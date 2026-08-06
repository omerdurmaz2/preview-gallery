package com.devomer.previewgallery.model

/**
 * Whether the composable a preview shows has a snapshot test.
 *
 * A module with no `src/screenshotTest` is not a third case: its previews are [Uncovered] and the module reads
 * as 0% covered. The question the gallery answers is about the composable, not about the module's build setup —
 * a module that never adopted screenshot testing has written no snapshot for any of its previews, which is
 * exactly what [Uncovered] says.
 */
sealed interface SnapshotCoverage {
    data class Covered(val count: Int) : SnapshotCoverage
    data object Uncovered : SnapshotCoverage
}
