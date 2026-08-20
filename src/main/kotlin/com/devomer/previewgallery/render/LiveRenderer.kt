package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.model.ViewOverride
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
import com.android.tools.preview.XmlSerializable
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
     * [override] (PG6-9, widened from the three-axis config to a full name→value property map) is an optional,
     * plugin-owned override for one comparison copy — forwarded to [RenderModelResolver.resolve] unchanged.
     * `null`, or a default ([ViewOverride.isDefault]), override reproduces the exact config-aware path; applying
     * a non-default override's values onto the `Configuration` is not wired up yet (a later task).
     *
     * [moduleWrapper] (PG22-3) is forwarded to [RenderModelResolver.resolve] unchanged and applied there, before
     * [renderResolved] ever sees the resolved model — this method has nothing else to do for it. `null` (every
     * caller before this task) reproduces today's render exactly.
     */
    fun render(
        entry: PreviewEntry,
        override: ViewOverride? = null,
        moduleWrapper: RenderModuleWrapper? = null,
    ): RenderOutcome = when (
        val result = renderInstance(entry, override, moduleWrapper, requiredVariant = null, pinCalibrationDevice = false)
    ) {
        is VariantRenderResult.Rendered -> result.render.outcome
        is VariantRenderResult.VariantUnresolved, is VariantRenderResult.DeviceSpecUnresolved ->
            RenderOutcome.Failure("Render failed", null)
    }

    /**
     * PG22-8: renders the `@Preview` instance named [variantName] — nothing else — pinned to the calibration's own
     * device (D6a, [RenderModelResolver.resolve]'s `pinCalibrationDevice = true`) so its `Configuration` matches
     * the device the committed goldens were drawn on rather than whatever the module's own defaults would produce.
     *
     * [VariantRenderResult.VariantUnresolved] and [VariantRenderResult.DeviceSpecUnresolved] are stops, not a
     * failure to describe: the calibration compares against a variant-specific golden rendered on a specific
     * device, so a render whose variant is unknown, or whose device could not be pinned, is worse than no render
     * at all (spec D3, D6a). The caller publishes its own stop-and-report message for each and produces no number;
     * it must not treat either as "render it some other way".
     *
     * No [ViewOverride] parameter: an override is a comparison-view concept, and the calibration renders exactly
     * what the source declares — device pinning is not an override in that sense, since it is never user-supplied
     * and never optional for this entry point.
     *
     * D3a: [VariantRenderResult.Rendered.render] carries [RenderModelResolver.Resolved.variantAssumed] — set when
     * the resolver rendered the default element in place of a `phone` instance the finder could not name at all —
     * so it can reach [PreviewGalleryPanel.compareOffEdt], the one caller. Widening [RenderOutcome] itself to carry
     * this would leak a calibration-only concept into every other render path in the plugin; this one-caller
     * wrapper is the smaller change.
     */
    fun renderVariant(
        entry: PreviewEntry,
        variantName: String,
        moduleWrapper: RenderModuleWrapper? = null,
    ): VariantRenderResult = renderInstance(
        entry,
        override = null,
        moduleWrapper = moduleWrapper,
        requiredVariant = variantName,
        pinCalibrationDevice = true,
    )

    /**
     * D3a: [render]'s and [renderVariant]'s shared payload — the [RenderOutcome] both already produced, plus
     * whether the resolver had to assume the `phone` variant rather than confirm it by name
     * ([RenderModelResolver.Resolved.variantAssumed]). [render] itself only ever unwraps [outcome]; [variantAssumed]
     * exists for [renderVariant]'s one caller, which must not present an assumed render's size mismatch as an
     * engine disagreement (spec D3a, decision table).
     */
    class VariantRender(val outcome: RenderOutcome, val variantAssumed: Boolean)

    /**
     * [renderInstance]'s own result, shared by [render] and [renderVariant]: a produced [VariantRender], or one of
     * the two named stops [RenderModelResolver.resolve] can report before any render is attempted —
     * [VariantUnresolved] when a non-null `requiredVariant` could not be named (D3a), [DeviceSpecUnresolved] when a
     * requested calibration device pin could not be applied (D6a). [render] passes no `requiredVariant` and never
     * asks for the pin, so neither stop is reachable through it — it is written to handle them anyway, defensively,
     * rather than assume, the same discipline [renderInstance]'s own doc already held for a bare `null`.
     */
    sealed interface VariantRenderResult {
        class Rendered(val render: VariantRender) : VariantRenderResult
        object VariantUnresolved : VariantRenderResult
        object DeviceSpecUnresolved : VariantRenderResult
    }

    /** The body both entry points share. */
    private fun renderInstance(
        entry: PreviewEntry,
        override: ViewOverride?,
        moduleWrapper: RenderModuleWrapper?,
        requiredVariant: String?,
        pinCalibrationDevice: Boolean,
    ): VariantRenderResult {
        if (!isAvailable()) {
            return VariantRenderResult.Rendered(
                VariantRender(RenderOutcome.Unsupported("Live rendering is unavailable on this IDE build"), false),
            )
        }
        return try {
            when (
                val result =
                    resolver.resolve(entry, project, override, moduleWrapper, requiredVariant, pinCalibrationDevice)
            ) {
                is RenderModelResult.NoFacet ->
                    VariantRenderResult.Rendered(
                        VariantRender(RenderOutcome.Unsupported("Module '${entry.moduleName}' has no Android facet"), false),
                    )
                is RenderModelResult.VariantUnresolved -> {
                    thisLogger().info(
                        "No '$requiredVariant' preview instance for ${entry.indexed.composableFqn}; nothing rendered",
                    )
                    VariantRenderResult.VariantUnresolved
                }
                is RenderModelResult.DeviceSpecUnresolved -> {
                    thisLogger().info(
                        "Could not pin the calibration device for ${entry.indexed.composableFqn}; nothing rendered",
                    )
                    VariantRenderResult.DeviceSpecUnresolved
                }
                is RenderModelResult.Failed ->
                    VariantRenderResult.Rendered(VariantRender(RenderOutcome.Failure(result.message, result.detail), false))
                is RenderModelResult.Resolved ->
                    VariantRenderResult.Rendered(
                        VariantRender(renderResolved(entry, result.model), result.model.variantAssumed),
                    )
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Render failed for ${entry.indexed.composableFqn}", e)
            VariantRenderResult.Rendered(
                VariantRender(RenderOutcome.Failure("Render failed", e.stackTraceToString()), false),
            )
        } catch (e: LinkageError) {
            thisLogger().warn("Render API mismatch for ${entry.indexed.composableFqn}", e)
            VariantRenderResult.Rendered(
                VariantRender(
                    RenderOutcome.Failure("Render API is incompatible with this IDE build", e.stackTraceToString()),
                    false,
                ),
            )
        }
    }

    /**
     * PG24: one composable can now resolve to more than one instance — a `@PreviewParameter` preview renders once
     * per value its provider yields ([RenderModelResolver.Resolved.instances]). A single instance takes the exact
     * pre-PG24 path and produces the same [RenderOutcome.Success]; more than one produces a
     * [RenderOutcome.MultiSuccess] the panel shows as a stacked strip.
     *
     * **One `RenderTask` per instance**, deliberately, rather than re-using one task through `setXmlFile`: a task
     * carries an inflated view hierarchy and a class loader, and driving the same one through several composables
     * would make each render depend on what the previous one left behind. The cost is bounded by
     * [RenderModelResolver.MAX_PARAMETER_INSTANCES], which is why it is affordable.
     *
     * A failing instance does not fail the set. Rendering 8 of 10 values and saying so beats showing nothing
     * because the tenth threw — but a set where *nothing* rendered reports the first instance's own failure,
     * which is the message that explains why.
     */
    private fun renderResolved(entry: PreviewEntry, model: RenderModelResolver.Resolved): RenderOutcome {
        val instances = model.instances
        val single = instances.singleOrNull()
        if (single != null) return renderInstance(entry, model, single.element)

        val rendered = instances.map { it.label to renderInstance(entry, model, it.element) }
        return combineInstanceRenders(rendered, "Nothing to render for ${entry.indexed.composableFqn}")
    }

    private fun renderInstance(
        entry: PreviewEntry,
        model: RenderModelResolver.Resolved,
        element: XmlSerializable,
    ): RenderOutcome {
        // U2: the compose element serializes to a <androidx.compose.ui.tooling.ComposeViewAdapter
        //     tools:composableName="<fqn>" …> tag. layoutlib inflates that adapter, which reflectively invokes the
        //     composable. A RenderXmlFileSnapshot parses the string into a non-PSI RenderXmlFile the task can read
        //     (RenderTask.createRenderSession requires an XML file; withParserFactory alone is not the root path).
        val xml = element.toPreviewXml().buildString()
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
            ?: return logged(RenderOutcome.Failure("Render task could not be created", loggerDetail(model)))

        try {
            // ── 1. Inflate: build the view hierarchy and load the composable's classes. ──
            // AS checks this result and bails out (LayoutlibSceneRenderer.doRender); previously we discarded it,
            // so a failed inflation still fell through to a blank "successful" image.
            val inflated = task.inflate().getOrTimeout()
            if (inflated == null) {
                return logged(RenderOutcome.Failure("Inflating the preview timed out", loggerDetail(model)))
            }
            if (hasFailedStatus(inflated)) {
                return failure("Inflating the preview failed", inflated)
            }
            disposeQuietly(inflated)

            // ── 2. First render: mounts the ComposeViewAdapter and starts the composition. ──
            // This frame is intentionally thrown away — it is the one that used to be captured (and was blank).
            val firstPass = task.render().getOrTimeout()
            if (firstPass == null) {
                return logged(RenderOutcome.Failure("The first render pass timed out", loggerDetail(model)))
            }
            if (hasFailedStatus(firstPass)) {
                return failure("The first render pass failed", firstPass)
            }
            disposeQuietly(firstPass)

            // ── 3. Let Compose run its frame callbacks (recomposition, effects, the first animation frames). ──
            drainComposeCallbacks(task)

            // ── 4. Second render: captures the now-composed content. ──
            val result = task.render().getOrTimeout()
                ?: return logged(RenderOutcome.Failure("Render produced no result", loggerDetail(model)))
            if (hasFailedStatus(result)) {
                return failure("Render failed", result)
            }

            // U4: the ImagePool image is recycled on dispose(), so copy the pixels into a detached BufferedImage
            //     now, inside processImageIfNotDisposed, before the finally block runs.
            var image: BufferedImage? = null
            result.processImageIfNotDisposed { pooled -> image = pooled.getCopy() }
            val copied = image
                ?: return failure("Render produced no image", result)

            return verifySomethingWasDrawn(copied, result, renderDpi(task, model))
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
    private fun verifySomethingWasDrawn(image: BufferedImage, result: RenderResult, dpi: Int): RenderOutcome {
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
        return RenderOutcome.Success(image, viewTree, dpi)
    }

    /**
     * The density layoutlib actually rendered at, so the UI can draw the preview at dp size the way Android
     * Studio's own design surface does (PG12-2).
     *
     * The primary source is the task's own `HardwareConfig`: `RenderTask`'s constructor builds it as
     * `new HardwareConfigHelper(device)` (verified on `RenderTask` bytecode in
     * `plugins/android/lib/android.jar`), so this is the value the render used, not a re-derivation from the
     * device. `getHardwareConfigHelper()`, `HardwareConfigHelper.getConfig()` and `HardwareConfig.getDensity()`
     * are all public API on that build.
     *
     * The first fallback is the `Configuration`'s own density qualifier, which `Configuration.getDensity()`
     * reads off the same device state through `FolderConfiguration` (and which itself falls back to
     * `Density.MEDIUM`). The last resort is [RenderOutcome.DEFAULT_DPI], at which the dp conversion is the
     * identity — a guard failure therefore degrades to the pre-PG12 raw-pixel display, never to a wrong size.
     *
     * Guarded with `runCatching` like the other optional AS reads in this file (see the `showDecoration` read in
     * [RenderModelResolver]): both calls are plain in-memory field getters that cannot raise
     * [ProcessCanceledException], so there is nothing here that must be allowed to propagate.
     */
    private fun renderDpi(task: RenderTask, model: RenderModelResolver.Resolved): Int {
        val fromTask = runCatching { task.hardwareConfigHelper.config.density.dpiValue }.getOrNull()
        if (fromTask != null && fromTask > 0) return fromTask
        val fromConfiguration = runCatching { model.configuration.density.dpiValue }.getOrNull()
        if (fromConfiguration != null && fromConfiguration > 0) return fromConfiguration
        thisLogger().info("Render density unavailable; the preview will display at raw render pixels")
        return RenderOutcome.DEFAULT_DPI
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

    /**
     * PG24-4: **every render failure is logged, not only shown.**
     *
     * The pane has carried layoutlib's own diagnosis behind its Details link since PG2, and nothing wrote it
     * anywhere else — so a user reporting "Render produced no image" left no trace in `idea.log` at all, and the
     * only way to see the reason was to have them copy it out of the dialog by hand. Every render failure now
     * lands in the log with the same summary and detail the pane shows.
     *
     * `warn`, not `info`: a preview that failed to render is a defect in either the composable or this plugin,
     * and the log level is what decides whether it survives to the report.
     */
    private fun failure(message: String, result: RenderResult): RenderOutcome.Failure =
        logged(RenderOutcome.Failure(renderErrorSummary(message, result), renderErrorDetail(result)))

    /** [failure]'s logging, for the failures built without a [RenderResult] to interrogate — a task that could
     *  not be created, or a pass that timed out, whose detail is [loggerDetail]'s instead. */
    private fun logged(failure: RenderOutcome.Failure): RenderOutcome.Failure {
        thisLogger().warn("Render failed: ${failure.message}\n${failure.detail.orEmpty()}")
        return failure
    }

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

        /**
         * PG24: what a set of per-instance renders adds up to — the pure half of [renderResolved]'s
         * `@PreviewParameter` branch, extracted so the rule is testable without Android Studio (the same split
         * [RenderModelResolver.decideVariantResolution] draws for its own decision).
         *
         * **A failing instance does not fail the set.** Eight values rendered out of ten, each labelled, is worth
         * more than nothing at all because the tenth threw; the set that comes back is the set that rendered, in
         * the provider's own order.
         *
         * **A set where nothing rendered reports the first failure it saw**, not a summary of its own: that
         * failure carries layoutlib's own message and detail, which is what explains why the preview is blank.
         * [fallbackMessage] covers only the case with no failures to report either — an empty instance list,
         * which callers should not produce and this function must still answer.
         *
         * The dpi is the last successful render's. They are renders of one composable on one [Configuration], so
         * they agree; taking one rather than asserting they do keeps a disagreement from failing the whole set.
         */
        internal fun combineInstanceRenders(
            rendered: List<Pair<String, RenderOutcome>>,
            fallbackMessage: String,
        ): RenderOutcome {
            val successes = rendered.mapNotNull { (label, outcome) ->
                (outcome as? RenderOutcome.Success)?.let { label to it }
            }
            if (successes.isNotEmpty()) {
                return RenderOutcome.MultiSuccess(
                    successes.map { (label, success) -> RenderOutcome.LabelledRender(label, success.image) },
                    successes.last().second.dpi,
                )
            }
            return rendered.firstNotNullOfOrNull { it.second as? RenderOutcome.Failure }
                ?: RenderOutcome.Failure(fallbackMessage, null)
        }

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
