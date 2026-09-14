# Preview Property Picker and Comparison Views

**Status:** shipped · **Phases:** PG3, PG4-1, PG6 · **Last change:** 346bbdd [PG6-10] 2026-07-28

Two features share this document because they share one mechanism: driving Android Studio's own `@Preview`
property picker UI. The **property picker** opens that dialog for the selected preview and writes edits straight
into the source `@Preview` annotation, refreshing the render in place. **Comparison views** duplicate the current
preview into ephemeral tabs, each configured through the *same* dialog running over an in-memory model instead of
PSI, so a copy's settings never touch source. Both are AS-internal, isolated to `render/`, gated by their own
`RenderApiProbe` checks, and simply absent — never a dead control — when the underlying API is missing.

## What the user gets

- **Properties** on the render panel toolbar, shown only with a preview selected on the Original tab and the
  picker API available: opens Android Studio's own `@Preview` picker, pre-filled with that preview's current
  arguments. Any change is written into the source `@Preview` annotation — exactly like the editor's own gutter
  picker — and the render refreshes in place, without reselecting the preview.
- **＋ Add view** on the toolbar, shown once a live Original image exists and the device-override capability is
  available: appends a tab that is an exact, untouched copy of Original. No setting is required to add it.
- Every extra tab is a fully independent, zoomable/pannable render with its own hover/click-to-source overlay; the
  toolbar's zoom/fit/hand-tool/Save PNG/Copy always act on whichever tab is currently active, never always
  Original.
- **Properties** on an extra tab opens the *same* Android Studio dialog Original uses, seeded from that tab's own
  current values and backed by an in-memory model: every edit re-renders only that tab and is summarised live in
  its tab title (e.g. `device id:pixel_7 · fontScale 1.3`). The `@Preview` source is never touched from a copy.
- Up to 5 extra tabs at once (`ComparisonViewList.DEFAULT_MAX_EXTRAS`); each has its own close button (Original
  cannot be closed); a render failure inside one tab shows a retry link scoped to that tab only.
- Selecting a different preview immediately discards every extra tab and its cached image — comparison views
  never survive a preview switch, a tool-window close, or an IDE restart.
- Both controls are simply absent on an IDE build missing the underlying capability; nothing else in the gallery
  is affected.

## How it works

[PreviewGalleryPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) owns both
bridges (`pickerBridge = PreviewPickerBridge(project)`, `ephemeralPickerBridge = EphemeralPickerBridge(project)`)
and probes both capabilities once, before the first `show()`: `propertiesAvailable = pickerBridge.isAvailable()`,
`deviceOverrideAvailable = RenderApiProbe.isViewOverrideAvailable()`.

**Original's Properties.** `PreviewRenderPanel.PropertiesAction` reads the active tab
(`activeComparisonView()`); with Original active it calls `onProperties(entry, anchor)` →
`PreviewPickerBridge.showPicker`. `showPicker` captures `ModalityState` on the EDT (the button's own
`ActionListener`) then hops to a background executor; `buildModelAndShow` resolves the `@Preview`
`KtAnnotationEntry` ([PreviewAnnotationLocator](../../../src/main/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocator.kt))
and the owning module inside one read action, and builds
`PreviewPickerPropertiesModel.fromPreviewElement(...)` with a `GalleryPickerTracker(onModification)` as its
tracker. `ApplicationManager.invokeLater` at the captured modality then runs `showPopup` back on the EDT, which
re-checks `at.component.isShowing` (the anchor may have stopped showing while the background work was in flight)
before `PsiPickerManager.show(...)`. Android Studio writes the edited value straight into the `@Preview` source
PSI (design D1) — this plugin never touches PSI itself, so undo, the index, and the preview tree all pick the
change up through the existing paths.

**Refreshing on a picker edit.** Every edit calls `GalleryPickerTracker.registerModification`, which forwards to
`onModification` — wired to `RenderPipeline.rerenderCurrent()`. `RenderPipeline.select()` tracks its own
`currentEntry` *synchronously*, not only inside the debounced dispatch (4d16e51 [PG4-1]), so a picker edit that
races a fresh selection still re-renders the entry the picker actually opened for, not a stale one; both fields
are `@Volatile` because the tracker's calling thread is documented as the EDT but not contractually guaranteed.
`rerenderCurrent()` goes through the same debounced `Alarm` and `generation` guard as every other render path.

**A copy tab's Properties.** With a non-Original tab active, `PropertiesAction` instead calls
`onEphemeralProperties(entry, view.override, anchor, onEdit)` →
[EphemeralPickerBridge.showEphemeralPicker](../../../src/main/kotlin/com/devomer/previewgallery/render/EphemeralPickerBridge.kt).
Off the EDT, lock-free and before any read action, it seeds every axis from the entry's own current `@Preview`
values via `RenderModelResolver.resolveCurrentElement` (config-aware when available, else the layoutlib default —
the same resolution a render itself would use), merged with the copy's own `ViewOverride` (which always wins).
One read action then resolves the module, asks AS's own `PreviewPickerValuesProvider` for the dropdown/enum
values, and builds one `NotifyingItem` per property: the 11 top-level `@Preview` axes plus the 10 capitalised
"Hardware" sub-axes the rich inspector layout needs. Back on the EDT, `showPopup` tries AS's own
`PreviewPropertiesInspectorBuilder` hardware-grouped layout first, retrying once with a flat
`PsiPropertiesInspectorBuilder` layout if the rich one throws. A `NotifyingItem`'s overridden `value` setter — not
the tracker — is the one edit signal: it calls `onEdit(name, newValue)`, which `PreviewRenderPanel` folds into
`comparisonViews.setOverride(view.id, current.override.with(name, value))` (re-reading the model fresh each time,
so a multi-edit session never drops an earlier edit), refreshes that tab's title
([ViewTitle.of](../../../src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt)), and re-renders just that
tab.

**Rendering a tab.** `renderInto(view)` calls `onRequestVariant(entry, view.override, callback)` →
`RenderPipeline.renderVariant` — a dedicated, non-debounced, off-EDT entry point that touches neither the single
Original `generation` counter nor `currentEntry`, so N tabs render independently without one superseding another
— → `LiveRenderer.render(entry, override)` → `RenderModelResolver.resolve(entry, project, override, ...)`.
Inside `resolveUnderReadAction`, `applyOverride` is mapped over every resolved instance (so a `@PreviewParameter`
preview's whole set inherits the same override): a `null` or default override returns the base element
unchanged; otherwise `deriveOverriddenElement` merges the override onto the base's `PreviewConfiguration`
(`mergeConfiguration` / [OverrideMerge](../../../src/main/kotlin/com/devomer/previewgallery/render/OverrideMerge.kt),
preserving every un-edited axis explicitly because AS's own `PreviewConfiguration.cleanAndGet` treats `null` as
"reset to the layoutlib sentinel", never "keep") and its display settings (`mergeBackground` for
`showBackground`/`backgroundColor`, `showDecoration` for `showSystemUi`), calls
`base.createDerivedInstance(display, merged)`, then re-runs the same `applyConfigAware` seam an ordinary
`@Preview`'s own configuration goes through. A failure at any step degrades back to the base element, never fails
the whole render. `renderInto` guards its own async result with a per-tab generation token
(`extraGenerations`), independent of `RenderPipeline`'s.

**Lifecycle.** `PreviewRenderPanel.show()` compares the incoming entry by `PreviewEntry.id` (not reference), and
only a genuinely different id calls `clearComparisonExtras()` — which tears down the pure `ComparisonViewList`
model, every staleness token, and every Swing widget/image together; removing the widgets from `viewTabs` is what
actually frees the rendered images.

| Class / file | Responsibility |
|---|---|
| [PreviewPickerBridge](../../../src/main/kotlin/com/devomer/previewgallery/render/PreviewPickerBridge.kt) | Opens AS's real, source-backed `@Preview` picker for Original; resolves the annotation and builds the model off the EDT |
| [GalleryPickerTracker](../../../src/main/kotlin/com/devomer/previewgallery/render/GalleryPickerTracker.kt) | No-op `ComposePickerTracker`; forwards "a value changed" so the panel can re-render |
| [EphemeralPickerBridge](../../../src/main/kotlin/com/devomer/previewgallery/render/EphemeralPickerBridge.kt) | The same AS picker UI over an in-memory `PsiPropertiesModel`, for one comparison-view copy; never touches PSI |
| [OverrideMerge](../../../src/main/kotlin/com/devomer/previewgallery/render/OverrideMerge.kt) | Pure, base-preserving axis merge behind the override (spec V4's `cleanAndGet` trap) |
| [RenderModelResolver](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt) — `applyOverride`/`deriveOverriddenElement`/`mergeConfiguration`/`mergeBackground`/`resolveCurrentElement` | Applies a `ViewOverride` onto the resolved preview element before render |
| [RenderApiProbe](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt) — `isPickerAvailable`/`isViewOverrideAvailable` | Reflective capability gates for the two pickers |
| [PreviewAnnotationLocator](../../../src/main/kotlin/com/devomer/previewgallery/render/PreviewAnnotationLocator.kt) | Re-resolves the `@Preview` `KtAnnotationEntry` an indexed entry was built from |
| [ViewOverride](../../../src/main/kotlin/com/devomer/previewgallery/model/ViewOverride.kt) | Plugin-owned name→value override map; one comparison copy's state |
| [ComparisonViewList](../../../src/main/kotlin/com/devomer/previewgallery/ui/ComparisonViewList.kt) | Pure ephemeral tab model: Original at index 0, plus up to 5 copies |
| [ViewTitle](../../../src/main/kotlin/com/devomer/previewgallery/ui/ViewTitle.kt) | Pure tab-title derivation from a view's override |
| [PreviewRenderPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt) — tab strip, `PropertiesAction`, `AddViewAction`, `renderInto` | Hosts the tab strip, routes Properties by active tab, renders each copy |
| [PreviewGalleryPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) | Owns both bridges, probes both capabilities once, wires every callback |
| [RenderPipeline](../../../src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt) — `rerenderCurrent`/`renderVariant` | Re-renders in place after a picker edit; independent per-tab render entry point |

## Key decisions

- Picker edits are written to the source `@Preview` annotation, not a temporary/side config — the least code, and
  it reuses the existing index/tree refresh path — source: picker spec D1.
- Reuse Android Studio's own `PsiPickerManager` dialog rather than build a plugin UI — the whole picker, including
  every enumeration, comes for free — source: picker spec D2.
- Missing picker API ⇒ the Properties button is simply not added, never shown-and-disabled — source: picker spec
  D4, `RenderApiProbe.isPickerAvailable`.
- PG3-7: resolving the annotation and building `PreviewPickerPropertiesModel` moved off the EDT — only
  `PsiPickerManager.show` itself still runs there — because building the model (the Device/apiLevel/locale
  enumerations) was slow enough to feel worse than the editor's own gutter picker — source: 3cd879c [PG3-7].
- PG4-1: `RenderPipeline` tracks `currentEntry` synchronously inside `select()`, not only inside the debounced
  dispatch, so a picker edit racing a fresh selection targets the right entry — source: 4d16e51 [PG4-1] (the
  property-picker plan drafted this re-render step as its own Task 3/`[PG3-3]`; it shipped instead once phase
  numbering had moved on to PG4).
- A comparison-view tab starts as an untouched clone of Original, no setting prompt required to add one; Properties
  on a copy drives the *same* AS dialog Original uses, but over an in-memory `PsiPropertiesModel`
  (`EphemeralPickerBridge`) instead of a reduced, plugin-drawn popup — verified that the picker's source-writing
  lives in its property items (`PsiCallParameterPropertyItem.setValue`), not its UI, so swapping only the model
  keeps every AS widget and enumeration — source: comparison-views spec D2/D4/D5, 346bbdd [PG6-10].
- The override carries the picker's full property set (11 axes) applied via `createDerivedInstance` + AS's own
  `applyTo`, replacing the interim three-axis `setDevice`/`setNightMode`/`setFontScale` mechanism entirely (not
  extended alongside it) — one override mechanism, one mapping — source: comparison-views spec D5, 62c0027
  [PG6-9].
- `PreviewConfiguration.cleanAndGet` treats a `null` argument as "reset to the layoutlib sentinel", never "keep
  the current value" — every axis the user did not edit must be threaded through from the base config explicitly.
  `OverrideMerge` exists solely to make this a pure, unit-tested contract instead of a gate-only risk — source:
  comparison-views spec V4, [OverrideMerge.kt](../../../src/main/kotlin/com/devomer/previewgallery/render/OverrideMerge.kt).
- Comparison views are strictly ephemeral: selecting a different preview frees every copy and its cached image
  immediately; nothing survives a tool-window close or an IDE restart — source: comparison-views spec D7,
  `PreviewRenderPanel.clearComparisonExtras`.
- `RenderPipeline.renderVariant` is a dedicated, non-debounced, off-EDT entry point independent of the Original
  selection's `generation`/`currentEntry`, so N tabs render concurrently without one cancelling another — source:
  comparison-views spec D8.
- The render toolbar's zoom/fit/hand-tool/export controls always target the *active* tab, never always Original —
  source: comparison-views spec D10, `PreviewRenderPanel.activeView`/`activeComparisonView`.
- At most `ComparisonViewList.DEFAULT_MAX_EXTRAS` (5) copies at once; `＋ Add view` silently no-ops past the cap —
  source: `ComparisonViewList.kt`.
- **Spec vs. code:** the comparison-views spec's own Non-Goals section still lists "Locale / RTL axis" and "a
  small curated device list only" as deferred/limited. The shipped code disagrees on both: `locale` has been a
  live override axis since 62c0027 [PG6-9] (`OverrideMerge.merge`, `ViewOverride`'s own doc), and since 346bbdd
  [PG6-10] the ephemeral picker reuses AS's own `PreviewPickerValuesProvider` — the identical enum provider
  Original's picker uses — so the full device catalog is available to a copy, not a curated subset. Code (and the
  same spec's own D5) win over those two stale Non-Goals lines.

## Android Studio and platform internals relied on

- Two independent reflective gates in `RenderApiProbe`: `pickerRequired` (`PsiPickerManager.show`,
  `PreviewPickerPropertiesModel` + `Companion.fromPreviewElement`, `ComposePickerTracker`) behind
  `isPickerAvailable()` gates Original's Properties button. `viewOverrideRequired`
  (`ComposePreviewElementInstance.createDerivedInstance`, `PsiPickerManager.show` again,
  `PsiPropertiesModel.{getInspectorBuilder,getTracker,getProperties}`,
  `MemoryParameterPropertyItem.{getValue,setValue}`, `PreviewPropertiesInspectorBuilder`,
  `PreviewPickerValuesProvider.createPreviewValuesProvider`, `PropertiesTable.Companion.create`,
  `ComposePickerTracker`, `EditingErrorCategory`) behind `isViewOverrideAvailable()` gates **both** ＋ Add view
  and a copy tab's Properties — one flag for both, since re-rendering a copy needs the same capability that
  adding one does.
- Both picker APIs live in the bundled `com.android.tools.design` plugin (`design-tools.jar`), a separate
  dependency from `org.jetbrains.android` (where the renderer's own API lives) — declared as
  `bundledPlugins(...)` in [build.gradle.kts](../../../build.gradle.kts) and
  `<depends>com.android.tools.design</depends>` in
  [plugin.xml](../../../src/main/resources/META-INF/plugin.xml), added in 21f61b4 [PG3-1] once the design doc's
  original guess (`org.jetbrains.android`) turned out wrong.
- `@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")` at the top of `PreviewPickerBridge.kt`,
  `GalleryPickerTracker.kt` and `EphemeralPickerBridge.kt`: `PsiPickerManager`, `PreviewPickerPropertiesModel`,
  `ComposePickerTracker` and, for the ephemeral bridge, `PsiPropertiesModel`, `MemoryParameterPropertyItem`,
  `PsiPropertyItem`, `PsiPropertiesInspectorBuilder`, `PreviewPropertiesInspectorBuilder`,
  `EnumSupportValuesProvider` and `PreviewPickerValuesProvider` are Kotlin-`internal` inside their own
  `compose-designer` module while JVM-`public` (`javap`-confirmed) — Kotlin enforces the source-level visibility
  across module boundaries regardless of the bytecode modifier. kotlinc flags every such use with its own
  standard, Kotlin-project-wide caveat ("Suppression of error 'INVISIBLE_REFERENCE' might compile and work, but
  the compiler behavior is UNSPECIFIED and WILL NOT BE PRESERVED"). The risk this carries is bounded the same way
  as any other AS-internal call here: every one sits inside the `Exception`/`LinkageError` guard, so a member
  rename or removal — by a Kotlin compiler change or an AS upgrade — degrades to a logged no-op, never a crash.
- Threading is identical across both bridges: the button's own EDT `ActionListener` captures
  `ModalityState.defaultModalityState()`, then hands off to `AppExecutorUtil.getAppExecutorService()`. Resolving
  the PSI/project-model pieces and building the (real or in-memory) property model happen together inside **one**
  background `ReadAction.compute`. Only `PsiPickerManager.show` itself runs back on the EDT
  (`ApplicationManager.invokeLater` at the captured modality), re-checking `at.component.isShowing` first since
  the anchor may have stopped showing while the background work was in flight. `showPicker`/
  `showEphemeralPicker`'s boolean return means only "dispatched", never "shown".
- The change signal back into a render is documented, not contractually threaded: `ComposePickerTracker`'s
  callbacks and `NotifyingItem`'s `value` setter are said to fire on the EDT, but `RenderPipeline` still treats
  `currentEntry`/`generation` as `@Volatile` and drives everything through its thread-safe `Alarm` rather than
  trusting that guarantee.
- `EphemeralPickerBridge`'s seed lookup (`RenderModelResolver.resolveCurrentElement`) must run lock-free, *before*
  any read action, mirroring the config-aware finder it wraps: that finder is a `suspend` function that acquires
  its own (smart) read access, and calling it while a read action is already held previously froze the whole IDE
  on selection (see the rendering doc's PG3-5/PG4-2 fix) — the ephemeral picker inherits that same discipline
  instead of reopening the bug for a second entry point.

## Tests

- [OverrideMergeTest](../../../src/test/kotlin/com/devomer/previewgallery/render/OverrideMergeTest.kt) — the
  base-preserving merge contract: an empty override returns the base unchanged, each of the 8 mapped axes
  overrides only itself, and an unparseable value falls back to the base, never a sentinel.
- [ComparisonViewListTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ComparisonViewListTest.kt) —
  start state (Original only), add/cap/close/setOverride/clearExtras, non-reused ids.
- [ViewOverrideTest](../../../src/test/kotlin/com/devomer/previewgallery/model/ViewOverrideTest.kt) — `isDefault`,
  and `with` is immutable, replaces an existing key, and keeps the others.
- [ViewTitleTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ViewTitleTest.kt) — Original / `View N` /
  single- and multi-axis summaries in insertion order.
- [RenderApiProbeTest](../../../src/test/kotlin/com/devomer/previewgallery/render/RenderApiProbeTest.kt) — asserts
  `isPickerAvailable()`/`isViewOverrideAvailable()` (alongside every other capability) are true against the IDE
  build the plugin compiles against, so a name/signature drift in either reflective list fails this test instead
  of failing silently at runtime.
- [PreviewRenderPanelTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt) —
  only the Properties action's *visibility* by state (present for a live render, absent for both snapshot
  states); does not exercise the comparison-view tab strip itself.
- **Test gaps:** no automated test drives the tab strip's own behaviour (＋ Add view, per-tab render dispatch,
  `PropertiesAction`'s Original-vs-copy routing, close, `clearComparisonExtras` on selection change) or either
  bridge (`PreviewPickerBridge`, `EphemeralPickerBridge`, `GalleryPickerTracker`) directly — by design, per both
  specs' own Testing sections, this is Swing/PSI/AS-internal surface verified only manually in `runIde`.
  `RenderModelResolver`'s override-application path itself (`applyOverride`/`deriveOverriddenElement`/
  `mergeBackground`) has no dedicated test file either; only the pure math it delegates to (`OverrideMerge`) is
  unit-tested, and the AS-type conversion around it is exercised solely by the `runIde` gate.

## Open items

- [gap] Editing one of the ephemeral picker's 10 capitalised "Hardware" sub-rows (`Width`, `Height`,
  `DimensionUnit`, `Density`, `Orientation`, `IsRound`, `ChinSize`, `Cutout`, `Navigation`, and a capitalised
  `Device`) has no effect on the render: the edit becomes a `ViewOverride` entry like any other, but
  `OverrideMerge`/`mergeConfiguration`/`mergeBackground` only read the lowercase `device`/`widthDp`/`heightDp`/...
  axes — source: [EphemeralPickerBridge.kt](../../../src/main/kotlin/com/devomer/previewgallery/render/EphemeralPickerBridge.kt)
  (`hardwareSeedValues`'s own "Scope note").
- [idea] Promote a comparison view to a committed snapshot variant — the override already holds everything needed
  — but it is blocked on the (deferred) snapshot-generating PSI writer — source:
  [snapshot-testing-roadmap.md § F4](../../snapshot-testing-roadmap.md).
- [limitation] Comparison views are tabs, one visible at a time — no side-by-side grid/contact sheet — by design,
  called out as a separate, later spec — source: comparison-views spec, Non-Goals.
- [limitation] Export (Save PNG / Copy) acts on the single active tab only; there is no batch/sheet export across
  every comparison copy — by design — source: comparison-views spec, Non-Goals.
- [limitation] At most 5 extra comparison tabs at once; `＋ Add view` silently no-ops past the cap rather than
  surfacing an error — source: `ComparisonViewList.kt`.
- [limitation] Comparison-view state is never persisted: switching preview, closing the tool window, or
  restarting the IDE loses every copy and its overrides — by design — source: comparison-views spec D7.
- [debt] No automated test exercises the tab-strip UI itself or either AS picker bridge; coverage stops at the
  pure model classes (`ComparisonViewList`, `ViewOverride`, `ViewTitle`, `OverrideMerge`) plus one
  capability/visibility test each — everything else is a manual `runIde` gate (see Tests above).

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG3 (property picker) | 2026-07-24 | a1f7c41 [PG3-1], 21f61b4 [PG3-1], 0c03427 [PG3-2], 3cd879c [PG3-7] | Probed and wired AS's own `@Preview` picker into the render panel behind a Properties button; moved model-building off the EDT. (PG3-5 "Stop the freshness check from freezing the IDE" and PG3-6 "Render off the read action" landed in the same phase but are render-threading fixes — see the rendering doc.) |
| PG4-1 (picker refresh) | 2026-07-25 | a8e3602 [PG4-1], 4d16e51 [PG4-1] | `RenderPipeline.rerenderCurrent()` re-renders in place after a picker edit; `currentEntry` is tracked synchronously in `select()` so a race between a fresh selection and a picker edit cannot target the wrong entry. |
| PG6 v1 — curated device, three axes | 2026-07-26 | ab8b868 [PG6-1], 738956e [PG6-2], 4bf897a [PG6-3] | First cut: a curated device catalog plus a three-axis (`device`/`theme`/`fontScale`) `ViewConfig` applied via `setDevice`/`setNightMode`/`setFontScale`. Superseded below. |
| PG6 v2 — copies, still three axes | 2026-07-27 | ec7375c [PG6-6], 7b1ebdb [PG6-7], d72a2d1 [PG6-7], 22d0b23 [PG6-8] | Revised to "tabs are copies of Original" (rejecting per-tab device pickers); tab titles and context-aware Properties routing land, still on the three-axis config. Superseded below. |
| PG6 v3 — full override, real picker (shipped) | 2026-07-28 | 62c0027 [PG6-9], 346bbdd [PG6-10] | Replaced the three-axis config with `ViewOverride` (the picker's full property set) applied via `createDerivedInstance` + `applyTo`, and replaced the plugin-drawn popup with `EphemeralPickerBridge` — AS's own picker UI over an in-memory model. This is what ships today. |

## References

- [Preview property picker design](../../superpowers/specs/2026-07-24-preview-property-picker-design.md)
- [Preview property picker plan](../../superpowers/plans/2026-07-24-preview-property-picker.md)
- [Comparison views design](../../superpowers/specs/2026-07-26-comparison-views-device-snapshots-design.md)
- [Comparison views plan](../../superpowers/plans/2026-07-26-comparison-views-device-snapshots.md)
- [snapshot-testing-roadmap.md § F3/F4](../../snapshot-testing-roadmap.md) — the deferred "Create snapshot test"
  writer that blocks promoting a comparison view to a variant
- [feature-overview.md § 2](../../feature-overview.md) — the user-facing framing of both features
- [README.md](../../../README.md), [CHANGELOG.md](../../../CHANGELOG.md) — both features shipped under `[0.1.0]`
