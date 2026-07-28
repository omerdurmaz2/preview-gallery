# Comparison Views — Ephemeral Per-Preview View Copies

| | |
|---|---|
| **Scope** | Phase 6 — compare the current preview across view settings (device / theme / font scale) *without editing its `@Preview`*, by adding ephemeral in-memory copies of it as tabs. |
| **Builds on** | [Phase 5](2026-07-26-render-view-zoom-pan-export-design.md) — the zoomable/pannable `ZoomableRenderView` and the config-aware render pipeline. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG6-N]` |

## Goal

Let a user see the *same* preview under more than one set of view settings at once, side-stepping the "edit `@Preview`, lose the old view" loop. The current render stays as the **Original** tab; **＋ Add view** spins up an ephemeral **comparison view** — an in-memory **copy of Original**, rendering identically until the user changes it. Pressing **Properties** while a copy is active opens that copy's own ephemeral **view settings** (device, theme, font scale), re-rendering only that tab. Each tab carries a title reflecting its settings, and is fully zoom/pan/click-to-source. The moment a different preview is selected, every copy (and its cached image) is discarded and memory is freed.

## Non-Goals

- **Side-by-side grid / contact sheet** — the user chose *tabs*, one view visible at a time (a grid is a separate, later spec).
- **Locale / RTL axis** — needs resource resolution; a natural follow-up, not v1.
- **Full AS device catalog or manual width×height entry** — a small *curated* device list only.
- **Persistence** — comparison views are deliberately ephemeral: not saved across a preview switch, tool-window close, or IDE restart.
- **Batch / sheet export** — export stays the existing single-image Save-PNG / Copy, acting on the active tab.
- **Frozen-image snapshots** — a copy is a *live* re-render under its settings, not a static image copy.
- **Editing the `@Preview` source from a copy** — copies never write source; only Original's Properties does (unchanged AS behaviour).

## Current state (what this builds on)

`PreviewRenderPanel` renders the `LIVE` state into a **single** `ZoomableRenderView` inside a `JBScrollPane` (Phase 5). `RenderPipeline` debounces selection→render and calls `LiveRenderer.render(entry): RenderOutcome`. `RenderModelResolver` builds the `(RenderModelModule, Configuration, RenderLogger)` triple **config-aware** — it applies the preview's real `@Preview` args (device/api/size/`showSystemUi`). Nothing holds a rendered image in memory across selections. `RenderApiProbe` reflectively checks capabilities so missing internal API degrades to "unavailable," never a crash. The **Properties** action opens Android Studio's own `@Preview` picker (`PreviewPickerBridge`), whose edits re-render in place.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Add a **tab strip** to `PreviewRenderPanel`'s `LIVE` state. Tab 0 = **Original** (the preview at its own `@Preview` config — today's render, untouched). Extra tabs = ephemeral copies. With only Original present, the strip is **hidden** so the panel looks exactly as today. | Opt-in by construction: invisible until the user adds a view. Original is a stable baseline. |
| D2 | **＋ Add view** creates a tab that is an exact **copy of Original** — same `PreviewEntry`, empty view settings, so it renders identically. **No setting is required at add time.** | The user's model: "her yeni sekme orijinalin bir kopyası olsun… cihaz seçmek zorunlu olmasın." Adding a view is one click; configuring it is a separate, optional step. |
| D3 | **Context-aware Properties.** The existing Properties action targets whatever tab is active: on **Original** it opens Android Studio's `@Preview` picker backed by the annotation PSI, exactly as today (edits write source); on a **copy** it opens **the same picker UI** backed by an *in-memory* model, so edits change only that tab. | The user asked for the *same* dialog with the *same* configs on every copy, held in memory and comparable by switching tabs — not a reduced plugin-drawn popup. |
| D4 | **The picker's source-writing lives in its PSI model, not its UI — so we drive the same UI with an in-memory model.** Verified in the AS 253 jars: source writes come from `PsiCallParameterPropertyItem.setValue` → `writeNewValue` → `WriteCommandAction.runWriteCommandAction(...)` + `KtPsiFactory.createArgument`, i.e. from the *property items*, while `PsiPickerManager.show(Point, String, PsiPropertiesModel, Balloon.Position)` takes the **abstract** `PsiPropertiesModel` and its whole call chain (`createPickerPanel` → `PsiPropertyView` → `PropertiesPanel`) touches no PSI. So a copy's picker is the same dialog built from: our own `PsiPropertiesModel` subclass (public no-arg constructor; three members to implement — `properties`, `inspectorBuilder`, `tracker`), items that are a subclass of the **open** `MemoryParameterPropertyItem` overriding `setValue` to notify us (its own `setValue` is a bare field write that notifies nothing), AS's own `PreviewPropertiesInspectorBuilder(EnumSupportValuesProvider)` for an identical layout, and the dropdown values from the public factory `PreviewPickerValuesProvider.createPreviewValuesProvider(module, file)`. | Gives the user the real dialog with every config, while keeping a copy's edits ephemeral. Evidence-based; the earlier assumption that this needed reimplementing `PsiCallPropertiesModel` was wrong — that class is only one of three model layers, and the picker accepts the abstract base. |
| D5 | **A copy's override carries the full `@Preview` property set**, not a curated three-axis subset: device/deviceSpec, apiLevel, locale, fontScale, uiMode, showSystemUi, showBackground, backgroundColor, widthDp, heightDp, wallpaper (plus the hardware sub-rows the picker derives from the device spec). It is applied by deriving a preview element — `ComposePreviewElementInstance.createDerivedInstance(displaySettings, configuration)` — and letting AS's own `applyTo(configuration)` (already used by `RenderModelResolver.applyConfigAware`) do the work. This **replaces** the interim three-axis `ViewConfig`/`setDevice`+`setNightMode`+`setFontScale` path. | One override mechanism instead of two competing ones (AS's packed `uiMode` vs our `NightMode` would otherwise need a precedence rule), and it reuses AS's own mapping for every axis — including the ones we cannot set on a `Configuration` at all (`showBackground`/`backgroundColor`/`widthDp`/`heightDp` reach the render through the bridge XML that AS's `toPreviewXml()` generates). |
| D6 | **Tab titles.** A copy's tab is titled `View N` while its settings are empty, and otherwise summarises them (e.g. `Pixel 7 · Dark · 1.3×`). Original's tab is titled `Original`. | The user asked for a title on the tab; deriving it from the settings makes each tab self-describing during comparison. |
| D7 | **Ephemeral lifecycle.** Copies and their cached image/viewTree exist only while the current `PreviewEntry` is selected. Selecting a **different** preview discards every copy (frees memory) and resets to Original alone; closing a tab frees that view's image; a **max copies cap** bounds memory. Another state update for the *same* entry (RENDERING→LIVE, a picker-triggered re-render) leaves copies alone. | Matches "farklı previewe geçildiği anda bellek gider." Bounded + ephemeral, with no unbounded cache. |
| D8 | Render a copy by **reusing** the pipeline through a dedicated `renderVariant` entry point that takes a plugin-owned `ViewConfig` (*not* an AS type). It runs **off the EDT**, delivers on the EDT, and never touches the pipeline's debounced selection `generation`, so Original is unaffected. Only the **active** tab renders eagerly; an inactive tab renders on first activation, then caches. | Keeps one render in flight per tab, reuses the proven pipeline, and keeps AS types out of `ui/`. |
| D9 | The **only** new AS-internal capability: `RenderModelResolver` applies a `ViewConfig` to the `Configuration` before render. **Guarded + probed**: `RenderApiProbe` gains a view-override capability check; when unavailable, **＋ Add view** is hidden and the panel behaves exactly as today. | Isolation + degrade-don't-break, as in every prior AS-internal task. |
| D10 | Each copy is a full `ZoomableRenderView` (zoom/pan/hover/click-to-source per tab), and the render toolbar's zoom/fit/hand-tool/**export** actions target the **active** tab. Click-to-source from a copy navigates to the composable's real source — view settings change the render, not the source. | No new overlay code; and export/zoom acting on a hidden tab would be a silent-wrong-output bug. |
| D11 | **Failure posture** (degrade, don't break): a copy whose render fails shows a failed/retry state *inside that tab* and never breaks the other tabs or Original; an unavailable override degrades to the no-copies panel. | Consistent with the plugin's established resilience. |
| D12 | **Isolation.** The tab manager, the view-settings model/popup, the title derivation, and all UI are AS-free (`ui/`, `model/`). The `ViewConfig`→AS mapping (`Device`, `NightMode`, font scale) lives only in `render/`. No `com.android.tools.*` outside `render/`. | Keeps the Phase 1–5 boundary. |

## Interfaces (indicative)

```kotlin
// model/ — pure, unit-tested, no Swing/AS
/** One comparison copy's ephemeral overrides, keyed by the picker's own property names ("device", "apiLevel",
 *  "fontScale", "uiMode", "showSystemUi", "showBackground", "backgroundColor", "widthDp", "heightDp", "locale",
 *  "wallpaper"). Empty = an untouched copy of Original. Plugin-owned strings — the picker produces strings and
 *  `render/` maps them onto AS types, so no AS type ever reaches `model/` or `ui/`. */
data class ViewOverride(val values: Map<String, String> = emptyMap()) {
    val isDefault: Boolean get() = values.isEmpty()
    fun with(name: String, value: String): ViewOverride
}

// ui/ — ephemeral comparison-view state + title (no Swing/AS)
data class ComparisonView(val id: Int, val override: ViewOverride)  // Original = ORIGINAL_ID, empty override
class ComparisonViewList {
    val views: List<ComparisonView>
    fun add(override: ViewOverride): ComparisonView?    // null at the cap
    fun close(id: Int)                                  // no-op for Original
    fun setOverride(id: Int, override: ViewOverride)    // ignores Original
    fun clearExtras()                                   // on preview switch → Original only
}
object ViewTitle {
    fun of(view: ComparisonView, ordinal: Int): String   // "Original" / "View 2" / "Pixel 7 · uiMode 32 · 1.3×"
}

// render/ — AS-internal: the ephemeral picker and the override application both live here
class EphemeralPickerBridge(project: Project) {          // sibling of the existing PreviewPickerBridge
    fun isAvailable(): Boolean
    /** Shows AS's own picker UI over an in-memory model seeded from [entry]'s current @Preview values plus
     *  [override]; every edit calls [onEdit] with the property name and its new value. Writes no source. */
    fun showEphemeralPicker(entry: PreviewEntry, override: ViewOverride, at: RelativePoint,
                            onEdit: (String, String) -> Unit): Boolean
}
// RenderPipeline.renderVariant(entry: PreviewEntry, override: ViewOverride, onResult: (RenderOutcome) -> Unit)
// LiveRenderer.render(entry: PreviewEntry, override: ViewOverride? = null): RenderOutcome
// RenderModelResolver: ViewOverride → PreviewConfiguration/PreviewDisplaySettings →
//     ComposePreviewElementInstance.createDerivedInstance(displaySettings, configuration) → existing applyTo path.
// RenderApiProbe.isViewOverrideAvailable(): Boolean   // now also covers createDerivedInstance + the picker model
```

## Unknowns (discovery gates — settled in `runIde`, like every prior AS-internal task)

| # | Unknown | Where it bites | Degrade |
|---|---------|----------------|---------|
| V1 | Whether a derived element (`createDerivedInstance` + AS's `applyTo`) actually re-renders with each overridden property on our layoutlib path — the seams are verified, the render effect is not. | D5 — the feature's engine. | Probe fails → hide ＋ Add view; panel is exactly today's. A property that does not take effect degrades to Original's look, never a crash. |
| V2 | Whether AS's picker UI renders correctly over our in-memory model — the layout comes from `PreviewPropertiesInspectorBuilder`, whose grouping keys are hardcoded strings, and whether the item names we seed match what it expects (a mismatch is documented as cosmetic: the row falls into the default section). | D4 — the dialog itself. | A missing/renamed member throws `LinkageError`/`NoSuchMethodError` at the guarded call site → the copy-side Properties degrades (no dialog) while Original's picker is untouched. |
| V3 | Memory/latency of holding N rendered images + re-rendering per tab. | D7 cap + lazy render. | Cap the copies; render lazily; free on switch/close. |
| V4 | `PreviewConfiguration.Companion.cleanAndGet(...)` treats `null` as "reset to the layoutlib sentinel", **not** "keep the current value", so every unedited axis must be passed through explicitly from the base configuration. | D5 — a wrong merge silently resets properties the user never touched. | Merge helper is pure and unit-tested against the base-preserving contract before it reaches the render. |

## Architecture

```
PreviewRenderPanel (LIVE)
   ┌─────────────────────────────────────────────────┐
   │ tabs:  [Original] [View 2] [Pixel 7 · Dark ×] …  │   hidden when only Original
   ├─────────────────────────────────────────────────┤
   │ active view → JBScrollPane → ZoomableRenderView   │   zoom/pan/overlay per tab
   └─────────────────────────────────────────────────┘
   toolbar: ＋ Add view · Properties* · − + Fit 100% · Hand · SavePNG · Copy
            (* Original → AS @Preview picker;  copy → ephemeral view-settings popup)
            (zoom/fit/hand/export act on the ACTIVE tab — D10)

   ＋ Add view          → ComparisonViewList.add(ViewConfig())        (ui/, pure) → renders a copy of Original
   settings changed     → ComparisonViewList.setConfig(id, config)    (ui/, pure)
        │
        ▼
   RenderPipeline.renderVariant(entry, config, onResult)              (render/, off-EDT → EDT)
        └── LiveRenderer.render(entry, config)
              └── RenderModelResolver: device → Configuration.setDevice
                                        theme  → Configuration.setNightMode
                                        scale  → Configuration.setFontScale          (AS-internal)
        ▲
   RenderOutcome.Success(image, viewTree) → the tab's ZoomableRenderView; tab title ← ViewTitle.of(view, ordinal)

   selection change → ComparisonViewList.clearExtras() → drop images, back to Original
```

## Components

| Unit | Responsibility | AS-internal? |
|------|----------------|--------------|
| `ViewOverride` (model/) | A copy's ephemeral property overrides as plugin-owned name→value strings | No |
| `ComparisonView` / `ComparisonViewList` (ui/) | Ephemeral tab state: add/close/setOverride/clearExtras, Original-at-0 + max cap | No |
| `ViewTitle` (ui/) | Pure tab-title derivation from a view's override and ordinal | No |
| `EphemeralPickerBridge` (render/) | AS's own picker UI over an in-memory `PsiPropertiesModel`; reports each edit, writes no source | **Yes** |
| `PreviewRenderPanel` (ui/) | Tab strip, one `ZoomableRenderView` per view, ＋Add view, context-aware Properties, active-tab toolbar targeting, clear-on-selection-change | No |
| `ZoomableRenderView` (ui/) | Reused per tab, unchanged | No |
| `RenderPipeline.renderVariant` (render/) | Off-EDT per-tab render with a `ViewOverride`, EDT delivery, independent of the selection generation | Yes (impl) |
| `RenderModelResolver` (render/) | Map `ViewOverride` → `PreviewConfiguration`/`PreviewDisplaySettings`, derive the element via `createDerivedInstance`, apply through AS's own `applyTo`, guarded | **Yes** |
| `RenderApiProbe` (render/) | Capability probe (derived-instance + picker model) → gates ＋Add view and the copy-side Properties | **Yes** |

## Testing

- **Pure unit (JUnit, no fixture):** `ComparisonViewList` (add up to the cap then null; `close` frees a copy but never Original; `setConfig`; `clearExtras` returns to Original-only; Original stays at index 0); `ViewConfig.isDefault`; `ViewTitle.of` (default copy → `View N`; each axis and combinations → the summary; Original → `Original`); `ViewSettingsCatalog` shape (non-empty, unique device ids, sane scales). This is where the branching logic lives, so it is unit-tested.
- **Manual `runIde` gate:** ＋Add view yields an identical copy with no settings prompt; Properties on a copy opens the ephemeral popup and changing device/theme/font scale re-renders only that tab while Original and the `@Preview` source stay unchanged; Properties on Original still opens AS's picker; tab titles track settings; per-tab zoom/pan/click-to-source; toolbar actions (incl. Save PNG/Copy) act on the active tab; selection change frees copies; the degrade path — and V1/V2/V3.

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R1 | An axis does not visibly change the render on our layoutlib path (V1). | Medium | Medium | Setters confirmed present; the gate verifies each axis; an ineffective axis degrades to Original's look, never a crash. |
| R2 | N held images + per-tab re-render is memory/latency-heavy (V3). | Medium | Medium | Lazy per-tab render, cache once, hard cap on copies, free on switch/close. |
| R3 | Curated device ids don't resolve on this build (V2). | Medium | Low | `getDeviceById` returns null → the config-aware device is kept; the gate records which ids resolve. |
| R4 | Context-aware Properties confuses (same button, two editors). | Low | Low | The active tab is visible and titled; Original's tab is explicitly `Original`; the popup is clearly the copy's own settings. |
| R5 | A copy's settings accidentally reach the shared `@Preview` source. | Low | High | Copies never touch the picker (D4); their settings live only in `ViewConfig` and are applied to the render `Configuration`. The gate explicitly checks the source is unmodified. |

## Acceptance Criteria

- **AC1** With a preview selected, **＋ Add view** adds a tab that is an exact copy of Original — it renders identically, and **no setting has to be chosen** to add it.
- **AC2** Pressing **Properties** while a copy is active opens **the same picker dialog Original shows**, with the same properties, seeded from that copy's current values; changing any of them re-renders **only that tab** and leaves Original, the other tabs, and the `@Preview` **source** unchanged. Each copy keeps its own values in memory, so switching tabs switches between the configured renders.
- **AC3** Pressing **Properties** while **Original** is active opens Android Studio's `@Preview` picker exactly as today, and its edits still write source exactly as today.
- **AC4** Each tab shows a title: `Original` for tab 0, `View N` for an untouched copy, and a summary of the overridden properties once edited.
- **AC5** Several copies can coexist, each with its own settings; each supports zoom/pan and click-to-source independently, and the toolbar's zoom/fit/hand-tool/Save-PNG/Copy act on the **active** tab.
- **AC6** Selecting a **different** preview discards every copy and its cached image (memory freed), leaving only Original; closing a tab frees that view's image; with no copies the tab strip is hidden and the panel is exactly today's.
- **AC7** When the view-override capability is unavailable on this build, **＋ Add view** is hidden and the panel behaves exactly as today; a copy whose render fails shows a failed/retry state within its own tab.
- **AC8** Phase 1–5 behaviour does not regress; `./gradlew test` is green (existing plus new pure tests for the view-list lifecycle, `ViewConfig`, `ViewTitle`, and the catalog).
