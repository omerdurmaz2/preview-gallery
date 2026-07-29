package com.devomer.previewgallery.ui

import com.devomer.previewgallery.index.PreviewIndex
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.indexing.FileBasedIndex

/**
 * Fires [onIndexingFinished] once the IDE has finished a round of indexing that actually changed
 * [PreviewIndex] (PG11-2). Shaped like its sibling [ActiveModuleTracker]: a message-bus subscription tied to
 * the panel's lifetime, with the "is this signal worth acting on?" decision kept out of the listener body — in
 * [IndexStampGate] here, in `lastReportedModuleName` there.
 *
 * ## The bug this exists for
 *
 * [PreviewGalleryPanel.reload] defers to `DumbService.runWhenSmart` while the project is dumb, and
 * [com.devomer.previewgallery.service.PreviewIndexService.findAll] caches its result against
 * `PsiModificationTracker.MODIFICATION_COUNT`. Indexing a large project is not one dumb→smart transition but
 * several: the panel reloads at the *first* one, when the index holds a fraction of the project, and caches
 * that partial list. Every later pass fills the index without touching PSI, so nothing invalidates the cache
 * and the tree keeps showing those first few modules until the user presses Refresh by hand.
 *
 * Observed on `hepsi-android` (2026-07-29), one startup after the index was rebuilt from scratch — `PreviewIndex`
 * took `3,918` files, then `115,805`, then `1,581`, then `4`, across four separate indexing passes. The tree
 * showed 4 of 40+ modules.
 *
 * ## Why exitDumbMode, and why the stamp
 *
 * `DumbService.DUMB_MODE`'s `exitDumbMode` is the platform's own "indexes are queryable again" signal — the same
 * edge `runWhenSmart` rides, except this listener keeps receiving it instead of firing once. Every exit is then
 * filtered through [IndexStampGate] so only the exits that moved [PreviewIndex] cost a reload, and coalesced
 * through [alarm] so a burst of transitions (three within two seconds in the observed startup) collapses into
 * one — the same `cancelAllRequests` + `addRequest` debounce the panel already uses for its search field.
 *
 * Platform API only ([FileBasedIndex.getIndexModificationStamp], `DumbService.DUMB_MODE`); nothing here touches
 * Android Studio internals, which stay confined to `render/` (design §3.1).
 */
class IndexingCompletionTracker(
    private val project: Project,
    parentDisposable: Disposable,
    private val onIndexingFinished: () -> Unit,
) {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    /** Seeded with the stamp as it is right now, so the panel's own constructor-time load is the baseline and
     *  the first *later* indexing pass is what triggers the first reload — not the signal that the already-done
     *  initial load itself produced. */
    private val gate = IndexStampGate(indexStamp())

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    onExitDumbMode()
                }
            },
        )
    }

    private fun onExitDumbMode() {
        if (!gate.accept(indexStamp())) return
        alarm.cancelAllRequests()
        alarm.addRequest({ onIndexingFinished() }, SETTLE_DELAY_MS)
    }

    /**
     * [PreviewIndex]'s current modification stamp, or [IndexStampGate.UNKNOWN_STAMP] when the platform call
     * fails. Guarded because this runs on the EDT inside a message-bus callback, where an exception would
     * escape into the platform's own dispatch loop; degrading to "unknown" makes the gate fail open (see
     * [IndexStampGate]) rather than silently disabling the refresh.
     */
    private fun indexStamp(): Long = try {
        FileBasedIndex.getInstance().getIndexModificationStamp(PreviewIndex.NAME, project)
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().info("Could not read the preview index modification stamp; refreshing unconditionally", e)
        IndexStampGate.UNKNOWN_STAMP
    }

    companion object {
        /** Long enough to swallow a burst of dumb→smart transitions, short enough that the tree fills in as
         *  soon as indexing settles. Mirrors the panel's own search debounce in spirit, not in value: this one
         *  waits for the IDE rather than for a human's keystrokes. */
        internal const val SETTLE_DELAY_MS = 400
    }
}
