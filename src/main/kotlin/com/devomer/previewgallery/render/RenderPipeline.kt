package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.model.ViewConfig
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

data class RenderResultView(val state: RenderState, val outcome: RenderOutcome?, val moduleName: String?)

/**
 * Orchestrates selection -> render. A stale module builds automatically on selection (revised D3/B3); the
 * Render button is only a manual retry after a [RenderState.FAILED] render. Debounced with in-flight
 * cancellation so arrow-keying through previews queues no work: [generation] is bumped on every [select],
 * [requestBuildAndRender], and [rerenderCurrent] call, and every async completion (build or render) checks it
 * is still current before acting, so a superseded selection's build/render is silently ignored rather than
 * cancelled outright.
 */
class RenderPipeline(
    private val project: Project,
    private val renderer: LiveRenderer,
    private val build: BuildService,
    private val parentDisposable: Disposable,
    private val onStateChange: (RenderResultView) -> Unit,
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    // PG3-6: a live "is the panel gone" check for publishRenderResult, now that render() no longer runs under
    // ReadAction.nonBlocking (which used to make this unnecessary). Disposer.isDisposed(Disposable) is
    // deprecated; a CheckedDisposable registered as parentDisposable's own child is the platform's replacement —
    // its isDisposed flips exactly when parentDisposable is disposed.
    private val disposalCheck: CheckedDisposable = Disposer.newCheckedDisposable(parentDisposable)

    @Volatile private var generation = 0

    // The entry currently dispatched/rendered, so rerenderCurrent() knows what to re-render without the
    // selection changing. @Volatile like [generation]: the picker's change signal (PG4-1) is documented to fire
    // on the EDT, but its delivery thread is AS-internal and not contractually guaranteed, so a plain var would
    // not reliably publish this field to a would-be off-EDT caller; @Volatile plus the thread-safe Alarm keep
    // rerenderCurrent correct from any thread.
    @Volatile private var currentEntry: PreviewEntry? = null

    companion object {
        const val DEBOUNCE_MS = 400

        /** Pure: the state a selection lands in before any async render runs. Booleans, not a PreviewEntry, so
         *  it is unit-testable without a VirtualFile fixture. */
        fun classify(unsupported: Boolean, hasPreviewParameter: Boolean, isFresh: Boolean): RenderState = when {
            unsupported -> RenderState.UNSUPPORTED
            hasPreviewParameter -> RenderState.UNSUPPORTED
            isFresh -> RenderState.RENDERING
            else -> RenderState.NEEDS_BUILD
        }
    }

    fun select(entry: PreviewEntry?) {
        // Track the selection synchronously (not just inside dispatch/requestBuildAndRender) so a picker-
        // triggered rerenderCurrent() targets the newly selected entry even before the debounced dispatch below
        // runs, and so it clears to null on deselection instead of leaving rerenderCurrent() pointed at the
        // previous, no-longer-selected entry.
        currentEntry = entry
        alarm.cancelAllRequests()
        val gen = ++generation
        if (entry == null) {
            onStateChange(RenderResultView(RenderState.IDLE, null, null))
            return
        }
        alarm.addRequest({ dispatch(entry, gen) }, DEBOUNCE_MS)
    }

    /**
     * Re-render whatever is currently displayed. Used after the picker edits the @Preview annotation: the
     * selection has not changed, so [select] would be a no-op, but the source has. Debounced and generation-
     * guarded like every other render path, so a burst of picker edits does not queue a render each.
     *
     * Intentionally calls [render] directly, not [dispatch]: a picker edit changes only the `@Preview` arguments,
     * never the compiled code, so the module's freshness/build verdict is unchanged and re-checking it (or
     * rebuilding) would be wasted work. The rare edge — editing the picker while a stale module is still doing its
     * first build — renders directly (likely a transient FAILED) and is self-correcting via the Render button.
     */
    fun rerenderCurrent() {
        val entry = currentEntry ?: return
        alarm.cancelAllRequests()
        alarm.addRequest({ render(entry, ++generation) }, DEBOUNCE_MS)
    }

    /**
     * Off-EDT entry point for a single comparison-view render (PG6-4): renders [entry] with [config] and delivers
     * [onResult] on the EDT. Deliberately separate from [select]/[dispatch]/[render]/[rerenderCurrent]: those all
     * share one debounced [generation] for the single Original selection, which does not fit the comparison-view
     * tab strip's N independent, concurrently-live per-tab renders — a `select`-shaped call for tab 2 would
     * cancel/supersede tab 1's in-flight render via that same counter (flagged explicitly in the task-3 report,
     * §4). This method reads and bumps neither [generation] nor [currentEntry], so it can never affect, or be
     * affected by, the Original selection's render.
     *
     * [config] (PG6-7, widened from PG6-3's device-only override) is the three-axis device/theme/font-scale
     * override for this one comparison copy — forwarded to [LiveRenderer.render] unchanged.
     *
     * Submits straight to the same background executor [render] uses (no debounce — every call is expected to be
     * an explicit, individual user action: add a view, change its device, or activate an unrendered tab), and
     * takes no outer read action for the same reason [render]'s class doc gives: [renderer] (`LiveRenderer`) and
     * the model resolver it delegates to already take their own short read actions for the PSI/project-model
     * slivers they need.
     *
     * Its own staleness/cancellation guard is [disposalCheck] — the same "is the panel gone" check
     * [publishRenderResult] uses — so a torn-down panel never receives a late callback; which *tab*'s result this
     * is, and whether that tab is still the one the user wants (device changed again, tab closed, or the whole
     * comparison set was cleared), is [onResult]'s caller's own responsibility (a per-tab generation token in
     * [com.devomer.previewgallery.ui.PreviewRenderPanel]) — this method has no notion of "tabs" at all.
     */
    fun renderVariant(entry: PreviewEntry, config: ViewConfig, onResult: (RenderOutcome) -> Unit) {
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val outcome = try {
                renderer.render(entry, config)
            } catch (e: ProcessCanceledException) {
                // Same rationale as render()'s identical catch: never swallow cancellation, and nothing is
                // waiting to replace this particular request, so simply publish nothing.
                return@execute
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposalCheck.isDisposed) return@invokeLater
                    onResult(outcome)
                },
                modality,
            )
        }
    }

    private fun dispatch(entry: PreviewEntry, gen: Int) {
        currentEntry = entry
        // PG3-5: no outer read action here anymore. ModuleFreshness.isModuleFresh takes its own short read
        // action for the project-model part only; the filesystem walk it does afterwards must NOT run under a
        // read action (that used to hold the read lock for the whole walk, blocking every write action in the
        // IDE — a prime freeze suspect). ModuleManager.findModuleByName is called bare, matching the existing
        // precedent in buildThenRender below.
        val module = ModuleManager.getInstance(project).findModuleByName(entry.moduleName)
        val fresh = module != null && ModuleFreshness.isModuleFresh(module)
        val state = classify(entry.indexed.unsupportedReason != null, entry.indexed.hasPreviewParameter, fresh)
        when (state) {
            RenderState.UNSUPPORTED ->
                onStateChange(RenderResultView(RenderState.UNSUPPORTED, unsupportedOutcome(entry), entry.moduleName))
            RenderState.NEEDS_BUILD -> {
                // D3/B3 (revised): a stale module builds itself, no button click required. The panel shows this
                // as the "building" state; [buildThenRender] checks [gen] again once the build finishes, so a
                // selection made while this build is in flight silently supersedes it.
                onStateChange(RenderResultView(RenderState.NEEDS_BUILD, null, entry.moduleName))
                buildThenRender(entry, gen)
            }
            RenderState.RENDERING -> render(entry, gen)
            else -> {}
        }
    }

    /** Manual retry after a [RenderState.FAILED] render — the Render button's only remaining path (D3/B3). */
    fun requestBuildAndRender(entry: PreviewEntry) {
        currentEntry = entry
        val gen = ++generation
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
        buildThenRender(entry, gen)
    }

    /**
     * Builds [entry]'s module and renders on success. Shared by the automatic (selection) and manual (Render
     * button retry) paths. [gen] is the generation captured by the caller before any state change; the build
     * completion callback re-checks it against the live [generation] so a stale build finishing late (the
     * selection moved on, or a newer build/render started) is ignored rather than clobbering newer output —
     * [BuildService] itself only cancels its own in-flight task when a *new* build is requested, not merely
     * when the selection changes, so this check is the actual staleness guard for "walked past it" builds.
     */
    private fun buildThenRender(entry: PreviewEntry, gen: Int) {
        if (DumbService.isDumb(project)) {
            onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Indexing", null), entry.moduleName))
            return
        }
        val module = ModuleManager.getInstance(project).findModuleByName(entry.moduleName) ?: run {
            onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Module not found", null), entry.moduleName))
            return
        }
        build.build(module) { success ->
            if (gen != generation) return@build
            if (success) {
                // The build just changed what's on disk under this module's output directory; drop the cached
                // freshness verdict so a selection elsewhere in this module within the cache TTL (PG3-5)
                // re-derives from the new output instead of replaying the pre-build "stale" verdict that
                // triggered this very build.
                ModuleFreshness.invalidate(module)
                render(entry, gen)
            } else {
                onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Build failed", null), entry.moduleName))
            }
        }
    }

    /**
     * PG3-6: [renderer] does a layoutlib render that can take seconds (up to its own 30 s timeout). The old code
     * ran it inside `ReadAction.nonBlocking`, which holds the read lock for that whole time and blocks every
     * write action in the IDE — the second freeze cause. [LiveRenderer] / [RenderModelResolver] already take
     * their own short read actions for the PSI/project-model slivers they need, so the outer read action was
     * both harmful and unnecessary; this submits straight to a background executor instead.
     *
     * That trades away `ReadAction.nonBlocking`'s built-in cancellation (see the PG3-6 report for how
     * cancellation behaves now): nothing here preempts an in-flight [LiveRenderer.render] call anymore, it always
     * runs to completion. What is preserved: [gen] is still checked against the live [generation] before any UI
     * update, so a superseded selection's result is discarded, not shown; the result still reaches [onStateChange]
     * only on the EDT, at the modality captured here (matching the old `finishOnUiThread` timing exactly); and a
     * disposed panel ([parentDisposable]) is checked right before that UI update too, so this cannot outlive it.
     *
     * [viewConfig] (PG6-7, widened from PG6-3's device-only override) is forwarded to [LiveRenderer.render]
     * unchanged; it is `null` on every call site today ([dispatch], [buildThenRender] and [rerenderCurrent] all
     * call this with just `(entry, gen)` — the single debounced Original selection never carries a view
     * override), which reproduces this method's exact pre-override behaviour. A non-null, config-bearing value
     * only ever reaches [LiveRenderer] via [renderVariant]'s own, separate path.
     */
    private fun render(entry: PreviewEntry, gen: Int, viewConfig: ViewConfig? = null) {
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute {
            val outcome = try {
                renderer.render(entry, viewConfig)
            } catch (e: ProcessCanceledException) {
                // LiveRenderer.render() always re-throws cancellation rather than swallowing it (design §5). With
                // no ReadAction.nonBlocking around this call anymore, nothing downstream is waiting to catch this
                // specifically — there is no next attempt already queued to replace this one the way a superseded
                // generation implies, so the correct move is simply not publishing anything: whatever the panel
                // was already showing stays showing, exactly as if this render had not run at all.
                return@execute
            }
            publishRenderResult(entry, gen, outcome, modality)
        }
    }

    /** Runs on the EDT, at [modality] (captured on the caller's thread in [render], before hopping to the
     *  background executor — the exact same timing the old `finishOnUiThread(ModalityState...)` used). */
    private fun publishRenderResult(entry: PreviewEntry, gen: Int, outcome: RenderOutcome, modality: ModalityState) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (gen != generation) return@invokeLater
                if (disposalCheck.isDisposed) return@invokeLater
                val state = when (outcome) {
                    is RenderOutcome.Success -> RenderState.LIVE
                    is RenderOutcome.Failure -> RenderState.FAILED
                    is RenderOutcome.Unsupported -> RenderState.UNSUPPORTED
                }
                onStateChange(RenderResultView(state, outcome, entry.moduleName))
            },
            modality,
        )
    }

    private fun unsupportedOutcome(entry: PreviewEntry): RenderOutcome.Unsupported = RenderOutcome.Unsupported(
        entry.indexed.unsupportedReason ?: if (entry.indexed.hasPreviewParameter) "@PreviewParameter is not supported" else "Unsupported",
    )
}
