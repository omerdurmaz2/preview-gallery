package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.DeviceOption
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.render.RenderModelResolver.RenderModelResult
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// ── Android Studio internal render API. Isolated here + in RenderModelResolver only (design §3.1). ──
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.ResourceFolderType
import com.android.tools.idea.compose.preview.ComposeViewInfo
import com.android.tools.idea.compose.preview.parseViewInfo
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderTask
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot

/**
 * Renders a composable by FQN through Android Studio's layoutlib pipeline. Together with [RenderModelResolver]
 * this is the ONLY home for AS-internal render API coupling (design §3.1).
 *
 * [render] must be called OFF the EDT (design §5.1). It resolves the render model, feeds the compose bridge XML to
 * a `RenderTask`, drives the task through Android Studio's own compose render sequence, and copies the pooled image
 * into a standalone [BufferedImage] before disposing the task. Every AS-internal call is guarded against [Exception]
 * and [LinkageError]; nothing throws out of [render] except [ProcessCanceledException] (which must propagate).
 *
 * ## Why a single inflate+render is not enough (the blank-frame fix)
 *
 * `ComposeViewAdapter` mounts a `ComposeView`; Compose only actually composes once it has been attached and has
 * been given frame callbacks to run. Android Studio therefore never does one inflate+render. Two AS code paths
 * were read to establish the real sequence (Android Studio Panda 4, `plugins/design-tools/lib/design-tools.jar`):
 *
 * 1. `com.android.tools.idea.preview.SurfacePreviewsUpdateKt.updatePreviewsAndRefresh` sets, immediately before
 *    the render request, `sceneRenderConfiguration.executeCallbacksAfterRender.set(true)` and
 *    `sceneRenderConfiguration.doubleRender.set(true)`.
 * 2. `com.android.tools.idea.uibuilder.scene.LayoutlibSceneRenderer` then runs, per request:
 *    `inflate()` → `RenderTask.render()` → `executeAllCallbacks()` → (because `doubleRender`) `RenderTask.render()`
 *    again. `executeAllCallbacks` is `withTimeoutOrNull(100ms) { do { RenderTask.executeCallbacks(clock.timeNanos) }
 *    while (it.hasMoreCallbacks()) }`, and for a static preview the clock is a `SteppingSessionClock(16.ms)`.
 *
 * The builder options mirror `com.android.tools.idea.preview.rendering.RenderTaskCreatorKt.createRenderTaskFuture`,
 * Android Studio's own standalone "render one compose preview element" path (no design surface). That function
 * takes its own `showDecorations: Boolean = false` parameter (name recovered from its class file's embedded
 * `LocalVariableTable` — this jar is not fully stripped) and calls `disableDecorations()` + `withRenderingMode(SHRINK)`
 * only when it is false; when true it calls neither. [renderResolved] now takes exactly that same branch on
 * [RenderModelResolver.Resolved.showDecorations] (PG4-2 ext, decorations): `false` (plain previews, the common
 * case) reproduces the PG2-2 fix byte-for-byte; `true` (`@Preview(showSystemUi = true)`) leaves the builder's own
 * defaults — `showDecorations = true` and a `null` rendering mode, which `RenderTask`'s own constructor defaults to
 * `NORMAL` (both confirmed on `RenderService$RenderTaskBuilder`/`RenderTask` by bytecode) — full device size with
 * system-UI chrome, the standard-preview look.
 */
class LiveRenderer(
    private val project: Project,
    private val resolver: RenderModelResolver = RenderModelResolver(),
) {

    fun isAvailable(): Boolean = RenderApiProbe.isAvailable()

    /**
     * Render [entry] to a [RenderOutcome]. Never throws (except cancellation). Blocks the calling (background)
     * thread on the render futures, each with a [TIMEOUT_MS] cap.
     *
     * [deviceOverride] (PG6-3) is an optional, plugin-owned device to render on instead of [entry]'s config-aware
     * `@Preview` device — forwarded to [RenderModelResolver.resolve] unchanged, which applies it to the
     * `Configuration` (guarded; degrades to the config-aware device on any failure). `null` (every caller today)
     * reproduces the exact pre-PG6-3 config-aware path.
     */
    fun render(entry: PreviewEntry, deviceOverride: DeviceOption? = null): RenderOutcome {
        if (!isAvailable()) {
            return RenderOutcome.Unsupported("Live rendering is unavailable on this IDE build")
        }
        return try {
            when (val result = resolver.resolve(entry, project, deviceOverride)) {
                is RenderModelResult.NoFacet ->
                    RenderOutcome.Unsupported("Module '${entry.moduleName}' has no Android facet")
                is RenderModelResult.Failed ->
                    RenderOutcome.Failure(result.message, result.detail)
                is RenderModelResult.Resolved ->
                    renderResolved(entry, result.model)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Render failed for ${entry.indexed.composableFqn}", e)
            RenderOutcome.Failure("Render failed", e.stackTraceToString())
        } catch (e: LinkageError) {
            thisLogger().warn("Render API mismatch for ${entry.indexed.composableFqn}", e)
            RenderOutcome.Failure("Render API is incompatible with this IDE build", e.stackTraceToString())
        }
    }

    private fun renderResolved(entry: PreviewEntry, model: RenderModelResolver.Resolved): RenderOutcome {
        // U2: the compose element serializes to a <androidx.compose.ui.tooling.ComposeViewAdapter
        //     tools:composableName="<fqn>" …> tag. layoutlib inflates that adapter, which reflectively invokes the
        //     composable. A RenderXmlFileSnapshot parses the string into a non-PSI RenderXmlFile the task can read
        //     (RenderTask.createRenderSession requires an XML file; withParserFactory alone is not the root path).
        val xml = model.element.toPreviewXml().buildString()
        val xmlFile = RenderXmlFileSnapshot(project, PREVIEW_FILE_NAME, ResourceFolderType.LAYOUT, xml)

        val renderService = StudioRenderService.getInstance(project)
        var builder = renderService
            .taskBuilder(model.renderModule, model.configuration, model.logger)
            .withPsiFile(xmlFile)

        // ── PG4-2 ext: the same condition AS's own createRenderTaskFuture branches this exact pair of calls on
        // (see the class doc) — whether the resolved preview element asked for decorations. ──
        if (!model.showDecorations) {
            // disableDecorations(): no system UI chrome; the ComposeViewAdapter tag is the render root. Without it
            // the builder default (showDecorations = true) wraps the composable in a device-sized decor, which is
            // what produced a device-sized, entirely empty frame (PG2-2). This is the common (plain-preview) case,
            // and reproduces that fix exactly — untouched.
            // SHRINK: size the rendered image to the measured content instead of to the device. With the default
            // (null → NORMAL) an unmeasured/uncomposed ComposeViewAdapter still yields a full-device blank canvas.
            builder = builder
                .disableDecorations()
                .withRenderingMode(SessionParams.RenderingMode.SHRINK)
        }
        // else (showSystemUi = true): call neither. The builder's own defaults — showDecorations = true, and a
        // null rendering mode that RenderTask's constructor defaults to NORMAL — are exactly what a standard,
        // decorated compose preview wants: full device size with the system-UI chrome drawn around the composable.

        val task = builder
            .withLayoutScanner(false)
            .doNotReportOutOfDateUserClasses()
            .disableSecurityManager() // headless render off the EDT; the sandbox blocks layoutlib class loading here
            .build()
            .getOrTimeout()
            ?: return RenderOutcome.Failure("Render task could not be created", loggerDetail(model))

        try {
            // ── 1. Inflate: build the view hierarchy and load the composable's classes. ──
            // AS checks this result and bails out (LayoutlibSceneRenderer.doRender); previously we discarded it,
            // so a failed inflation still fell through to a blank "successful" image.
            val inflated = task.inflate().getOrTimeout()
            if (inflated == null) {
                return RenderOutcome.Failure("Inflating the preview timed out", loggerDetail(model))
            }
            if (hasFailedStatus(inflated)) {
                return failure("Inflating the preview failed", inflated)
            }
            disposeQuietly(inflated)

            // ── 2. First render: mounts the ComposeViewAdapter and starts the composition. ──
            // This frame is intentionally thrown away — it is the one that used to be captured (and was blank).
            val firstPass = task.render().getOrTimeout()
            if (firstPass == null) {
                return RenderOutcome.Failure("The first render pass timed out", loggerDetail(model))
            }
            if (hasFailedStatus(firstPass)) {
                return failure("The first render pass failed", firstPass)
            }
            disposeQuietly(firstPass)

            // ── 3. Let Compose run its frame callbacks (recomposition, effects, the first animation frames). ──
            drainComposeCallbacks(task)

            // ── 4. Second render: captures the now-composed content. ──
            val result = task.render().getOrTimeout()
                ?: return RenderOutcome.Failure("Render produced no result", loggerDetail(model))
            if (hasFailedStatus(result)) {
                return failure("Render failed", result)
            }

            // U4: the ImagePool image is recycled on dispose(), so copy the pixels into a detached BufferedImage
            //     now, inside processImageIfNotDisposed, before the finally block runs.
            var image: BufferedImage? = null
            result.processImageIfNotDisposed { pooled -> image = pooled.getCopy() }
            val copied = image
                ?: return failure("Render produced no image", result)

            return verifySomethingWasDrawn(copied, result)
        } finally {
            // MANDATORY (design §5.1): release layoutlib render contexts / class loaders. Guarded so a dispose
            // failure never masks the real outcome.
            try {
                task.dispose()
            } catch (e: Exception) {
                thisLogger().warn("RenderTask.dispose() failed", e)
            } catch (e: LinkageError) {
                thisLogger().warn("RenderTask.dispose() failed", e)
            }
        }
    }

    /**
     * Mirrors `LayoutlibSceneRenderer.executeAllCallbacks()`: keep pumping layoutlib's callback queue until it
     * reports no more work, bounded by a wall-clock budget (AS uses `withTimeoutOrNull(100)`) and a round cap.
     *
     * The nanosecond argument is the frame clock. For a static preview AS supplies a `SteppingSessionClock` with a
     * 16 ms step, so every round advances the composition by exactly one frame — that is what lets Compose settle
     * deterministically instead of depending on wall-clock timing.
     */
    private fun drainComposeCallbacks(task: RenderTask) {
        val deadline = System.currentTimeMillis() + CALLBACK_BUDGET_MS
        var frameTimeNanos = 0L
        var round = 0
        while (round < MAX_CALLBACK_ROUNDS && System.currentTimeMillis() < deadline) {
            round++
            frameTimeNanos += FRAME_STEP_NANOS
            val callbacks = try {
                task.executeCallbacks(frameTimeNanos).getOrTimeout()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("RenderTask.executeCallbacks() failed; capturing the frame as-is", e)
                return
            } catch (e: LinkageError) {
                thisLogger().warn("RenderTask.executeCallbacks() is unavailable; capturing the frame as-is", e)
                return
            }
            if (callbacks == null || !callbacks.hasMoreCallbacks()) return
        }
    }

    /**
     * Honest blank detection. A composable that never composed still yields an image (layoutlib hands the canvas
     * back either way), so a non-null image alone is not evidence that anything was drawn. AS uses the same two
     * signals: `LayoutlibSceneRenderer.containsValidImage` requires width > 1 && height > 1, and a render with no
     * root views has nothing on screen.
     */
    private fun verifySomethingWasDrawn(image: BufferedImage, result: RenderResult): RenderOutcome {
        if (image.width <= 1 || image.height <= 1) {
            return failure("Render produced an empty ${image.width}x${image.height} image", result)
        }
        val rootViews = runCatching { result.rootViews.size }.getOrDefault(-1)
        if (rootViews == 0) {
            return failure("Render produced an empty view hierarchy", result)
        }
        if (RenderedImageInspector.isBlank(image)) {
            return failure("Render produced a blank ${image.width}x${image.height} frame", result)
        }
        // A valid image with logged problems is still a usable image, but the problems must not be swallowed.
        if (runCatching { result.logger.hasErrors() }.getOrDefault(false)) {
            thisLogger().warn("Preview rendered with layoutlib problems:\n${renderErrorDetail(result)}")
        }

        val viewTree = buildViewTree(result)
        return RenderOutcome.Success(image, viewTree)
    }

    /**
     * PG4-3: converts the render's raw layoutlib `ViewInfo` tree ([RenderResult.getRootViews]) into a plugin-owned
     * [PreviewViewNode] tree, via Android Studio's own Compose view-info parser ([parseViewInfo]) — the same
     * parser AS's own preview surface uses to resolve node bounds and `@Preview` source locations. Guarded like
     * every other AS-internal call here: probed first ([RenderApiProbe.isViewTreeAvailable]) so a build missing
     * the parser never attempts it, then wrapped against [Exception]/[LinkageError] so a shape change degrades to
     * an empty tree — the image itself is never lost to a tree-conversion failure (design §5.3). Runs OFF the EDT
     * and OFF any read action (this whole method already does, per PG3-6): [parseViewInfo] and every
     * [ComposeViewInfo]/`SourceLocation` accessor it produces read only plain in-memory fields (reflection over
     * the already-rendered Compose view objects, and `String`/`Int` getters), never PSI.
     *
     * `getRootViews()` returns a list because layoutlib's contract allows more than one top-level view, but a
     * single compose preview's bridge XML has exactly one root; every root present is parsed and flattened so no
     * node is silently dropped if that ever changes.
     */
    private fun buildViewTree(result: RenderResult): List<PreviewViewNode> {
        if (!RenderApiProbe.isViewTreeAvailable()) return emptyList()
        return try {
            result.rootViews
                .flatMap { rootView -> parseViewInfo(rootView, thisLogger()) }
                .map { it.toPreviewViewNode() }
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("View tree conversion failed", e)
            emptyList()
        } catch (e: LinkageError) {
            thisLogger().warn("View tree conversion API is incompatible with this IDE build", e)
            emptyList()
        }
    }

    /**
     * Recursively maps one AS [ComposeViewInfo] node into a plugin-owned [PreviewViewNode] (design §3.1: AS types
     * never leak past `render/`). [ComposeViewInfo.getSourceLocation] is never Kotlin-null (AS's constructor
     * requires it), but it can be `SourceLocation.isEmpty()` when AS could not resolve one — that maps to `null`
     * here, matching [PreviewViewNode.sourceLocation]'s contract. AS's `SourceLocation` exposes only
     * fileName/lineNumber/packageHash — no character offset — and `packageHash` is a package-name hash used to
     * disambiguate same-named files during navigation, not a text offset, so it is never substituted in;
     * [PreviewSourceLocation.offset] is always null from this path (task-3 report, V2).
     */
    private fun ComposeViewInfo.toPreviewViewNode(): PreviewViewNode {
        val location = sourceLocation
        return PreviewViewNode(
            bounds = Rectangle(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top),
            sourceLocation = if (location.isEmpty()) {
                null
            } else {
                // V1 (javap on design-tools.jar): SourceLocation.getPackageHash(): Int — a plain field getter on
                // SourceLocationImpl, so this cannot throw in practice; guarded anyway so a future shape change
                // degrades to null instead of breaking the whole tree conversion.
                val hash = runCatching { location.packageHash }.getOrNull()
                PreviewSourceLocation(location.fileName, location.lineNumber, null, hash)
            },
            children = children.map { it.toPreviewViewNode() },
        )
    }

    /**
     * The gate AS's `LayoutlibSceneRenderer.doRender` applies after each pass: `result.renderResult.isSuccess`.
     * Deliberately status-only — `RenderResultUtilKt.isErrorResult` additionally treats `logger.hasErrors()` as a
     * failure, which AS uses only to decide whether to cache an image, not whether the render is usable. Adopting
     * that here would reject perfectly good previews that merely logged a recoverable problem; those are surfaced
     * through the log warning in [verifySomethingWasDrawn] instead.
     */
    private fun hasFailedStatus(result: RenderResult): Boolean = runCatching {
        !result.renderResult.isSuccess
    }.getOrDefault(false)

    private fun disposeQuietly(result: RenderResult) {
        // Returns the pooled image of a discarded frame; AS does the same via setRenderResult(). Never fatal.
        runCatching { result.dispose() }
            .onFailure { thisLogger().debug("RenderResult.dispose() failed", it) }
    }

    private fun <T> CompletableFuture<T>.getOrTimeout(): T? =
        get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

    private fun failure(message: String, result: RenderResult): RenderOutcome.Failure =
        RenderOutcome.Failure(renderErrorSummary(message, result), renderErrorDetail(result))

    private fun renderErrorSummary(fallback: String, result: RenderResult): String {
        val message = runCatching { result.renderResult.errorMessage }.getOrNull()
        return if (message.isNullOrBlank()) fallback else "$fallback: $message"
    }

    private fun renderErrorDetail(result: RenderResult): String {
        val parts = mutableListOf<String>()
        val status = runCatching { result.renderResult.status?.toString() }.getOrNull()
        if (status != null) parts += "status=$status"

        val rootViews = runCatching { result.rootViews.size }.getOrNull()
        if (rootViews != null) parts += "rootViews=$rootViews"

        val logger = result.logger
        val missing = runCatching { logger.missingClasses }.getOrNull().orEmpty()
        if (missing.isNotEmpty()) parts += "missingClasses=$missing"
        val broken = runCatching { logger.brokenClasses.keys }.getOrNull().orEmpty()
        if (broken.isNotEmpty()) parts += "brokenClasses=$broken"
        val problems = runCatching { logger.messages.map { it.toString() } }.getOrNull().orEmpty()
        if (problems.isNotEmpty()) parts += "problems=$problems"

        val exception = runCatching { result.renderResult.exception }.getOrNull()
        if (exception != null) parts += "exception=${exception.stackTraceToString()}"

        return parts.joinToString(separator = "\n").ifBlank { "No further detail was reported by layoutlib." }
    }

    private fun loggerDetail(model: RenderModelResolver.Resolved): String =
        runCatching {
            val logger = model.logger
            buildString {
                append("missingClasses=").append(logger.missingClasses)
                append(" brokenClasses=").append(logger.brokenClasses.keys)
            }
        }.getOrDefault("No detail available.")

    companion object {
        /** Per-future render timeout (design §5.1 rule 4). Expiry becomes a [RenderOutcome.Failure], not a hang. */
        private const val TIMEOUT_MS = 30_000L

        /** Wall-clock budget for the whole callback drain. AS: `withTimeoutOrNull(100)` in executeAllCallbacks. */
        private const val CALLBACK_BUDGET_MS = 100L

        /** Hard cap on callback rounds so a composable that never settles cannot spin. */
        private const val MAX_CALLBACK_ROUNDS = 16

        /** One frame per round. AS: `SteppingSessionClock(16.milliseconds)` for a static preview. */
        private const val FRAME_STEP_NANOS = 16_000_000L

        private const val PREVIEW_FILE_NAME = "preview_gallery_render.xml"
    }
}
