package com.devomer.previewgallery.ui

import com.intellij.openapi.vfs.VirtualFile

/**
 * Picks the right file among same-named candidates using AS's `packageHash`. Pure — the caller computes each
 * candidate's package hash (needs PSI / a read action) and the target hash comes from the clicked node. Falls
 * back to the first candidate (today's `firstOrNull` behaviour) whenever the target is null or nothing matches,
 * so a wrong/absent hash never navigates worse than before.
 */
object SourceFileDisambiguator {

    data class Candidate(val file: VirtualFile, val packageHash: Int?)

    fun pick(targetHash: Int?, candidates: List<Candidate>): VirtualFile? {
        if (candidates.isEmpty()) return null
        if (targetHash != null) {
            candidates.firstOrNull { it.packageHash == targetHash }?.let { return it.file }
        }
        return candidates.first().file
    }
}
