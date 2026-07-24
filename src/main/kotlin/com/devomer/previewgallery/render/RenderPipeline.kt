package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.RenderOutcome
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

data class RenderResultView(val state: RenderState, val outcome: RenderOutcome?, val moduleName: String?)

/**
 * Orchestrates selection -> render. A stale module builds automatically on selection (revised D3/B3); the
 * Render button is only a manual retry after a [RenderState.FAILED] render. Debounced with in-flight
 * cancellation so arrow-keying through previews queues no work: [generation] is bumped on every [select] and
 * [requestBuildAndRender] call, and every async completion (build or render) checks it is still current before
 * acting, so a superseded selection's build/render is silently ignored rather than cancelled outright.
 */
class RenderPipeline(
    private val project: Project,
    private val renderer: LiveRenderer,
    private val build: BuildService,
    parentDisposable: Disposable,
    private val onStateChange: (RenderResultView) -> Unit,
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    @Volatile private var generation = 0

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
        alarm.cancelAllRequests()
        val gen = ++generation
        if (entry == null) {
            onStateChange(RenderResultView(RenderState.IDLE, null, null))
            return
        }
        alarm.addRequest({ dispatch(entry, gen) }, DEBOUNCE_MS)
    }

    private fun dispatch(entry: PreviewEntry, gen: Int) {
        val fresh = ReadAction.compute<Boolean, RuntimeException> {
            val module = ModuleManager.getInstance(project).findModuleByName(entry.moduleName)
            module != null && ModuleFreshness.isModuleFresh(module)
        }
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
            if (success) render(entry, gen)
            else onStateChange(RenderResultView(RenderState.FAILED, RenderOutcome.Failure("Build failed", null), entry.moduleName))
        }
    }

    private fun render(entry: PreviewEntry, gen: Int) {
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
        ReadAction.nonBlocking<RenderOutcome> { renderer.render(entry) }
            .finishOnUiThread(ModalityState.defaultModalityState()) { outcome ->
                if (gen != generation) return@finishOnUiThread
                val state = when (outcome) {
                    is RenderOutcome.Success -> RenderState.LIVE
                    is RenderOutcome.Failure -> RenderState.FAILED
                    is RenderOutcome.Unsupported -> RenderState.UNSUPPORTED
                }
                onStateChange(RenderResultView(state, outcome, entry.moduleName))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun unsupportedOutcome(entry: PreviewEntry): RenderOutcome.Unsupported = RenderOutcome.Unsupported(
        entry.indexed.unsupportedReason ?: if (entry.indexed.hasPreviewParameter) "@PreviewParameter is not supported" else "Unsupported",
    )
}
