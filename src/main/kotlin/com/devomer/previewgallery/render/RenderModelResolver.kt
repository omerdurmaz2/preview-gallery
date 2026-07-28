package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ViewOverride
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
 *
 * PG6-9: also accepts an optional, plugin-owned [ViewOverride] for comparison views — a name→value property map
 * from Android Studio's own `@Preview` picker. This resolver does not yet apply it to the `Configuration`; the
 * parameter is threaded through so the signature is ready for the task that derives a preview element from it. A
 * `null` or default ([ViewOverride.isDefault]) override leaves this class's behaviour byte-for-byte unchanged,
 * exactly like before.
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

    fun resolve(entry: PreviewEntry, project: Project, override: ViewOverride? = null): RenderModelResult =
        try {
            // The config-aware element is fetched FIRST, lock-free (see [findConfigAwareElement]): its finder is a
            // suspend function that acquires its OWN (smart) read access, and calling it while we already hold the
            // synchronous read lock below made it wait for smart-mode / write-intent the starved EDT could never
            // grant — pinning the read lock and freezing the whole IDE (observed: a 65s UI freeze on select). Only
            // the project-model reads that genuinely need a read action run inside ReadAction.compute below, which
            // applies the already-fetched element's own @Preview configuration.
            val configAware = findConfigAwareElement(entry, project)
            ReadAction.compute<RenderModelResult, RuntimeException> {
                resolveUnderReadAction(entry, project, configAware, override)
            }
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Render model resolution failed for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Could not prepare the render model", e.stackTraceToString())
        } catch (e: LinkageError) {
            thisLogger().warn("Render model API mismatch for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Render API is incompatible with this IDE build", e.stackTraceToString())
        }

    private fun resolveUnderReadAction(
        entry: PreviewEntry,
        project: Project,
        configAware: SingleComposePreviewElementInstance<*>?,
        override: ViewOverride?,
    ): RenderModelResult {
        // U1: module → AndroidFacet → AndroidBuildTargetReference → AndroidFacetRenderModelModule.
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file)
            ?: return RenderModelResult.Failed("File is not part of any module", entry.file.path)
        val facet = AndroidFacet.getInstance(module)
            ?: return RenderModelResult.NoFacet
        val buildTarget = AndroidBuildTargetReference.from(facet, entry.file)
        val renderModule = AndroidFacetRenderModelModule(buildTarget)

        // The Configuration (device, theme, locale, target SDK) derived from the composable's own source file.
        val configurationManager = ConfigurationManager.getOrCreateInstance(module)
        val configuration = configurationManager.getConfiguration(entry.file)

        // A logger scoped to this project; layoutlib records missing/broken classes and render problems on it.
        val logger = StudioRenderService.getInstance(project).createLogger(project)

        // U5 + the compose element whose toPreviewXml() drives the ComposeViewAdapter bridge (U2, consumed later).
        // [configAware] was resolved lock-free by [findConfigAwareElement] before this read action; applying its own
        // device/api/size/showSystemUi onto [configuration] is the only part of the config-aware path that needs a
        // read action. A null means the finder was unavailable, found no single-instance match, or threw — fall
        // back to the default-config element, exactly as before PG4-2.
        val element: SingleComposePreviewElementInstance<*> =
            if (configAware != null && applyConfigAware(configAware, configuration)) {
                configAware
            } else {
                buildDefaultPreviewElement(entry)
            }

        // PG4-2 ext: whether this element's own @Preview asked for system-UI chrome (showSystemUi), so LiveRenderer
        // can render WITH decorations instead of always shrink-to-content. Guarded on its own: a signature change on
        // a newer IDE degrades this one flag to false (today's plain-preview behavior), not the whole resolution.
        val showDecorations = runCatching { element.displaySettings.showDecoration }.getOrDefault(false)

        return RenderModelResult.Resolved(Resolved(renderModule, configuration, logger, element, showDecorations))
    }

    /**
     * Applies the config-aware element's own device/api/size/showSystemUi onto [configuration] (needs a read
     * action — the caller already holds one). Returns `false` if AS's `applyTo` throws, so a config-aware apply
     * failure degrades to the default-config render (spec §6 D4: the config-aware feature never breaks base
     * rendering) instead of becoming a hard [RenderModelResult.Failed] via [resolve]'s outer catch.
     */
    private fun applyConfigAware(
        element: SingleComposePreviewElementInstance<*>,
        configuration: Configuration,
    ): Boolean = try {
        element.applyTo(configuration)
        true
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().info("Applying the config-aware @Preview configuration failed; using the default", e)
        false
    } catch (e: LinkageError) {
        thisLogger().info("The config-aware apply API is incompatible with this IDE build; using the default", e)
        false
    }

    /**
     * Asks Android Studio's own [AnnotationFilePreviewElementFinder] for [entry]'s file's real `@Preview`
     * elements and returns the one matching [entry] by composable FQN + display name — carrying that `@Preview`'s
     * own device/api/size/showSystemUi (which the caller, [resolveUnderReadAction], applies onto the
     * `Configuration` under its read action). Returns `null` (so the caller falls back to
     * [buildDefaultPreviewElement] unchanged) when:
     *  - the probe ([RenderApiProbe.isConfigAwareAvailable]) says the finder is not present on this IDE build —
     *    checked first so an IDE build that lacks it never pays for the read-action + suspend-bridge round trip;
     *  - the finder returns no element whose `methodFqn` AND `displaySettings.name` both match [entry.indexed]'s
     *    `composableFqn`/`displayName`;
     *  - the match is a `ParametrizedComposePreviewElementTemplate` (a `@PreviewParameter` or multipreview group)
     *    rather than an already-resolved single instance — not `XmlSerializable`, and not what one [PreviewEntry]
     *    represents (spec R1); the `as?` below returns `null` for it precisely because that template class does
     *    NOT extend `SingleComposePreviewElementInstance`;
     *  - the finder call or the match throws — guarded against [Exception] and [LinkageError] (logged once, at
     *    `info` — this is an expected degrade, not a broken feature; applying the config is guarded separately in
     *    [applyConfigAware]).
     *
     * ## The suspend bridge — deliberately OUTSIDE any read action
     *
     * [AnnotationFilePreviewElementFinder.findPreviewElements] is `suspend`; [runBlockingCancellable]
     * (`com.intellij.openapi.progress`) bridges it to this blocking, background-thread call. It MUST NOT run while
     * a synchronous read lock is held. The finder does its own internal `smartReadAction`, which waits for smart
     * mode and for the EDT to grant write-intent; if we hold the read lock meanwhile, the EDT can never acquire
     * write-intent, so it starves and the whole IDE freezes — a real 65-second UI freeze was observed doing
     * exactly that (the read lock came from an outer `ReadAction.compute` this call used to sit inside). So
     * [resolve] now calls this BEFORE it opens its read action, lock-free: the finder acquires and releases its
     * own read access as a well-behaved suspend function, and the matched element's own device/api/size is applied
     * onto the `Configuration` afterwards, inside the short read action in [resolveUnderReadAction].
     *
     * `runBlockingCancellable` here blocks only this background render thread — never the EDT ([RenderPipeline]
     * always calls [LiveRenderer] off the EDT) — so a slow finder only makes one render take longer; it can no
     * longer freeze the IDE. Guarded against [Exception]/[LinkageError] → `null` → the default-config fallback.
     */
    private fun findConfigAwareElement(
        entry: PreviewEntry,
        project: Project,
    ): SingleComposePreviewElementInstance<*>? {
        if (!RenderApiProbe.isConfigAwareAvailable()) return null
        return try {
            val elements = runBlockingCancellable {
                AnnotationFilePreviewElementFinder.findPreviewElements(project, entry.file)
            }
            // Reads only String properties of the finder's already-resolved snapshot elements (no live PSI), so it
            // is safe off a read action; the config-aware element itself is applied under one in the caller.
            elements.firstOrNull { element ->
                element.methodFqn == entry.indexed.composableFqn &&
                    element.displaySettings.name == entry.indexed.displayName
            } as? SingleComposePreviewElementInstance<*>
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
