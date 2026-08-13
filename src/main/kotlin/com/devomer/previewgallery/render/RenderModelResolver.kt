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
 * PG6-9/PG6-10: also accepts an optional, plugin-owned [ViewOverride] for comparison views — a name→value
 * property map from Android Studio's own `@Preview` picker (the real, source-editing picker for Original;
 * [EphemeralPickerBridge]'s in-memory one for a copy). [applyOverride] applies it by deriving a new preview
 * element via AS's own `createDerivedInstance` and running the existing `applyTo` path against it (design D5),
 * merging every axis the user did NOT edit from the base configuration first ([mergeConfiguration] /
 * [OverrideMerge] — spec V4's `cleanAndGet` trap). A `null` or default ([ViewOverride.isDefault]) override
 * leaves this class's behaviour byte-for-byte unchanged, exactly like before PG6-9.
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

    /**
     * PG22-3: [moduleWrapper], when non-null, wraps the [AndroidFacetRenderModelModule] this method would
     * otherwise build directly — the seam a `screenshotTest` classpath injection composes onto (see
     * [ScreenshotTestClassLoader]). A null wrapper (every caller before this task, and `Original`'s own render)
     * reproduces the exact pre-PG22-3 path: [resolveUnderReadAction] builds the same
     * [AndroidFacetRenderModelModule] either way.
     */
    fun resolve(
        entry: PreviewEntry,
        project: Project,
        override: ViewOverride? = null,
        moduleWrapper: RenderModuleWrapper? = null,
    ): RenderModelResult =
        try {
            // The config-aware element is fetched FIRST, lock-free (see [findConfigAwareElement]): its finder is a
            // suspend function that acquires its OWN (smart) read access, and calling it while we already hold the
            // synchronous read lock below made it wait for smart-mode / write-intent the starved EDT could never
            // grant — pinning the read lock and freezing the whole IDE (observed: a 65s UI freeze on select). Only
            // the project-model reads that genuinely need a read action run inside ReadAction.compute below, which
            // applies the already-fetched element's own @Preview configuration.
            val configAware = findConfigAwareElement(entry, project)
            ReadAction.compute<RenderModelResult, RuntimeException> {
                resolveUnderReadAction(entry, project, configAware, override, moduleWrapper)
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
        moduleWrapper: RenderModuleWrapper? = null,
    ): RenderModelResult {
        // U1: module → AndroidFacet → AndroidBuildTargetReference → AndroidFacetRenderModelModule.
        // PG11-1: the facet is resolved through [AndroidModuleResolver], not `AndroidFacet.getInstance(module)`
        // directly — a `@Preview` in a Kotlin Multiplatform *common* source set belongs to a module that has no
        // facet at all; the Android target's own module does. See that object's doc for the Android Studio path
        // this mirrors.
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file)
            ?: return RenderModelResult.Failed("File is not part of any module", entry.file.path)
        val facet = AndroidModuleResolver.androidFacet(module)
            ?: return RenderModelResult.NoFacet
        val buildTarget = AndroidBuildTargetReference.from(facet, entry.file)
        val renderModule = moduleWrapper?.invoke(AndroidFacetRenderModelModule(buildTarget))
            ?: AndroidFacetRenderModelModule(buildTarget)

        // The Configuration (device, theme, locale, target SDK) derived from the composable's own source file.
        // Keyed on the *facet's* module, which is what AS's own updatePreviewsAndRefresh does
        // (`ConfigurationManager.getOrCreateInstance(facet.module)`) — and must be, since a KMP common source
        // set has no Android SDK/resources of its own to configure against.
        val configurationManager = ConfigurationManager.getOrCreateInstance(facet.module)
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

        // PG6-10: a non-default comparison-view override — from EphemeralPickerBridge's in-memory picker for a
        // copy; always null/default for Original — is applied on top of whichever element the config-aware/
        // default resolution above produced. A null or default override returns [element] itself, unchanged, so
        // Original's render is byte-for-byte identical to before PG6-9/PG6-10.
        val finalElement = applyOverride(element, configuration, override)

        // PG4-2 ext: whether this element's own @Preview asked for system-UI chrome (showSystemUi), so LiveRenderer
        // can render WITH decorations instead of always shrink-to-content. Guarded on its own: a signature change on
        // a newer IDE degrades this one flag to false (today's plain-preview behavior), not the whole resolution.
        val showDecorations = runCatching { finalElement.displaySettings.showDecoration }.getOrDefault(false)

        return RenderModelResult.Resolved(Resolved(renderModule, configuration, logger, finalElement, showDecorations))
    }

    /**
     * Applies [element]'s own device/api/size/showSystemUi onto [configuration] via AS's `applyTo` (needs a read
     * action — every caller already holds one). Returns `false` if `applyTo` throws, so an apply failure degrades
     * to the caller's own fallback instead of becoming a hard [RenderModelResult.Failed] via [resolve]'s outer
     * catch. Despite the name (kept from PG4-2, its original and still most common caller), this helper is
     * generic in [element] — PG6-10's [applyOverride] reuses it verbatim for an override-derived element, where a
     * throw here falls back to that override's own base element rather than the default-config render.
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
     * PG6-10: applies [override]'s values onto [base] by deriving a new preview element via AS's own
     * `ComposePreviewElementInstance.createDerivedInstance` and running it through [applyConfigAware] (design
     * D5) — replacing the interim three-axis `setDevice`/`setNightMode`/`setFontScale` mechanism (PG6-7,
     * superseded — see [RenderApiProbe.isViewOverrideAvailable]'s updated requirements). A `null` or default
     * override ([ViewOverride.isDefault]) returns [base] itself, unchanged: Original's own render (whose
     * override is always `null`) and a freshly-added, untouched comparison copy (AC1) are both exactly as
     * before this task.
     *
     * Guarded like every other AS-internal call in this class, via [deriveOverriddenElement] and
     * [applyConfigAware]: [ProcessCanceledException] re-thrown first, then [Exception]/[LinkageError] degrade to
     * [base] — an override that fails to derive or apply on this IDE build renders the copy at its base
     * configuration instead of failing the whole render (design D11).
     */
    private fun applyOverride(
        base: SingleComposePreviewElementInstance<*>,
        configuration: Configuration,
        override: ViewOverride?,
    ): SingleComposePreviewElementInstance<*> {
        if (override == null || override.isDefault) return base
        val derived = deriveOverriddenElement(base, override) ?: return base
        return if (applyConfigAware(derived, configuration)) derived else base
    }

    /**
     * The actual `createDerivedInstance` call (spec D5), isolated from [applyOverride] so it can return `null` on
     * failure the same way [findConfigAwareElement] does, instead of juggling try/catch + a definite-assignment
     * `val` across a `when applyOverride` caller. [mergeConfiguration] handles spec V4's `cleanAndGet` trap for
     * the 8 `Configuration`-mapped axes; `showDecoration` (`showSystemUi`) and `background`
     * (`showBackground`/`backgroundColor`, via [mergeBackground] — Fix round 1) are folded into the derived
     * display settings in the same `copy(...)` call, so every offered picker property reaches the render.
     */
    private fun deriveOverriddenElement(
        base: SingleComposePreviewElementInstance<*>,
        override: ViewOverride,
    ): SingleComposePreviewElementInstance<*>? = try {
        val merged = mergeConfiguration(base.configuration, override)
        val display = base.displaySettings.copy(
            showDecoration = override.values["showSystemUi"]?.toBooleanStrictOrNull()
                ?: base.displaySettings.showDecoration,
            background = mergeBackground(base.displaySettings.background, override),
        )
        base.createDerivedInstance(display, merged)
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().info("Deriving the view-override preview element failed; using the base configuration", e)
        null
    } catch (e: LinkageError) {
        thisLogger().info(
            "The view-override derive API is incompatible with this IDE build; using the base configuration",
            e,
        )
        null
    }

    /**
     * Fix round 1: folds `showBackground`/`backgroundColor` onto [base] (spec D5 — these travel through the
     * bridge XML via [PreviewDisplaySettings], not a `Configuration` setter). `PreviewDisplaySettings.Background`
     * is a 4-variant Kotlin sealed interface (javap-confirmed against `android.jar`): the singleton objects
     * `None` and `Default`, `Color(color: String)`, and `Image(image: Consumer<BufferedImage>)` — the last is
     * unreachable from the picker's two string properties and is preserved, not clobbered, when [base] already
     * carries one (see the final branch below). `backgroundColor`'s string is passed straight through into
     * `Color(...)` unchanged, exactly like `device`/`locale` elsewhere ([OverrideMerge]) — no format was
     * reverse-engineered or assumed; whatever string the picker's own editor round-trips through `Color.color`
     * when seeding is the same string handed back to `Color(...)` here.
     *
     * Base-preserving per spec V4, same rule as [mergeConfiguration]: `shown`/`color` each fall back to reading
     * [base] itself — `shown` from `base !is Background.None`, `color` from `(base as? Background.Color)?.color`
     * — when the corresponding property was not edited this session, so an untouched axis reproduces [base]'s
     * own value instead of a fabricated default.
     */
    private fun mergeBackground(base: PreviewDisplaySettings.Background, override: ViewOverride): PreviewDisplaySettings.Background {
        val baseShown = base !is PreviewDisplaySettings.Background.None
        val baseColor = (base as? PreviewDisplaySettings.Background.Color)?.color
        val shown = override.values["showBackground"]?.toBooleanStrictOrNull() ?: baseShown
        val color = override.values["backgroundColor"] ?: baseColor
        return when {
            !shown -> PreviewDisplaySettings.Background.None
            color != null -> PreviewDisplaySettings.Background.Color(color)
            baseShown -> base // shown, no color either way, and base already had SOME visible background (Default/Image) — keep it as-is.
            else -> PreviewDisplaySettings.Background.Default // newly toggled on, no color yet: a sensible starting point, not a guess.
        }
    }

    /**
     * The AS [PreviewConfiguration] ⇄ [MergedConfig] conversion at the AS boundary (spec V4): [OverrideMerge.merge]
     * itself (Step 3) never touches an AS type, is pure, and is unit-tested directly. [base]'s own
     * `colorBlindImageTransformation` — outside [ViewOverride]'s property set entirely, so the picker can never
     * edit it — is always passed straight through for the same reason every unedited axis is: `cleanAndGet`
     * would otherwise reset it to its sentinel.
     */
    private fun mergeConfiguration(base: PreviewConfiguration, override: ViewOverride): PreviewConfiguration {
        val merged = OverrideMerge.merge(
            MergedConfig(
                apiLevel = base.apiLevel,
                width = base.width,
                height = base.height,
                locale = base.locale,
                fontScale = base.fontScale,
                uiMode = base.uiMode,
                deviceSpec = base.deviceSpec,
                wallpaper = base.wallpaper,
            ),
            override,
        )
        return PreviewConfiguration.cleanAndGet(
            /* apiLevel = */ merged.apiLevel,
            /* width = */ merged.width,
            /* height = */ merged.height,
            /* locale = */ merged.locale,
            /* fontScale = */ merged.fontScale,
            /* uiMode = */ merged.uiMode,
            /* deviceSpec = */ merged.deviceSpec,
            /* wallpaper = */ merged.wallpaper,
            /* colorBlindImageTransformation = */ base.colorBlindImageTransformation,
        )
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
     *
     * It does, however, require a cancellable context, which none of this method's callers arrive with: they all
     * reach it on a pooled thread whose context was propagated from a plain EDT callback, so it carries neither a
     * `Job` nor a `ProgressIndicator`. The platform reports that through `Logger.error` (an internal-error
     * notification in the IDE) instead of throwing, so no `catch` below ever saw it. [RenderTaskContext] installs
     * the context the bridge needs — see its doc for the whole mechanism.
     */
    private fun findConfigAwareElement(
        entry: PreviewEntry,
        project: Project,
    ): SingleComposePreviewElementInstance<*>? {
        if (!RenderApiProbe.isConfigAwareAvailable()) return null
        return try {
            val elements = RenderTaskContext.runCancellable {
                runBlockingCancellable {
                    AnnotationFilePreviewElementFinder.findPreviewElements(project, entry.file)
                }
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

    /**
     * PG6-10: [entry]'s own current preview element — config-aware when available, else the layoutlib-default
     * fallback; the exact "real `@Preview` args, else sentinel defaults" resolution [resolveUnderReadAction]
     * itself uses for rendering (just without applying it onto a `Configuration`). `internal`, not `private`, so
     * [EphemeralPickerBridge] can seed its ephemeral picker's items from the SAME values a render would use,
     * without duplicating [findConfigAwareElement]'s lock-free-before-any-read-action discipline (see its own
     * doc for why). Must be called OUTSIDE a read action for the same reason [findConfigAwareElement] alone must
     * be — [buildDefaultPreviewElement] takes none itself, so the combination carries no extra constraint.
     */
    internal fun resolveCurrentElement(entry: PreviewEntry, project: Project): SingleComposePreviewElementInstance<*> =
        findConfigAwareElement(entry, project) ?: buildDefaultPreviewElement(entry)
}
