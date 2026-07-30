package com.devomer.previewgallery.model

/**
 * Whether the composable a preview shows has a snapshot test.
 *
 * [NotApplicable] is not "unknown": it means the module has no `src/screenshotTest` at all, so the question does
 * not apply and no badge is drawn. Badging those rows would paint a whole project as failing and the signal
 * would be discarded.
 */
sealed interface SnapshotCoverage {
    data class Covered(val count: Int) : SnapshotCoverage
    data object Uncovered : SnapshotCoverage
    data object NotApplicable : SnapshotCoverage
}
