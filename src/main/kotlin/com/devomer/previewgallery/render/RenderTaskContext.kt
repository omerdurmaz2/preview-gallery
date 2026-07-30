package com.devomer.previewgallery.render

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable

/**
 * Gives a blocking background task the cancellable context the platform's suspend bridge
 * (`com.intellij.openapi.progress.runBlockingCancellable`) requires.
 *
 * ## Why this exists
 *
 * `runBlockingCancellable` needs a `Job` in the calling thread's context, or a `ProgressIndicator` on the thread
 * that `ContextKt.prepareThreadContext` can derive one from. Every path that reaches this plugin's one suspend
 * bridge ([RenderModelResolver.findConfigAwareElement]) starts on the EDT — the selection debounce alarm in
 * [RenderPipeline.select], a comparison view in [RenderPipeline.renderVariant], the ephemeral picker button's
 * `ActionListener` in [EphemeralPickerBridge] — and hops to `AppExecutorUtil.getAppExecutorService()`. The
 * platform propagates the *submitting* thread's context onto that pooled thread (`ChildContext`), and a plain
 * Swing callback's context holds neither a `Job` nor an indicator.
 *
 * The platform does not throw on that: it reports
 * `IllegalStateException: There is no ProgressIndicator or Job in this thread, the current job is not cancellable.`
 * through `Logger.error` and then runs the coroutine anyway, as an orphan nothing can cancel. `Logger.error` is
 * what Android Studio turns into an internal-error notification — which is why selecting a preview raised one
 * while the render itself still worked, and why no `catch` around the bridge ever saw it.
 *
 * ## What it does
 *
 * Installs an [EmptyProgressIndicator] for the duration of the task, so the bridge inside it has a context to
 * derive its `Job` from. Nothing cancels that indicator today — the render path deliberately has no preemption
 * (PG3-6) — so this changes no behaviour beyond silencing the report; it is the seam a real per-render indicator
 * would later be threaded through.
 *
 * A caller that already has an indicator keeps it: [RenderPipeline.buildThenRender] renders from the build
 * callback, which can still be running under the build's own progress, and swapping that for an indicator nothing
 * ever cancels would throw its cancellation away. A caller whose context carries a `Job` but no indicator is not
 * detectable through public API and would be shadowed by ours — no such caller exists, every path into the bridge
 * is blocking code submitted to a plain executor.
 */
internal object RenderTaskContext {

    fun <T> runCancellable(task: () -> T): T {
        if (ProgressManager.getInstance().progressIndicator != null) return task()
        return ProgressManager.getInstance().runProcess(Computable { task() }, EmptyProgressIndicator())
    }
}
