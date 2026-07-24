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
 * Orchestrates selection -> render. Selection never builds (spec B3); the Render button does. Debounced with
 * in-flight cancellation so arrow-keying through previews queues no work.
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
            onStateChange(RenderResultView(RenderState.UNSUPPORTED, null, null))
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
            RenderState.NEEDS_BUILD ->
                onStateChange(RenderResultView(RenderState.NEEDS_BUILD, null, entry.moduleName))
            RenderState.RENDERING -> render(entry, gen)
            else -> {}
        }
    }

    fun requestBuildAndRender(entry: PreviewEntry) {
        val gen = ++generation
        onStateChange(RenderResultView(RenderState.RENDERING, null, entry.moduleName))
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
