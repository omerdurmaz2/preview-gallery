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
import com.android.tools.environment.Logger
import com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.preview.ComposePreviewElementInstance
import com.android.tools.preview.DisplayPositioning
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.ParametrizedComposePreviewElementTemplate
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.XmlSerializable
import com.android.tools.preview.applyTo
import com.android.tools.preview.config.findOrParseFromDefinition
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
 *
 * D6a (PG22-14): [resolve]'s [pinCalibrationDevice] pins the render's own [Configuration] to
 * [CALIBRATION_DEVICE_SPEC] — the device the committed goldens were drawn on. Only the calibration's own caller
 * ([LiveRenderer.renderVariant]) ever asks for it; every other caller's [Configuration] is picked exactly as
 * before this task. A pin that cannot be applied stops the render ([RenderModelResult.DeviceSpecUnresolved])
 * rather than proceeding on whatever device the module's own [ConfigurationManager] would otherwise have handed
 * back — the failure mode a gate run actually hit.
 *
 * PG22-15: [applyCalibrationConfiguration] writes that device onto [Configuration] via [Configuration.setDevice]
 * directly, not through [PreviewConfiguration.applyTo] (the seam [applyConfigAware] uses for an ordinary
 * `@Preview`'s own configuration). `applyTo` resolves the configuration's preferred theme, which needs the main
 * manifest index a `src/screenshotTest` file's module does not have (D3a) — a real gate run hit
 * `MainManifestIndexNotReadyException` from exactly that path.
 *
 * PG22-19: the pin is a device **and** a theme ([CALIBRATION_THEME]), both taken from the screenshot engine's own
 * jar rather than approximated — the device by catalogue id so every qualifier matches, the theme as a literal so
 * no preferred-theme lookup happens. See [applyCalibrationConfiguration] and the two constants for the `javap`
 * evidence behind each.
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
     *
     * [variantAssumed] (D3a) is true only when [resolveUnderReadAction] rendered [element] as
     * [buildDefaultPreviewElement] because [findConfigAwareElement]'s finder returned no elements at all for a
     * non-null `requiredVariant` — the `phone`-carries-no-properties case the amendment covers, never the "found
     * instances but none named `phone`" one, which still stops at [RenderModelResult.VariantUnresolved] and never
     * reaches this class. `false` for every render before PG22-11 and for every ordinary (no `requiredVariant`)
     * render today.
     */
    class Resolved(
        val renderModule: RenderModelModule,
        val configuration: Configuration,
        val logger: RenderLogger,
        val instances: List<Instance>,
        val showDecorations: Boolean,
        val variantAssumed: Boolean = false,
    )

    /**
     * PG24: one preview instance to render, and the label the panel puts under it.
     *
     * A plain `@Preview` resolves to exactly one of these and [Resolved.instances] is a singleton — the shape
     * every render had before this task. A `@PreviewParameter` one resolves to one per value the provider yields,
     * which is the whole reason this is a list: those instances differ only in the argument they are invoked
     * with, so they share one [Configuration] and one [RenderModelModule] and differ only in their XML.
     */
    class Instance(val label: String, val element: XmlSerializable)

    sealed interface RenderModelResult {
        class Resolved(val model: RenderModelResolver.Resolved) : RenderModelResult

        /** The file's module has no Android facet — the caller maps this to `Unsupported`. */
        object NoFacet : RenderModelResult

        /** An AS-internal call failed — the caller maps this to `Failure`. */
        class Failed(val message: String, val detail: String?) : RenderModelResult

        /** [resolve] was asked for one named `@Preview` instance and no such instance could be resolved and
         *  applied. Only ever produced for a non-null `requiredVariant`; see [resolve]'s own doc. */
        object VariantUnresolved : RenderModelResult

        /** D6a: [resolve] was asked to pin the render's [Configuration] to [CALIBRATION_DEVICE_SPEC]
         *  (`pinCalibrationDevice = true`) and applying it failed. Only ever produced when that flag is true —
         *  every render before PG22-14, and every render that does not ask for the pin, never reaches this
         *  branch, exactly as [VariantUnresolved] is reached only for a non-null `requiredVariant`. The caller
         *  must stop, not fall back to whatever device the module's own [Configuration] already carried: a render
         *  on the wrong device compared against the `phone` golden is not a percentage, it is nothing. */
        object DeviceSpecUnresolved : RenderModelResult
    }

    /** [decideVariantResolution]'s own result: either render — [Proceed.variantAssumed] says whether the element is
     *  confirmed or merely assumed (D3a) — or stop, unrendered, at [RenderModelResult.VariantUnresolved]. */
    internal sealed interface VariantResolution {
        data class Proceed(val variantAssumed: Boolean) : VariantResolution
        object Unresolved : VariantResolution
    }

    /** [decideDevicePin]'s own result (D6a): [NotRequested] for every render that did not ask for the calibration
     *  pin — every caller before PG22-14, and every ordinary render since — [Applied] once
     *  [CALIBRATION_DEVICE_SPEC] has been written onto the render's [Configuration], [Failed] when it could not
     *  be. Only [Failed] turns into [RenderModelResult.DeviceSpecUnresolved]. */
    internal sealed interface DevicePinResolution {
        object NotRequested : DevicePinResolution
        object Applied : DevicePinResolution
        object Failed : DevicePinResolution
    }

    companion object {
        /**
         * D6a, revised in PG22-19: the device the comparison render must use — the Android screenshot engine's
         * **own default device, named by catalogue id**, rather than a spec synthesized to have the same pixel
         * size.
         *
         * `compose-preview-renderer-0.0.1-alpha15` builds its default device as
         * `DefaultDevices.getDevice("medium_phone", "Generic")` (`javap -c` against that artifact's
         * `StandaloneConfigurationSettings`), so `id:medium_phone` hands this render the *same* `Device` the
         * engine renders every golden on, read out of the same `devices.xml`, with every qualifier matching
         * instead of only the two dimensions.
         *
         * **Why the previous `spec:width=1080px,height=2400px,dpi=420` was not enough**, even though PG22-16 is
         * right that it produces exactly the golden's canvas: `DeviceUtilsKt.createDeviceInstance` sets
         * `xDimension`, `yDimension`, `pixelDensity`, a diagonal derived from those three, round/chin and ratio —
         * and nothing else. It never sets `xdpi`/`ydpi`, navigation, keyboard or touch screen, and it derives
         * `Screen.size` from that computed diagonal: `sqrt(1080² + 2400²) / 420 = 6.27 in`, which
         * `ScreenSize.getScreenSize` maps to **`LARGE`** (its own thresholds, by bytecode: `XLARGE` at
         * `160 * diagonal >= 1200`, `LARGE` at `>= 800`, `NORMAL` at `>= 568`; `160 * 6.27 = 1003`). The
         * catalogue's `medium_phone` declares `normal`, with `diagonal-length` 6.4, `xdpi`/`ydpi` 420, `nav`
         * `nonav` and `keyboard` `nokeys`. layoutlib ORs `Screen.size` into `Configuration.screenLayout`, so a
         * `-large`/`-normal` resource folder, a `WindowSizeClass` read or an `isLayoutSizeAtLeast` branch
         * resolves differently on the two devices — and the size check cannot catch that, because both are
         * 1080x2400 either way.
         *
         * `findOrParseFromDefinition` takes the `id:` form directly: only a `spec:`-prefixed definition goes
         * through `DeviceConfig`/`createDeviceInstance`, and everything else falls through to `findByIdOrName`,
         * which strips the prefix and matches on `Device.getId()` (`javap -c` against `android.jar`;
         * `DEVICE_BY_ID_PREFIX = "id:"` is that same file's own constant).
         */
        internal const val CALIBRATION_DEVICE_SPEC = "id:medium_phone"

        /**
         * D6a, PG22-19: the theme the comparison render must use — the one the screenshot engine renders every
         * golden under — written as a literal so it can be applied without resolving one.
         *
         * The engine's `RenderRequest.configurationModifier` is `PreviewConfigurationKt::applyTo`, whose device
         * step ends `setTheme(getPreferredTheme())` (`javap -c`), and the `ThemeInfoProvider` that lookup reaches
         * in a standalone render is `StandaloneThemeInfoProvider`, whose `appThemeName` — returned by both
         * `getDefaultTheme` and `getDeviceDefaultTheme` — is exactly this string.
         *
         * It carries far more of the measurement than one line suggests. `Theme.Material.Light`'s
         * `windowBackground` resolves to `#FAFAFA`, and the golden for a bare `Row`
         * (`CreateListActionBar_Submitting_Snapshot_phone`, 1080x190) holds `(250,250,250,255)` in 83,044 of its
         * 205,200 pixels — 40.5%. A render that inherits the module's own theme paints something else there, and
         * [ImageDiff] measures 40% of the image as different before a single glyph is compared.
         *
         * A literal, never `Configuration.getPreferredTheme()`: that lookup needs the main manifest index a
         * `src/screenshotTest` file's module does not have and threw `MainManifestIndexNotReadyException` out of
         * a real gate run — the whole reason [applyCalibrationConfiguration] bypasses `applyTo` (PG22-15).
         */
        internal const val CALIBRATION_THEME = "@android:style/Theme.Material.Light"

        /** How many `@PreviewParameter` values one composable may render (PG24). Each costs its own
         *  `RenderTask`, and a provider is ordinary Kotlin that can yield as many as it likes; past this the
         *  extras are dropped and the drop is logged. */
        internal const val MAX_PARAMETER_INSTANCES = 16

        /**
         * D3a: the pure form of the branch both [resolve] and [resolveUnderReadAction] decide with, extracted so
         * the decision itself is testable without Android Studio (see `RenderModelResolverTest`) — obtaining
         * [elementFound] and [finderReturnedNothing] needs AS's finder and `Configuration.applyTo`, but choosing
         * what they mean, once known, does not. [resolve] calls this once, lock-free, purely to skip the read
         * action outright when it already knows the answer is [VariantResolution.Unresolved]; [resolveUnderReadAction]
         * calls it again with [elementFound] now meaning "the config-aware element applied successfully" rather
         * than "the finder matched one" — the same rule both times, run once per call against whatever that call
         * has resolved so far.
         *
         * The four reachable combinations:
         *  - [requiredVariant] `null` → always [VariantResolution.Proceed] with `variantAssumed = false`: the
         *    ordinary, no-named-variant render every caller before PG22-8 took, independent of [elementFound].
         *  - [requiredVariant] non-null, [elementFound] `true` → [VariantResolution.Proceed] with
         *    `variantAssumed = false`: the named `@Preview` was found — the confirmed case D3 always allowed.
         *  - [requiredVariant] non-null, [elementFound] `false`, [finderReturnedNothing] `true` → D3a's amendment:
         *    [VariantResolution.Proceed] with `variantAssumed = true`. `AnnotationFilePreviewElementFinder`
         *    produced no elements at all for the file (a project synced without
         *    `-Pandroid.experimental.enableScreenshotTest=true`), so [buildDefaultPreviewElement] renders in the
         *    named variant's place — safe only because the consuming project's `phone` `@Preview` carries no
         *    properties of its own, so its configuration *is* the default one.
         *  - [requiredVariant] non-null, [elementFound] `false`, [finderReturnedNothing] `false` →
         *    [VariantResolution.Unresolved], unchanged from before PG22-11: the finder found instances but none
         *    named [requiredVariant] — a real naming disagreement D3 forbids papering over.
         */
        internal fun decideVariantResolution(
            requiredVariant: String?,
            elementFound: Boolean,
            finderReturnedNothing: Boolean,
        ): VariantResolution = when {
            elementFound || requiredVariant == null -> VariantResolution.Proceed(variantAssumed = false)
            finderReturnedNothing -> VariantResolution.Proceed(variantAssumed = true)
            else -> VariantResolution.Unresolved
        }

        /**
         * D6a: the pure form of the branch [resolveUnderReadAction] decides the calibration device pin with —
         * mirrors [decideVariantResolution]'s split of "what AS said" from "what it means". [applied] is only
         * meaningful when [pinRequested] is true; [resolveUnderReadAction] never even attempts
         * [applyCalibrationConfiguration] otherwise (its call site short-circuits on `pinRequested &&`), and this
         * function does not need [applied] to answer [DevicePinResolution.NotRequested] either way.
         */
        internal fun decideDevicePin(pinRequested: Boolean, applied: Boolean): DevicePinResolution = when {
            !pinRequested -> DevicePinResolution.NotRequested
            applied -> DevicePinResolution.Applied
            else -> DevicePinResolution.Failed
        }
    }

    /**
     * PG22-3: [moduleWrapper], when non-null, wraps the [AndroidFacetRenderModelModule] this method would
     * otherwise build directly — the seam a `screenshotTest` classpath injection composes onto (see
     * [ScreenshotTestClassLoader]). A null wrapper (every caller before this task, and `Original`'s own render)
     * reproduces the exact pre-PG22-3 path: [resolveUnderReadAction] builds the same
     * [AndroidFacetRenderModelModule] either way.
     *
     * PG22-8: [requiredVariant], when non-null, names the `@Preview` whose instance must be rendered — the `name`
     * a multipreview declares (`phone`, `small`), which Android Studio also uses as `displaySettings.name` for
     * each instance it expands a multipreview into. It replaces the ordinary
     * `displaySettings.name == entry.indexed.displayName` match, which cannot work for a multipreview at all:
     * `PreviewPsiScanner` leaves the annotation unresolved for a `@PreviewTest` whose only preview annotation is a
     * custom multipreview, so `displayName` is the bare function name and no instance carries it.
     *
     * When no instance carries [requiredVariant] — or its configuration cannot be applied — this returns
     * [RenderModelResult.VariantUnresolved] rather than falling back to [buildDefaultPreviewElement]. Spec D3:
     * a render whose variant is unknown cannot be compared against a variant-specific golden, so the caller must
     * stop and report, not measure the plugin's default configuration against a `phone` golden.
     *
     * **D3a amendment, two branches that must stay distinct** (a gate run against `hepsi-android` found
     * [findConfigAwareElement]'s finder returning no elements at all for a real `screenshotTest` file — the project
     * is synced without `-Pandroid.experimental.enableScreenshotTest=true`, so AGP never attributes that file to a
     * source set and the finder has no VFS fallback the way the plugin's own index does):
     *  - **The finder returned nothing at all** ([ConfigAwareLookup.finderReturnedNothing]) → [element] below still
     *    renders as [buildDefaultPreviewElement], but [Resolved.variantAssumed] is now `true` instead of this
     *    method stopping at [RenderModelResult.VariantUnresolved]. Safe only because the consuming project's
     *    `phone` `@Preview` carries no properties of its own — its configuration *is* the default configuration,
     *    so what was missing was never the configuration, only the label the finder would have carried.
     *  - **The finder returned instances, but none named [requiredVariant]** → unchanged: still
     *    [RenderModelResult.VariantUnresolved]. This is a real naming disagreement, not a missing source set, and
     *    D3 already forbids papering over it by rendering some other configuration and comparing it to a
     *    variant-specific golden anyway.
     * Conflating the two would silently render the wrong thing whenever a multipreview's variants genuinely
     * disagree with what a golden's file name expects — exactly what D3 was written to prevent.
     *
     * D6a (PG22-14): [pinCalibrationDevice], when true, additionally pins [Resolved.configuration]'s device to
     * [CALIBRATION_DEVICE_SPEC] once the variant above has resolved — the device the committed goldens were
     * rendered on, not whatever [ConfigurationManager] hands back for the module (a gate run found it handing back
     * a large landscape screen, which is why the two images it did manage to render were different sizes and
     * nothing got measured). `false` (every caller before this task, and [LiveRenderer.render]'s own ordinary
     * path) leaves [Resolved.configuration] exactly as before this task. When the pin cannot be applied, this
     * returns [RenderModelResult.DeviceSpecUnresolved] rather than proceeding on the module's own device — the
     * same stop-not-guess posture [requiredVariant] already holds for the composable instance, now held for the
     * device too.
     */
    fun resolve(
        entry: PreviewEntry,
        project: Project,
        override: ViewOverride? = null,
        moduleWrapper: RenderModuleWrapper? = null,
        requiredVariant: String? = null,
        pinCalibrationDevice: Boolean = false,
    ): RenderModelResult =
        try {
            // The config-aware element is fetched FIRST, lock-free (see [findConfigAwareElement]): its finder is a
            // suspend function that acquires its OWN (smart) read access, and calling it while we already hold the
            // synchronous read lock below made it wait for smart-mode / write-intent the starved EDT could never
            // grant — pinning the read lock and freezing the whole IDE (observed: a 65s UI freeze on select). Only
            // the project-model reads that genuinely need a read action run inside ReadAction.compute below, which
            // applies the already-fetched element's own @Preview configuration.
            val lookup = findConfigAwareElement(entry, project, requiredVariant)
            val earlyDecision =
                decideVariantResolution(requiredVariant, lookup.elements.isNotEmpty(), lookup.finderReturnedNothing)
            if (earlyDecision is VariantResolution.Unresolved) {
                RenderModelResult.VariantUnresolved
            } else {
                ReadAction.compute<RenderModelResult, RuntimeException> {
                    resolveUnderReadAction(
                        entry,
                        project,
                        lookup.elements,
                        override,
                        moduleWrapper,
                        requiredVariant,
                        lookup.finderReturnedNothing,
                        pinCalibrationDevice,
                    )
                }
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
        configAware: List<ComposePreviewElementInstance<*>>,
        override: ViewOverride?,
        moduleWrapper: RenderModuleWrapper? = null,
        requiredVariant: String? = null,
        finderReturnedNothing: Boolean = false,
        pinCalibrationDevice: Boolean = false,
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
        //
        // PG22-8: with a [requiredVariant] the default element is not an acceptable fallback — it is the plugin's
        // own configuration, not the named `@Preview`'s, and measuring it against that variant's golden would fold
        // the configuration difference into the percentage invisibly (spec D3).
        // One configuration for the whole set: a `@PreviewParameter` composable's instances come from a single
        // `@Preview`, so they share its device/api/size and differ only in the argument they are invoked with.
        // Applying the first one's configuration therefore applies all of theirs.
        val applied = configAware.isNotEmpty() && applyConfigAware(configAware.first(), configuration)
        val decision = decideVariantResolution(requiredVariant, applied, finderReturnedNothing)
        if (decision is VariantResolution.Unresolved) {
            return RenderModelResult.VariantUnresolved
        }
        val elementVariantAssumed = (decision as VariantResolution.Proceed).variantAssumed
        val elements: List<ComposePreviewElementInstance<*>> =
            if (applied) configAware else listOf(buildDefaultPreviewElement(entry))
        thisLogger().debug(
            "Rendering ${entry.indexed.composableFqn} as ${elements.map { it.displaySettings.name }} " +
                "(configAware=${configAware.size}, requiredVariant=$requiredVariant, " +
                "variantAssumed=$elementVariantAssumed)",
        )

        val devicePinApplied = pinCalibrationDevice && applyCalibrationConfiguration(configuration)
        if (decideDevicePin(pinCalibrationDevice, devicePinApplied) is DevicePinResolution.Failed) {
            return RenderModelResult.DeviceSpecUnresolved
        }

        // PG6-10: a non-default comparison-view override — from EphemeralPickerBridge's in-memory picker for a
        // copy; always null/default for Original — is applied on top of whichever element the config-aware/
        // default resolution above produced. A null or default override returns [element] itself, unchanged, so
        // Original's render is byte-for-byte identical to before PG6-9/PG6-10.
        val finalElements = elements.map { applyOverride(it, configuration, override) }

        // PG4-2 ext: whether this element's own @Preview asked for system-UI chrome (showSystemUi), so LiveRenderer
        // can render WITH decorations instead of always shrink-to-content. Guarded on its own: a signature change on
        // a newer IDE degrades this one flag to false (today's plain-preview behavior), not the whole resolution.
        val showDecorations = runCatching { finalElements.first().displaySettings.showDecoration }.getOrDefault(false)

        return RenderModelResult.Resolved(
            Resolved(
                renderModule,
                configuration,
                logger,
                finalElements.map { Instance(labelOf(it), it) },
                showDecorations,
                elementVariantAssumed,
            ),
        )
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
        element: ComposePreviewElementInstance<*>,
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
     * PG22-15: writes [CALIBRATION_DEVICE_SPEC] straight onto [configuration] via [Configuration.setDevice],
     * bypassing [PreviewConfiguration.applyTo] entirely — unlike [applyConfigAware], this never *resolves* a
     * theme. `applyTo` resolves one (`Configuration.getPreferredTheme()`, which needs the main manifest index)
     * because it applies a whole `PreviewConfiguration` — api level, locale, font scale, wallpaper, ui mode,
     * theme. A `src/screenshotTest` file's module has no manifest to index at all (D3a), so that lookup threw
     * `MainManifestIndexNotReadyException` out of a real gate run.
     *
     * PG22-19: it does set a theme, [CALIBRATION_THEME], as a literal string. That is not a return to `applyTo`'s
     * path — the exception above came from *resolving* a preferred theme, and a literal never asks. Leaving the
     * theme alone was the larger error: the [Configuration] this render is handed carries whatever theme the
     * module's own `ConfigurationManager` persisted, while every committed golden was drawn under
     * [CALIBRATION_THEME], and on a shrink-to-content golden that single axis accounts for 40% of the pixels
     * (see that constant's own doc for the count).
     *
     * [findOrParseFromDefinition] (`Collection<Device>.findOrParseFromDefinition(String, Logger)`, a
     * `com.android.tools.preview.config` package-level extension confirmed via `javap` against `android.jar`) is
     * Android Studio's own device-definition entry point — the same one `applyTo`'s private device-resolution
     * branch calls for a [PreviewConfiguration.deviceSpec], not a hand-rolled parser. For [CALIBRATION_DEVICE_SPEC]
     * it takes the `id:` branch: only a `spec:` prefix goes through `DeviceConfig`/`createDeviceInstance`, and
     * everything else falls through to `findByIdOrName`. `null` — a blank string, an unparseable spec, or an
     * unknown id, the last of which is the only one reachable for this fixed constant and only on an IDE whose
     * device catalogue has no `medium_phone` — means the pin could not be applied.
     *
     * `configuration.setEffectiveDevice(null, null)` immediately before `setDevice` mirrors `applyTo`'s own
     * bytecode exactly (both arguments `null` there too, `javap -c` against `PreviewConfigurationKt`): it clears
     * any effective-device override the [Configuration] already carries so the newly pinned device is not shadowed
     * by it. `setDevice`'s own second argument (`false`) is likewise copied unchanged from what `applyTo` itself
     * passes, as is the order — `applyTo` sets the device and then the theme.
     */
    private fun applyCalibrationConfiguration(configuration: Configuration): Boolean = try {
        val device = configuration.settings.devices.findOrParseFromDefinition(
            CALIBRATION_DEVICE_SPEC,
            Logger.getInstance(RenderModelResolver::class.java),
        )
        if (device == null) {
            false
        } else {
            configuration.setEffectiveDevice(null, null)
            configuration.setDevice(device, false)
            configuration.setTheme(CALIBRATION_THEME)
            true
        }
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().info("Applying the calibration device and theme failed; the pin is unavailable", e)
        false
    } catch (e: LinkageError) {
        thisLogger().info(
            "The device-definition API is incompatible with this IDE build; the calibration pin is unavailable",
            e,
        )
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
        base: ComposePreviewElementInstance<*>,
        configuration: Configuration,
        override: ViewOverride?,
    ): ComposePreviewElementInstance<*> {
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
        base: ComposePreviewElementInstance<*>,
        override: ViewOverride,
    ): ComposePreviewElementInstance<*>? = try {
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
     * `Configuration` under its read action).
     *
     * [variantName], when non-null, replaces [entry]'s own `displayName` on the name half of that match: it is the
     * `name` one `@Preview` of a multipreview declares, and the name Android Studio gives the instance it expands
     * that `@Preview` into. See [resolve] for why the calibration must select by it, and what its absence means.
     * The candidate names are logged at debug either way, so the gate can see what the finder actually offered.
     *
     * Returns a null [ConfigAwareLookup.element] (so the caller falls back to [buildDefaultPreviewElement], or —
     * with a [variantName] and [ConfigAwareLookup.finderReturnedNothing] false — stops) when:
     *  - the probe ([RenderApiProbe.isConfigAwareAvailable]) says the finder is not present on this IDE build —
     *    checked first so an IDE build that lacks it never pays for the read-action + suspend-bridge round trip;
     *  - the finder returns no element whose `methodFqn` matches [entry.indexed]'s `composableFqn` and whose
     *    `displaySettings.name` matches [variantName] (or, with none, [entry.indexed]'s `displayName`);
     *  - the match is a `ParametrizedComposePreviewElementTemplate` (a `@PreviewParameter` or multipreview group)
     *    rather than an already-resolved single instance — not `XmlSerializable`, and not what one [PreviewEntry]
     *    represents (spec R1); the `as?` below returns `null` for it precisely because that template class does
     *    NOT extend `SingleComposePreviewElementInstance`;
     *  - the finder call or the match throws — guarded against [Exception] and [LinkageError] (logged once, at
     *    `info` — this is an expected degrade, not a broken feature; applying the config is guarded separately in
     *    [applyConfigAware]).
     *
     * [ConfigAwareLookup.finderReturnedNothing] (D3a) is `true` in exactly one of those cases: the finder itself
     * ran to completion and its own `elements` list — before any FQN or name filtering — was empty. It stays
     * `false` for every other null-element case above, including "the finder found elements for other composables
     * or names, just not this one" and "the probe/call/match failed": those are not the shape D3a's evidence
     * covers, and [resolve] must keep stopping at [RenderModelResult.VariantUnresolved] for them.
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
        variantName: String? = null,
    ): ConfigAwareLookup {
        if (!RenderApiProbe.isConfigAwareAvailable()) return ConfigAwareLookup(emptyList(), finderReturnedNothing = false)
        return try {
            val elements = RenderTaskContext.runCancellable {
                runBlockingCancellable {
                    AnnotationFilePreviewElementFinder.findPreviewElements(project, entry.file)
                }
            }
            // Reads only String properties of the finder's already-resolved snapshot elements (no live PSI), so it
            // is safe off a read action; the config-aware element itself is applied under one in the caller.
            val wanted = variantName ?: entry.indexed.displayName
            val candidates = elements.filter { it.methodFqn == entry.indexed.composableFqn }
            val matched = candidates.firstOrNull { it.displaySettings.name == wanted }
            thisLogger().debug(
                "Config-aware lookup for ${entry.indexed.composableFqn}: wanted '$wanted', " +
                    "instances ${candidates.map { it.displaySettings.name }}, matched=${matched != null}. " +
                    "Everything the finder returned for this file: " +
                    "${elements.map { it.methodFqn to it.displaySettings.name }}",
            )
            ConfigAwareLookup(
                instancesOf(matched, entry),
                finderReturnedNothing = elements.isEmpty(),
            )
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().info(
                "Config-aware preview element lookup failed for ${entry.indexed.composableFqn}; " +
                    "using the default configuration",
                e,
            )
            ConfigAwareLookup(emptyList(), finderReturnedNothing = false)
        } catch (e: LinkageError) {
            thisLogger().info(
                "Config-aware preview element API is incompatible with this IDE build; using the default configuration",
                e,
            )
            ConfigAwareLookup(emptyList(), finderReturnedNothing = false)
        }
    }

    /**
     * [findConfigAwareElement]'s own result, split into the two facts [resolve] needs to tell apart (D3a): the
     * matched element itself, if any, and whether the finder's *own* `elements` list — before any FQN or name
     * filtering — was empty. A `null` [element] can happen either way; only [finderReturnedNothing] says which,
     * and only [resolve] reads it, for exactly one decision: whether a [requiredVariant] miss may still render the
     * default element (finder returned nothing) or must stop at [RenderModelResult.VariantUnresolved] (finder
     * returned instances, none of them the wanted one). See [resolve]'s own doc for why conflating the two would
     * undo D3.
     */
    private class ConfigAwareLookup(
        val elements: List<ComposePreviewElementInstance<*>>,
        val finderReturnedNothing: Boolean,
    )

    /**
     * PG24: the preview instances [matched] stands for — one for a plain `@Preview`, one per value for a
     * `@PreviewParameter` one, and none when the finder matched nothing.
     *
     * `AnnotationFilePreviewElementFinder` answers a `@PreviewParameter` composable with a
     * [ParametrizedComposePreviewElementTemplate], which is deliberately **not** a
     * [SingleComposePreviewElementInstance] — it is not renderable as it stands, because the argument to invoke
     * the composable with has not been chosen yet. Its own `resolve()` chooses them: it loads the declared
     * `PreviewParameterProvider` through a module class loader it builds and closes itself (`javap -c`), and
     * yields one `ParametrizedComposePreviewElementInstance` per value. Every one of those extends
     * `ComposePreviewElementInstance`, which implements `XmlSerializable` — so from the render's point of view
     * they are the same kind of thing a plain `@Preview` produces, and [LiveRenderer] needs no new concept.
     *
     * **Bounded at [MAX_PARAMETER_INSTANCES], and the drop is logged rather than silent.** A provider is ordinary
     * Kotlin and may yield hundreds of values; each one costs a `RenderTask`. A truncated set that looked
     * complete would be worse than a slow one.
     *
     * Guarded on its own rather than inside [findConfigAwareElement]'s catch: `resolve()` runs *project* code —
     * the provider's own constructor and `values` sequence — so it can throw anything at all, and a provider that
     * throws must degrade this preview to the default element, not fail the whole lookup. It also loads classes,
     * which is why it must stay off the read action, exactly like the finder call it sits next to.
     */
    private fun instancesOf(
        matched: com.android.tools.preview.ComposePreviewElement<*>?,
        entry: PreviewEntry,
    ): List<ComposePreviewElementInstance<*>> = when (matched) {
        null -> emptyList()
        is ComposePreviewElementInstance<*> -> listOf(matched)
        is ParametrizedComposePreviewElementTemplate<*> -> try {
            val resolved = matched.resolve().take(MAX_PARAMETER_INSTANCES + 1).toList()
            if (resolved.size > MAX_PARAMETER_INSTANCES) {
                thisLogger().info(
                    "${entry.indexed.composableFqn} has more than $MAX_PARAMETER_INSTANCES @PreviewParameter " +
                        "values; rendering the first $MAX_PARAMETER_INSTANCES",
                )
            }
            resolved.take(MAX_PARAMETER_INSTANCES)
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Throwable) {
            // Throwable, not Exception: resolve() instantiates the project's own PreviewParameterProvider, and a
            // provider that throws an Error (a missing class it referenced, a failed assertion) must cost this
            // one preview its parameters, never the IDE.
            thisLogger().info(
                "Could not resolve the @PreviewParameter values for ${entry.indexed.composableFqn}; " +
                    "using the default configuration",
                e,
            )
            emptyList()
        }
        else -> emptyList()
    }

    /** What the panel writes under one rendered instance: Android Studio's own name for it, which already
     *  carries the parameter index (`MyPreview (0)`). Falls back to the composable's simple name so a label is
     *  never blank — the strip reserves a row for it either way. */
    private fun labelOf(element: ComposePreviewElementInstance<*>): String =
        runCatching { element.displaySettings.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: element.methodFqn.substringAfterLast('.')

    /**
     * U5: builds a [SingleComposePreviewElementInstance] with a default configuration. `cleanAndGet(null…)` fills
     * every field (api level, size, locale, ui-mode, device) with layoutlib's defaults — the "no `@Preview`
     * arguments given" case. Display settings carry only naming metadata; `previewWrapperProviderFqn` is null
     * because this path is for plain (non-`@PreviewParameter`) composables. The fallback target for
     * [findConfigAwareElement] (PG4-2), and — before PG4-2 — the only path this class had.
     *
     * PG22-15: this used to take a `deviceSpec` parameter shared with a now-deleted `buildCalibrationDeviceElement`
     * sibling — [applyCalibrationConfiguration] no longer builds a throwaway preview element at all, so [deviceSpec] here
     * is always the "no `@Preview` arguments given" sentinel and the parameter was removed rather than left unused.
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
        findConfigAwareElement(entry, project)
            .elements
            .filterIsInstance<SingleComposePreviewElementInstance<*>>()
            .firstOrNull()
            ?: buildDefaultPreviewElement(entry)
}
