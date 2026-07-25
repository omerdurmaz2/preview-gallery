package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project

// ── All imports below are Android Studio internal API (org.jetbrains.android / bundled render libs). ──
// This class, together with LiveRenderer, is the ONLY place that touches them (design §3.1).
import com.android.tools.configurations.Configuration
import com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.preview.DisplayPositioning
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.XmlSerializable
import com.android.tools.preview.applyTo
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.api.RenderModelModule
import org.jetbrains.android.facet.AndroidFacet

/**
 * Turns a [PreviewEntry] (its module + file) into everything [LiveRenderer] needs to build a layoutlib
 * `RenderTask`: the `(RenderModelModule, Configuration, RenderLogger)` triple plus the compose preview element
 * (an [XmlSerializable] that emits the `<ComposeViewAdapter tools:composableName=...>` bridge XML).
 *
 * Resolves design unknowns U1 (build-target reference) and U5 (default `PreviewConfiguration` /
 * `PreviewDisplaySettings`), plus V1 (PG4-2): the config-aware element built from the preview's own `@Preview`
 * arguments via Android Studio's own [AnnotationFilePreviewElementFinder] — see [findConfigAwareElement]. Every
 * AS-internal call is guarded against [Exception] and [LinkageError]; a signature change on a newer IDE degrades
 * to [RenderModelResult.Failed] (or, for the config-aware element specifically, to the pre-PG4-2 default element)
 * instead of throwing out of the render.
 */
class RenderModelResolver {

    /**
     * The pieces `RenderService.taskBuilder(...)` needs, plus the element that produces the layout XML.
     *
     * [showDecorations] is [element]'s own `displaySettings.showDecoration` (singular — the AS preview-element API
     * spelling; the AS render-task builder's own `showDecorations`/`disableDecorations()` are plural — both
     * spellings are AS's own, not a typo here). `LiveRenderer` branches its two builder options on this flag
     * (PG4-2 ext, decorations): `true` for a `@Preview(showSystemUi = true)` match, `false` for everything else,
     * including the default fallback element.
     */
    class Resolved(
        val renderModule: RenderModelModule,
        val configuration: Configuration,
        val logger: RenderLogger,
        val element: XmlSerializable,
        val showDecorations: Boolean,
    )

    sealed interface RenderModelResult {
        class Resolved(val model: RenderModelResolver.Resolved) : RenderModelResult

        /** The file's module has no Android facet — the caller maps this to `Unsupported`. */
        object NoFacet : RenderModelResult

        /** An AS-internal call failed — the caller maps this to `Failure`. */
        class Failed(val message: String, val detail: String?) : RenderModelResult
    }

    fun resolve(entry: PreviewEntry, project: Project): RenderModelResult =
        try {
            // Project-model / PSI access (module lookup, facet, configuration) must run under a read action.
            ReadAction.compute<RenderModelResult, RuntimeException> { resolveUnderReadAction(entry, project) }
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Render model resolution failed for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Could not prepare the render model", e.stackTraceToString())
        } catch (e: LinkageError) {
            thisLogger().warn("Render model API mismatch for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Render API is incompatible with this IDE build", e.stackTraceToString())
        }

    private fun resolveUnderReadAction(entry: PreviewEntry, project: Project): RenderModelResult {
        // U1: module → AndroidFacet → AndroidBuildTargetReference → AndroidFacetRenderModelModule.
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file)
            ?: return RenderModelResult.Failed("File is not part of any module", entry.file.path)
        val facet = AndroidFacet.getInstance(module)
            ?: return RenderModelResult.NoFacet
        val buildTarget = AndroidBuildTargetReference.from(facet, entry.file)
        val renderModule = AndroidFacetRenderModelModule(buildTarget)

        // The Configuration (device, theme, locale, target SDK) derived from the composable's own source file.
        val configuration = ConfigurationManager.getOrCreateInstance(module).getConfiguration(entry.file)

        // A logger scoped to this project; layoutlib records missing/broken classes and render problems on it.
        val logger = StudioRenderService.getInstance(project).createLogger(project)

        // U5 + the compose element whose toPreviewXml() drives the ComposeViewAdapter bridge (U2, consumed later).
        val element = buildPreviewElement(entry, project, configuration)

        // PG4-2 ext: whether this element's own @Preview asked for system-UI chrome (showSystemUi), so LiveRenderer
        // can render WITH decorations instead of always shrink-to-content. Guarded on its own: a signature change on
        // a newer IDE degrades this one flag to false (today's plain-preview behavior), not the whole resolution.
        val showDecorations = runCatching { element.displaySettings.showDecoration }.getOrDefault(false)

        return RenderModelResult.Resolved(Resolved(renderModule, configuration, logger, element, showDecorations))
    }

    /**
     * V1 (PG4-2): tries the config-aware element first ([findConfigAwareElement]); [buildDefaultPreviewElement] is
     * the fallback whenever the finder is unavailable, finds no match, resolves to a parametrized template rather
     * than a single instance, or throws (spec R1). The fallback branch mutates nothing new — [configuration] is
     * left exactly as [resolveUnderReadAction] already built it, so a miss renders exactly as it did before PG4-2.
     */
    private fun buildPreviewElement(
        entry: PreviewEntry,
        project: Project,
        configuration: Configuration,
    ): SingleComposePreviewElementInstance<*> =
        findConfigAwareElement(entry, project, configuration) ?: buildDefaultPreviewElement(entry)

    /**
     * Asks Android Studio's own [AnnotationFilePreviewElementFinder] for [entry]'s file's real `@Preview`
     * elements and returns the one matching [entry] by composable FQN + display name — carrying that `@Preview`'s
     * own device/api/size/showSystemUi, applied onto [configuration] below. Returns `null` (so the caller falls
     * back to [buildDefaultPreviewElement] unchanged) when:
     *  - the probe ([RenderApiProbe.isConfigAwareAvailable]) says the finder is not present on this IDE build —
     *    checked first so an IDE build that lacks it never pays for the read-action + suspend-bridge round trip;
     *  - the finder returns no element whose `methodFqn` AND `displaySettings.name` both match [entry.indexed]'s
     *    `composableFqn`/`displayName`;
     *  - the match is a `ParametrizedComposePreviewElementTemplate` (a `@PreviewParameter` or multipreview group)
     *    rather than an already-resolved single instance — not `XmlSerializable`, and not what one [PreviewEntry]
     *    represents (spec R1); the `as?` below returns `null` for it precisely because that template class does
     *    NOT extend `SingleComposePreviewElementInstance`;
     *  - the finder call, the match, or applying its configuration throws — guarded against [Exception] and
     *    [LinkageError] (logged once, at `info` — this is an expected degrade, not a broken feature).
     *
     * ## The suspend bridge
     *
     * [AnnotationFilePreviewElementFinder.findPreviewElements] is `suspend`. [runBlockingCancellable]
     * (`com.intellij.openapi.progress`, not `com.intellij.openapi.application`) is the platform's own bridge for
     * calling a suspend function from ordinary blocking code — this whole method runs inside the read action
     * [resolve] already took. Verified safe for exactly this call shape by decompiling it
     * (`com.intellij.openapi.progress.CoroutinesKt`, this IDE's `lib/util-8.jar`): its only hard precondition is
     * a private `assertBackgroundThreadAndNoWriteAction()` — on the EDT it only *logs* (never throws) unless a
     * write action is active too, and this method never runs on the EDT ([RenderPipeline] always calls into
     * [LiveRenderer] from a background executor, never `invokeLater`). It is explicitly not forbidden under a
     * *read* action: the calling thread's lock state is captured into the coroutine context
     * `runBlockingCancellable` runs on (`CoroutinesKt.getLockContext`/`getLockPermitContext`), so the finder's own
     * internal `smartReadAction`/`readAction` suspend calls see read access already permitted and run inline
     * rather than trying to acquire the lock on a different thread — which is what could deadlock. This is a
     * bytecode-level inference, not a live trace; the runIde gate is the final proof there is no hang.
     *
     * Once matched, the [applyTo] extension (`ConfigurablePreviewElement.applyTo`) pushes the element's own
     * device/api/locale/size onto [configuration] — the same `Configuration` `LiveRenderer` later hands to
     * `RenderService.taskBuilder`. Called only for a genuine match, never on the fallback path.
     */
    private fun findConfigAwareElement(
        entry: PreviewEntry,
        project: Project,
        configuration: Configuration,
    ): SingleComposePreviewElementInstance<*>? {
        if (!RenderApiProbe.isConfigAwareAvailable()) return null
        return try {
            val elements = runBlockingCancellable {
                AnnotationFilePreviewElementFinder.findPreviewElements(project, entry.file)
            }
            val matched = elements.firstOrNull { element ->
                element.methodFqn == entry.indexed.composableFqn &&
                    element.displaySettings.name == entry.indexed.displayName
            }
            val single = matched as? SingleComposePreviewElementInstance<*> ?: return null
            single.applyTo(configuration)
            single
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().info(
                "Config-aware preview element lookup failed for ${entry.indexed.composableFqn}; " +
                    "using the default configuration",
                e,
            )
            null
        } catch (e: LinkageError) {
            thisLogger().info(
                "Config-aware preview element API is incompatible with this IDE build; using the default configuration",
                e,
            )
            null
        }
    }

    /**
     * U5: builds a [SingleComposePreviewElementInstance] with a default configuration. `cleanAndGet(null…)` fills
     * every field (api level, size, locale, ui-mode, device) with layoutlib's defaults — the "no `@Preview`
     * arguments given" case. Display settings carry only naming metadata; `previewWrapperProviderFqn` is null
     * because this path is for plain (non-`@PreviewParameter`) composables. The fallback target for
     * [findConfigAwareElement] (PG4-2), and — before PG4-2 — the only path this class had.
     */
    private fun buildDefaultPreviewElement(entry: PreviewEntry): SingleComposePreviewElementInstance<*> {
        val previewConfiguration = PreviewConfiguration.cleanAndGet(
            /* apiLevel = */ null,
            /* width = */ null,
            /* height = */ null,
            /* locale = */ null,
            /* fontScale = */ null,
            /* uiMode = */ null,
            /* deviceSpec = */ null,
            /* wallpaper = */ null,
            /* colorBlindImageTransformation = */ null,
        )

        val displaySettings = PreviewDisplaySettings(
            /* name = */ entry.indexed.displayName,
            /* baseName = */ entry.indexed.functionName,
            /* parameterName = */ null,
            /* group = */ entry.indexed.previewGroup,
            /* showDecoration = */ false,
            /* background = */ PreviewDisplaySettings.Background.Default,
            /* displayPositioning = */ DisplayPositioning.NORMAL,
            /* organizationGroup = */ "",
            /* organizationName = */ null,
        )

        return SingleComposePreviewElementInstance<Any?>(
            /* methodFqn = */ entry.indexed.composableFqn,
            /* displaySettings = */ displaySettings,
            /* previewElementDefinition = */ null,
            /* previewBody = */ null,
            /* configuration = */ previewConfiguration,
            /* previewWrapperProviderFqn = */ null,
        )
    }
}
