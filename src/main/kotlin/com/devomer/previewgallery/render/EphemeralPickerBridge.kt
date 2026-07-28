// PsiPickerManager, PsiPropertiesModel, MemoryParameterPropertyItem, PsiPropertyItem, PsiPropertiesInspectorBuilder,
// PreviewPropertiesInspectorBuilder, EnumSupportValuesProvider, PreviewPickerValuesProvider and ComposePickerTracker
// are `internal` in Kotlin visibility terms inside their own module (compose-designer), even though they are
// `public` at the JVM bytecode level (verified with javap — see the task-10 report). Kotlin enforces the *source*
// visibility across module boundaries regardless of the bytecode modifier, so calling/subclassing them from this
// plugin's own Kotlin module needs this suppression — exactly the same trick [PreviewPickerBridge] already carries.
// Nothing about the runtime call is affected — every use is still inside the try/catch guarding Exception and
// LinkageError (design §5), so a future AS build that actually removes or renames these members degrades to a
// logged no-op exactly as it would if the compiler had never let us reference them by name.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ViewOverride
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil

// ── Android Studio internal picker API — the ephemeral, in-memory sibling of PreviewPickerBridge (design D4).
//    Isolated here + in GalleryPickerTracker only (design §3), alongside the existing LiveRenderer/
//    RenderModelResolver render pair. ──
import com.android.tools.adtui.model.stdui.EditingErrorCategory
import com.android.tools.idea.compose.pickers.PsiPickerManager
import com.android.tools.idea.compose.pickers.base.enumsupport.EnumSupportValuesProvider
import com.android.tools.idea.compose.pickers.base.inspector.PsiPropertiesInspectorBuilder
import com.android.tools.idea.compose.pickers.base.model.PsiPropertiesModel
import com.android.tools.idea.compose.pickers.base.property.MemoryParameterPropertyItem
import com.android.tools.idea.compose.pickers.base.property.PsiPropertyItem
import com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker
import com.android.tools.idea.compose.pickers.common.enumsupport.PsiEnumProvider
import com.android.tools.idea.compose.pickers.common.inspector.PsiEditorProvider
import com.android.tools.idea.compose.pickers.preview.enumsupport.PreviewPickerValuesProvider
import com.android.tools.idea.compose.pickers.preview.inspector.PreviewPropertiesInspectorBuilder
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.UNDEFINED_API_LEVEL
import com.android.tools.preview.UNDEFINED_DIMENSION
import com.android.tools.preview.UNSET_UI_MODE_VALUE
import com.android.tools.preview.config.Cutout
import com.android.tools.preview.config.DeviceConfig
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.Navigation
import com.android.tools.preview.config.Orientation
import com.android.tools.preview.config.Shape
import com.android.tools.property.panel.api.ControlType
import com.android.tools.property.panel.api.ControlTypeProvider
import com.android.tools.property.panel.api.EditorProvider
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.collect.HashBasedTable
import com.intellij.openapi.ui.popup.Balloon

/**
 * Shows Android Studio's own `@Preview` property picker UI — the SAME dialog [PreviewPickerBridge] opens for
 * Original — for one comparison-view copy, backed by an in-memory [PsiPropertiesModel] instead of a PSI-backed
 * one (design D4). Every edit is reported back via [onEdit] as `(propertyName, value)`; nothing here ever writes
 * to the `@Preview` source (spec R5) — [MemoryParameterPropertyItem]'s own `value` setter is a bare field write
 * with no PSI/write-action involvement at all (javap-confirmed; see the task-10 report), so there is no write
 * path to even guard against.
 *
 * Modeled directly on [PreviewPickerBridge]: the same off-EDT + read-action split, the same
 * `at.component.isShowing` re-check before showing the popup, and the same `ProcessCanceledException` re-throw +
 * `Exception`/`LinkageError` degrade at every AS-internal call site (design §5). The one structural difference is
 * *what* is resolved off the EDT: [PreviewPickerBridge] resolves a PSI annotation pointer; this resolves the
 * entry's own current preview element ([RenderModelResolver.resolveCurrentElement]) to seed each item's starting
 * value, merged with [ViewOverride]'s own values (which always win — the copy's own prior edits, if any).
 */
class EphemeralPickerBridge(private val project: Project) {

    private val resolver = RenderModelResolver()

    fun isAvailable(): Boolean = RenderApiProbe.isViewOverrideAvailable()

    /**
     * Shows the ephemeral picker for [entry]'s comparison copy carrying [override], anchored at [at] (mirroring
     * [PreviewPickerBridge.showPicker]'s spec P4 anchor). [onEdit] fires on the EDT for every value the user
     * changes, as `(propertyName, value)` — [PreviewGalleryPanel] wires this to
     * `ComparisonViewList.setOverride(view.id, view.override.with(name, value))` followed by a re-render of just
     * that tab (brief step 4); this class holds no notion of "tabs" or [ViewOverride] mutation at all.
     *
     * Returns `false` immediately — showing nothing — only when [isAvailable] is false; like
     * [PreviewPickerBridge.showPicker], that synchronous short-circuit is the only thing this return value
     * promises. Everything else (resolving the entry's current values, building the picker's in-memory model)
     * happens off the EDT afterwards and can still silently not show the popup (the anchor stopped showing, or
     * any AS-internal call failed) — always logged, never thrown into the UI.
     */
    fun showEphemeralPicker(
        entry: PreviewEntry,
        override: ViewOverride,
        at: RelativePoint,
        onEdit: (String, String) -> Unit,
    ): Boolean {
        if (!isAvailable()) return false
        // Captured on the EDT (the button's own ActionListener), matching PreviewPickerBridge.showPicker's own
        // rationale for capturing modality before hopping off the EDT.
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute { buildModelAndShow(entry, override, at, modality, onEdit) }
        return true
    }

    /**
     * Off the EDT: resolves the entry's own current preview element — lock-free, BEFORE any read action, exactly
     * as [RenderModelResolver.resolve] itself does and [RenderModelResolver.resolveCurrentElement]'s own doc
     * requires — then, in one read action, resolves the module and builds the in-memory model, then hops to the
     * EDT (at [modality], captured before this background hop) to show the popup ([showPopup]).
     *
     * Every AS-internal call anywhere in this chain is guarded against [Exception] and [LinkageError] (design
     * §5); nothing here can throw back into the button's `ActionListener`, which already returned by the time
     * this runs on the background executor.
     */
    private fun buildModelAndShow(
        entry: PreviewEntry,
        override: ViewOverride,
        at: RelativePoint,
        modality: ModalityState,
        onEdit: (String, String) -> Unit,
    ) {
        try {
            val seedElement = resolver.resolveCurrentElement(entry, project)
            val built = ReadAction.compute<Pair<List<PsiPropertyItem>, EnumSupportValuesProvider>?, RuntimeException> {
                val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file) ?: return@compute null
                val enumProvider = PreviewPickerValuesProvider.createPreviewValuesProvider(module, entry.file)
                buildItems(seedElement, override, onEdit) to enumProvider
            }
            if (built == null) {
                thisLogger().info(
                    "Could not resolve the module for ${entry.indexed.composableFqn}; ephemeral picker not shown",
                )
                return
            }
            val (items, enumProvider) = built
            ApplicationManager.getApplication().invokeLater({ showPopup(entry, at, items, enumProvider) }, modality)
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not open the ephemeral picker for ${entry.indexed.composableFqn}", e)
        } catch (e: LinkageError) {
            thisLogger().warn("Ephemeral picker API is incompatible with this IDE build", e)
        }
    }

    /** Under the read action opened by [buildModelAndShow]: one [NotifyingItem] per property — the 11
     *  [PROPERTY_NAMES] Original's `@Preview` values are seeded from ([seedValues]), plus the 10
     *  [HARDWARE_PROPERTY_NAMES] the rich inspector layout's "Hardware" section requires ([hardwareSeedValues],
     *  Fix round 2) — merging in [override]'s own values, which always win (the copy's own prior edits). Returned
     *  as a plain item list (not yet wrapped in a model) so [showPopup] can build either inspector layout from
     *  the same items. */
    private fun buildItems(
        seedElement: SingleComposePreviewElementInstance<*>,
        override: ViewOverride,
        onEdit: (String, String) -> Unit,
    ): List<PsiPropertyItem> {
        val seeds = seedValues(seedElement) + hardwareSeedValues(seedElement.configuration.deviceSpec)
        return (PROPERTY_NAMES + HARDWARE_PROPERTY_NAMES).map { name ->
            val defaultValue = override.values[name] ?: seeds[name].orEmpty()
            NotifyingItem(name, defaultValue, onEdit)
        }
    }

    /**
     * [seedElement]'s own values, as picker-facing strings keyed by [PROPERTY_NAMES] (the same property names
     * [ViewOverride] and [OverrideMerge] use — javap-confirmed against AS's own `ConstantsKt`; see the task-10
     * report). Layoutlib's "unset" sentinels ([UNDEFINED_API_LEVEL], [UNDEFINED_DIMENSION],
     * [UNSET_UI_MODE_VALUE]) are shown as blank rather than their raw (e.g. `-1`) value, matching how Original's
     * own PSI-backed picker shows an unspecified `@Preview` argument. This is display-only — it never affects
     * [ViewOverride] contents or the render (which reads only actual edits via [NotifyingItem.onEdit]) — so an
     * imperfect seed for a rarely-touched axis is a cosmetic risk (spec V2), not a correctness one.
     */
    private fun seedValues(seedElement: SingleComposePreviewElementInstance<*>): Map<String, String> {
        val config = seedElement.configuration
        val background = seedElement.displaySettings.background
        return mapOf(
            "device" to config.deviceSpec,
            "apiLevel" to (if (config.apiLevel == UNDEFINED_API_LEVEL) "" else config.apiLevel.toString()),
            "locale" to config.locale,
            "fontScale" to config.fontScale.toString(),
            "uiMode" to (if (config.uiMode == UNSET_UI_MODE_VALUE) "" else config.uiMode.toString()),
            "showSystemUi" to seedElement.displaySettings.showDecoration.toString(),
            "showBackground" to (background is PreviewDisplaySettings.Background.Color).toString(),
            "backgroundColor" to ((background as? PreviewDisplaySettings.Background.Color)?.color.orEmpty()),
            "widthDp" to (if (config.width == UNDEFINED_DIMENSION) "" else config.width.toString()),
            "heightDp" to (if (config.height == UNDEFINED_DIMENSION) "" else config.height.toString()),
            "wallpaper" to (if (config.wallpaper < 0) "" else config.wallpaper.toString()),
        )
    }

    /**
     * Fix round 2: the "Hardware" section's 10 capitalized sub-properties, javap-confirmed (`HardwarePanelHelperKt`
     * bytecode; see the task-10 report) as the exact set `PreviewPropertiesInspectorBuilder.attachToInspector`
     * reads by name — "Width"/"Height"/"DimensionUnit" specifically are read via `Intrinsics.checkNotNull` inside
     * `createDimensionLine` and THROW (an `NPE`, not a graceful skip) if absent from the model's properties;
     * the other seven (`Device`/`Density`/`Orientation`/`IsRound`/`ChinSize`/`Cutout`/`Navigation`) merely log a
     * WARN and skip that row if missing (`addHardwareView$addSinglePropertyLine`'s `ifnonnull` check) — all ten
     * are supplied here regardless, both to prevent the throw and to match Original's own Hardware section
     * completeness (D4) instead of leaving gaps.
     *
     * [DeviceConfig.Companion.toDeviceConfigOrNull] (public, `android.jar`) is AS's own parser for a `"spec:..."`
     * device-spec string — the SAME parser AS's own device-config UI uses — into exactly this axis set; called
     * with an empty devices collection since this plugin has no device catalog wired up here (safe: javap-confirmed
     * the function returns `null` cleanly, never throws, for `null`/non-`"spec:"` input, which covers both
     * [com.android.tools.preview.NO_DEVICE_SPEC] and an `"id:..."` reference this call can't resolve without a
     * devices list). [DEFAULT_HARDWARE_CONFIG] — a plugin-chosen literal, not reverse-engineered from AS's own
     * default-parameter expressions (disproportionate effort for a display-only seed) — covers that `null` case,
     * per the review's own "seed with a default device rather than inventing one" guidance.
     *
     * Enum axes (`DimensionUnit`/`Orientation`/`Cutout`/`Navigation`) are seeded with the enum constant's own
     * `name` (`dp`/`portrait`/`none`/`gesture`, ...) — confirmed via javap on each enum class directly, not
     * guessed — matching what `PsiEnumProvider.invoke` resolves these four names to: AS's own hardcoded
     * `DimensionUnitEnumSupport`/`OrientationEnumSupport`/`CutoutEnumSupport`/`NavigationEnumSupport` singletons,
     * which never consult this bridge's own [EnumSupportValuesProvider] at all (only `Device` does, via
     * `DevicesEnumSupportKt.createDeviceEnumSupport`) — so these four dropdowns work identically regardless of
     * what this bridge supplies as enum values, confirmed by the same bytecode read.
     *
     * **Scope note:** these 10 axes are seeded, editable, and become [ViewOverride] entries like any other
     * (`NotifyingItem`'s `onEdit` is name-agnostic) — but, like `showBackground`/`backgroundColor` before this
     * round, they are not yet folded into [RenderModelResolver]'s render-application path (`mergeConfiguration`/
     * `mergeBackground` only read the lowercase `device`/`widthDp`/`heightDp` axes). Wiring a `Width`/`Height`
     * edit back into an updated device spec was not part of this fix's scope (making the dialog open reliably);
     * a recorded boundary for the runIde gate, not silently dropped.
     */
    private fun hardwareSeedValues(deviceSpec: String): Map<String, String> {
        val config = DeviceConfig.toDeviceConfigOrNull(deviceSpec, emptyList()) ?: DEFAULT_HARDWARE_CONFIG
        return mapOf(
            "Device" to deviceSpec,
            "Width" to config.widthString,
            "Height" to config.heightString,
            "DimensionUnit" to config.dimUnit.name,
            "Density" to config.dpi.toString(),
            "Orientation" to config.orientation.name,
            "IsRound" to config.isRound.toString(),
            "ChinSize" to config.chinSizeString,
            "Cutout" to config.cutout.name,
            "Navigation" to config.navigation.name,
        )
    }

    /**
     * On the EDT, once [items]/[enumProvider] are ready. [at]'s anchor component may have stopped showing while
     * [buildModelAndShow] was resolving/building them off the EDT — mirrors [PreviewPickerBridge.showPopup]'s
     * identical re-check and rationale.
     *
     * Fix round 2 (Fix B): tries AS's own hardware-grouped [PreviewPropertiesInspectorBuilder] layout first —
     * the one that failed in the gate (`HardwarePanelHelperKt.createDimensionLine`'s `checkNotNull`, now fixed by
     * [hardwareSeedValues], but guarded again here in case some *other* future shape change makes it throw again
     * for a different reason) — and, only if that attempt fails, retries ONCE with [FlatInspectorBuilder]: the
     * same AS widgets and items, just without the Hardware/Dimensions grouping (`PsiPropertiesInspectorBuilder`'s
     * own base `attachToInspector`, javap-confirmed to be a plain `addEditorsForProperties(inspector,
     * properties.values)` with no name-keyed lookups at all — nothing in it can throw the way the rich layout
     * did). The exception happens during panel construction, before `PsiPickerManager.show` realizes any popup
     * on screen (per the gate's own stack trace: the throw originates inside `createPickerPanel`, called before
     * the popup is shown), so retrying is safe — no leftover UI artifact from the failed first attempt. Which
     * layout actually got shown (or that both failed) is logged at INFO/WARN so the gate can tell them apart.
     */
    private fun showPopup(
        entry: PreviewEntry,
        at: RelativePoint,
        items: List<PsiPropertyItem>,
        enumProvider: EnumSupportValuesProvider,
    ) {
        if (!at.component.isShowing) return
        if (tryShow(entry, at, items, PreviewPropertiesInspectorBuilder(enumProvider), "rich (hardware-grouped)")) return
        thisLogger().info(
            "Rich ephemeral picker layout failed; retrying with a flat layout for ${entry.indexed.composableFqn}",
        )
        tryShow(entry, at, items, FlatInspectorBuilder(enumProvider), "flat")
    }

    /** One guarded [PsiPickerManager.show] attempt with [builder] as the model's inspector layout. Returns
     *  whether it succeeded, so [showPopup] can retry with a different [builder] on `false` — never throws
     *  except [ProcessCanceledException], matching every other AS-internal call site in this class (design §5). */
    private fun tryShow(
        entry: PreviewEntry,
        at: RelativePoint,
        items: List<PsiPropertyItem>,
        builder: PsiPropertiesInspectorBuilder,
        layoutName: String,
    ): Boolean = try {
        PsiPickerManager.show(at.screenPoint, entry.indexed.displayName, EphemeralModel(items, builder), Balloon.Position.below)
        thisLogger().info("Ephemeral picker shown ($layoutName layout) for ${entry.indexed.composableFqn}")
        true
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().warn("Could not open the ephemeral picker ($layoutName layout) for ${entry.indexed.composableFqn}", e)
        false
    } catch (e: LinkageError) {
        thisLogger().warn("Ephemeral picker API is incompatible with this IDE build ($layoutName layout)", e)
        false
    }

    /**
     * A [MemoryParameterPropertyItem] whose `value` setter is the ONLY change hook for a memory item
     * (javap-confirmed: its `ComposePickerTracker` is never notified — see the task-10 report). `getValue()`/
     * `setValue(String)` javap as plain getter/setter-shaped methods, but the Kotlin compiler resolves them
     * against `MemoryParameterPropertyItem`'s real `@Metadata` as a single `open var value: String?` property —
     * overriding it therefore needs `override var value`, not `override fun setValue` (the brief's illustrative
     * snippet; see the task-10 report for how this was found, the same way as [EphemeralModel]'s members below).
     *
     * [defaultValue] seeds the property via `super.value = ...` in [init] — NOT through this class's own
     * overridden setter — because [MemoryParameterPropertyItem] never initializes its backing field itself
     * (javap: its constructor sets only `name`/`defaultValue`/`editingSupport`), so `getValue()` would otherwise
     * return `null` and the picker would show a blank field instead of the seeded value. Going through `super`
     * for the seed also means seeding never fires [onEdit] — opening Properties on an untouched copy must not, by
     * itself, count as an edit (D2/AC2: "no setting is required"); only a REAL user edit — which always arrives
     * through the picker's own editor writing this class's overridden `value` — does.
     */
    private class NotifyingItem(
        name: String,
        defaultValue: String,
        private val onEdit: (String, String) -> Unit,
    ) : MemoryParameterPropertyItem(name, defaultValue, { EditingErrorCategory.NONE to "" }) {
        init {
            super.value = defaultValue
        }

        override var value: String?
            get() = super.value
            set(newValue) {
                super.value = newValue
                if (newValue != null) onEdit(name, newValue)
            }
    }

    /**
     * The in-memory [PsiPropertiesModel] (design D4): [properties] never touches PSI (a plain [PropertiesTable]
     * of [items]), [inspectorBuilder] reuses AS's own [PreviewPropertiesInspectorBuilder] for the identical
     * Hardware/Display layout Original's picker shows, and [tracker] is a no-op [GalleryPickerTracker] — real
     * per-item change notification is [NotifyingItem]'s own overridden `value` setter calling `onEdit` directly,
     * not the tracker (see [NotifyingItem]'s own doc).
     *
     * These three are overridden as Kotlin `val` PROPERTIES, not `fun`s: although javap shows
     * `PsiPropertiesModel`/`PropertiesModel<P>`'s abstract members as plain `getXxx()`-shaped JVM methods
     * (indistinguishable, at that level, from a Kotlin function of the same name), the Kotlin compiler reads the
     * real declaration from the class's `@Metadata` and rejects a `fun` override with "overrides nothing" — this
     * differed from the brief's illustrative `override fun` snippet; see the task-10 report.
     */
    private class EphemeralModel(
        private val items: List<PsiPropertyItem>,
        private val builder: PsiPropertiesInspectorBuilder,
    ) : PsiPropertiesModel() {
        override val properties: PropertiesTable<PsiPropertyItem>
            get() {
                val table = HashBasedTable.create<String, String, PsiPropertyItem>()
                items.forEach { table.put(it.namespace, it.name, it) }
                return PropertiesTable.create(table)
            }

        /** Supplied by [showPopup] rather than built here, so the same items can be shown through either the rich
         *  hardware-grouped layout or [FlatInspectorBuilder]'s fallback (Fix round 2). */
        override val inspectorBuilder: PsiPropertiesInspectorBuilder = builder

        override val tracker: ComposePickerTracker = GalleryPickerTracker {}
    }

    /**
     * The fallback layout (Fix round 2, Fix B): [PsiPropertiesInspectorBuilder]'s own base `attachToInspector`,
     * which is a plain `addEditorsForProperties(inspector, properties.values)` with no name-keyed lookups
     * (javap-confirmed) — so, unlike [PreviewPropertiesInspectorBuilder]'s hardware grouping, nothing in it can
     * throw over a property it expected but did not find. Same AS editors and items, just ungrouped.
     *
     * The abstract member it must supply is `editorProvider`; AS's own preview implementation pairs
     * [PsiEditorProvider] with a control-type mapping, but AS's `PreviewControlTypeProvider` is a file-private
     * class (javap: no `public` modifier — a Kotlin file-level `private class`), so it is unreachable from this
     * package at runtime regardless of Kotlin visibility suppression. [EphemeralControlTypeProvider] below is
     * therefore our own, mirroring the mapping AS's own provider applies to these same property names.
     */
    private class FlatInspectorBuilder(enumProvider: EnumSupportValuesProvider) : PsiPropertiesInspectorBuilder() {
        private val provider: EditorProvider<PsiPropertyItem> =
            PsiEditorProvider(PsiEnumProvider(enumProvider), EphemeralControlTypeProvider)

        override val editorProvider: EditorProvider<PsiPropertyItem> get() = provider
    }

    /**
     * Which editor widget each property name gets in the fallback layout. Mirrors the mapping AS's own
     * (unreachable, file-private) `PreviewControlTypeProvider` applies — dropdowns for the enumerated axes,
     * checkboxes for the booleans, a colour editor for `backgroundColor`, a combo for `fontScale`, and a plain
     * text editor for anything else — so a property looks the same here as it does in the rich layout.
     */
    private object EphemeralControlTypeProvider : ControlTypeProvider<PsiPropertyItem> {
        override fun invoke(property: PsiPropertyItem): ControlType = when (property.name) {
            "showSystemUi", "showBackground", "IsRound" -> ControlType.BOOLEAN
            "backgroundColor" -> ControlType.COLOR_EDITOR
            "fontScale" -> ControlType.COMBO_BOX
            "device", "apiLevel", "locale", "uiMode", "wallpaper",
            "Device", "Orientation", "Density", "DimensionUnit", "Cutout", "Navigation",
            -> ControlType.DROPDOWN
            else -> ControlType.TEXT_EDITOR
        }
    }

    private companion object {
        /** Android Studio's own `@Preview` picker property names (javap-confirmed against `ConstantsKt`'s
         *  `PARAMETER_*` string constants — see the task-10 report). Must match [ViewOverride]'s documented key
         *  set and [OverrideMerge.merge]'s hardcoded lookups exactly, since [NotifyingItem.onEdit] is what
         *  populates a copy's [ViewOverride]. */
        private val PROPERTY_NAMES = listOf(
            "device", "apiLevel", "locale", "fontScale", "uiMode",
            "showSystemUi", "showBackground", "backgroundColor", "widthDp", "heightDp", "wallpaper",
        )

        /** The capitalized "Hardware" sub-properties `PreviewPropertiesInspectorBuilder` reads by name (see
         *  [hardwareSeedValues]). "Width"/"Height"/"DimensionUnit" are read through `checkNotNull` inside
         *  `createDimensionLine` and throw when absent — which is exactly what kept the dialog from opening
         *  before Fix round 2 — so every one of them is supplied. */
        private val HARDWARE_PROPERTY_NAMES = listOf(
            "Device", "Width", "Height", "DimensionUnit", "Density",
            "Orientation", "IsRound", "ChinSize", "Cutout", "Navigation",
        )

        /** The seed used when a preview's device spec cannot be parsed into hardware axes (an `"id:..."`
         *  reference, [com.android.tools.preview.NO_DEVICE_SPEC], or anything else
         *  `DeviceConfig.toDeviceConfigOrNull` returns null for). `DeviceConfig()`'s own defaults — AS's, not
         *  ours — keep this a display-only seed rather than an invented device. */
        private val DEFAULT_HARDWARE_CONFIG = DeviceConfig()
    }
}
