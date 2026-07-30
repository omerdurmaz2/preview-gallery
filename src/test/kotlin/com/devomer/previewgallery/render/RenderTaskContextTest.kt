package com.devomer.previewgallery.render

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers [RenderTaskContext] — the cancellable context the render and picker background tasks must install
 * before they bridge Android Studio's `suspend` preview-element finder with `runBlockingCancellable`
 * (see [RenderModelResolver.findConfigAwareElement]).
 *
 * The bug this locks down: `runBlockingCancellable` requires a `Job` in the calling thread's context (or a
 * `ProgressIndicator` it can derive one from). Every path into that bridge starts on the EDT — the selection
 * alarm, a comparison tab, the picker button's `ActionListener` — and hops to
 * `AppExecutorUtil.getAppExecutorService()`, which propagates the *submitting* thread's context; a plain Swing
 * callback carries neither. The platform does not throw in that case, it reports the orphan through
 * `Logger.error`, which Android Studio surfaces as an internal-error notification — so no `catch` in this plugin
 * ever saw it and the first render of every sandbox session showed one.
 *
 * Each test runs its body on a bare [Thread] rather than on the app executor: a fresh thread starts with no
 * indicator and no context, which is exactly the state production reaches through that propagation, without the
 * test depending on whatever context the test EDT happens to carry.
 */
class RenderTaskContextTest : BasePlatformTestCase() {

    /**
     * The canary for the platform contract the whole class exists for. If a future IDE stops reporting an
     * uncancellable bridge, this fails first and [RenderTaskContext] can go.
     */
    fun `test the suspend bridge without a cancellable context is reported by the platform`() {
        val reported = LoggedErrorProcessor.executeAndReturnLoggedError {
            onBareThread { runBlockingCancellable { BRIDGED } }
        }

        assertTrue(
            "Expected the platform's uncancellable-bridge report, got: $reported",
            reported.message.orEmpty().contains("no ProgressIndicator or Job"),
        )
    }

    /**
     * The regression test proper: no interception, so a logged error would be rethrown by the test framework on
     * the bare thread and resurface here through [onBareThread].
     */
    fun `test the suspend bridge inside runCancellable is not reported and returns its value`() {
        assertEquals(BRIDGED, onBareThread { RenderTaskContext.runCancellable { runBlockingCancellable { BRIDGED } } })
    }

    fun `test runCancellable installs an indicator on a thread that has none`() {
        assertNotNull(onBareThread { RenderTaskContext.runCancellable { currentIndicator() } })
    }

    /** An indicator the caller already owns is kept, so its cancellation still reaches the bridge. */
    fun `test runCancellable keeps an indicator the caller already installed`() {
        val callers = EmptyProgressIndicator()

        val seen = onBareThread {
            ProgressManager.getInstance().runProcess(
                Computable { RenderTaskContext.runCancellable { currentIndicator() } },
                callers,
            )
        }

        assertSame(callers, seen)
    }

    private fun currentIndicator() = ProgressManager.getInstance().progressIndicator

    /** Runs [task] on a thread with an empty context and rethrows whatever it threw on the test thread. */
    private fun <T> onBareThread(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val thread = Thread({ result.set(runCatching(task)) }, "RenderTaskContextTest")

        thread.start()
        thread.join(TIMEOUT_MS)

        assertFalse("The task did not finish within $TIMEOUT_MS ms", thread.isAlive)
        return result.get().getOrThrow()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L

        /** The value the bridged `suspend` block returns — stands in for the finder's element list. */
        const val BRIDGED = 1
    }
}
