package com.devomer.previewgallery.ui

/**
 * Decides whether an "indexing finished" signal actually warrants reloading the gallery, by comparing
 * [PreviewIndex][com.devomer.previewgallery.index.PreviewIndex]'s modification stamp against the one seen at the
 * previous accepted signal (PG11-2).
 *
 * The IDE leaves dumb mode far more often than this plugin's own index changes: on a large project, a build,
 * a VFS refresh or a project-model update each enter and exit dumb mode without a single Kotlin file being
 * re-indexed (observed on `hepsi-android`: of five dumb-mode cycles during one startup, two touched
 * `PreviewIndex` and three did not). Reloading on every one of them would run a full `processAllKeys` scan of
 * the index for nothing, so the stamp is what separates "the index really changed" from "the IDE was merely
 * busy".
 *
 * Deliberately fails **open**: an [UNKNOWN_STAMP] on either side — the platform call threw, or this is the very
 * first signal — accepts the signal. Over-refreshing costs one redundant scan; under-refreshing leaves the user
 * staring at a stale tree with no way to know it is stale, which is the exact bug this class exists to fix.
 *
 * Not thread-safe: every caller ([IndexingCompletionTracker]) is on the EDT.
 */
internal class IndexStampGate(private var lastStamp: Long = UNKNOWN_STAMP) {

    /**
     * @return `true` when [currentStamp] shows the index moved since the last accepted signal, in which case
     * this call also becomes the new baseline. `false` leaves the baseline untouched, so a later, genuinely
     * different stamp is still compared against the last stamp actually acted on.
     */
    fun accept(currentStamp: Long): Boolean {
        if (lastStamp != UNKNOWN_STAMP && currentStamp != UNKNOWN_STAMP && currentStamp == lastStamp) {
            return false
        }
        lastStamp = currentStamp
        return true
    }

    companion object {
        /** The stamp is unavailable — no baseline yet, or the platform lookup failed. Always accepted. */
        const val UNKNOWN_STAMP: Long = -1L
    }
}
