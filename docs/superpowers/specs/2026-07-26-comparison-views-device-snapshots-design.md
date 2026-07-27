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
| D3 | **Context-aware Properties.** The existing Properties action targets whatever tab is active: on **Original** it opens Android Studio's `@Preview` picker exactly as today (source-editing); on a **copy** it opens the plugin's own ephemeral **view settings** popup, which never touches source. | The user asked to change a copy's look "properties butonuna basıp." AS's picker cannot serve a copy — see D4 — so the same button dispatches to the right editor for what you are looking at. |
| D4 | **AS's picker is source-editing, so copies get a plugin-owned settings popup.** Verified from the AS 253 jars: `PsiCallParameterPropertyItem.setValue` → `writeNewValue` → `WriteCommandAction.runWriteCommandAction(...)` + `KtPsiFactory.createArgument`, i.e. the picker rewrites the `@Preview` annotation in source. Routing a copy's edits through it would mutate the shared annotation and change **every** tab including Original, defeating comparison. (`MemoryParameterPropertyItem` exists but is a single in-memory property item, not a usable model; driving AS's picker UI ephemerally would mean reimplementing `PsiCallPropertiesModel` + its enum/inspector providers — deep, fragile AS-internal coupling.) | Evidence-based: the whole feature depends on a copy's settings being ephemeral. |
| D5 | **View settings axes (v1): device, theme, font scale** — each optional, `null` meaning "inherit the `@Preview`'s own value." Confirmed settable on AS's `com.android.tools.configurations.Configuration`: `setDevice(Device, boolean)`, `setNightMode(NightMode)`, `setFontScale(float)`. Devices come from a small **curated** list; theme is Light/Dark; font scale is a curated ladder. | The three highest-value axes for design QA, all reachable through one already-owned `Configuration` seam. Curated lists keep the popup small and the render count bounded. |
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
data class DeviceOption(val id: String, val label: String)     // id maps to an AS Device in render/
enum class ThemeOption(val label: String) { LIGHT("Light"), DARK("Dark") }

/** One comparison copy's ephemeral view settings. Every field null = "inherit the @Preview's own value",
 *  which is exactly what a freshly added copy of Original looks like. */
data class ViewConfig(
    val device: DeviceOption? = null,
    val theme: ThemeOption? = null,
    val fontScale: Float? = null,
) {
    val isDefault: Boolean get() = device == null && theme == null && fontScale == null
}

object ViewSettingsCatalog {
    val DEVICES: List<DeviceOption>      // curated: Pixel 4a / Pixel 7 / Pixel Tablet / Pixel Fold
    val FONT_SCALES: List<Float>         // curated ladder: 0.85, 1.0, 1.15, 1.3, 1.5, 2.0
}

// ui/ — ephemeral comparison-view state + title (no Swing/AS)
data class ComparisonView(val id: Int, val config: ViewConfig)   // Original = ORIGINAL_ID with a default config
class ComparisonViewList {
    val views: List<ComparisonView>
    fun add(config: ViewConfig): ComparisonView?   // null at the cap
    fun close(id: Int)                             // no-op for Original
    fun setConfig(id: Int, config: ViewConfig)     // ignores Original
    fun clearExtras()                              // on preview switch → Original only
}
object ViewTitle {
    fun of(view: ComparisonView, ordinal: Int): String   // "Original" / "View 2" / "Pixel 7 · Dark · 1.3×"
}

// render/ — the override on the existing pipeline (AS-internal mapping stays here)
// RenderPipeline.renderVariant(entry: PreviewEntry, config: ViewConfig, onResult: (RenderOutcome) -> Unit)
// LiveRenderer.render(entry: PreviewEntry, config: ViewConfig? = null): RenderOutcome
// RenderModelResolver: ViewConfig → Configuration.setDevice / setNightMode / setFontScale; null → unchanged.
// RenderApiProbe.isViewOverrideAvailable(): Boolean
```

## Unknowns (discovery gates — settled in `runIde`, like every prior AS-internal task)

| # | Unknown | Where it bites | Degrade |
|---|---------|----------------|---------|
| V1 | Whether applying device / night mode / font scale to the `Configuration` actually re-renders that way on our layoutlib path (the setters exist; the render effect is unverified). | D5/D9 — the feature's engine. | Probe fails → hide ＋ Add view; panel is exactly today's. An axis that does not take effect degrades to Original's look. |
| V2 | Which curated device ids resolve on this build (`pixel_4a`, `pixel_7`, `pixel_tablet`, `pixel_fold`). | D5 curated list. | Unresolved id → that device renders unchanged; the gate records which resolve. |
| V3 | Memory/latency of holding N rendered images + re-rendering per tab. | D7 cap + lazy render. | Cap the copies; render lazily; free on switch/close. |

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
| `DeviceOption` / `ThemeOption` / `ViewConfig` / `ViewSettingsCatalog` (model/) | Plugin-owned view-settings model + curated option lists | No |
| `ComparisonView` / `ComparisonViewList` (ui/) | Ephemeral tab state: add/close/setConfig/clearExtras, Original-at-0 + max cap | No |
| `ViewTitle` (ui/) | Pure tab-title derivation from a view's config and ordinal | No |
| `ViewSettingsPopup` (ui/) | The ephemeral settings editor for a copy (device / theme / font scale); never writes source | No |
| `PreviewRenderPanel` (ui/) | Tab strip, one `ZoomableRenderView` per view, ＋Add view, context-aware Properties, active-tab toolbar targeting, clear-on-selection-change | No |
| `ZoomableRenderView` (ui/) | Reused per tab, unchanged | No |
| `RenderPipeline.renderVariant` (render/) | Off-EDT per-tab render with a `ViewConfig`, EDT delivery, independent of the selection generation | Yes (impl) |
| `RenderModelResolver` (render/) | Apply `ViewConfig` to the `Configuration` (device / night mode / font scale), guarded | **Yes** |
| `RenderApiProbe` (render/) | View-override capability probe → gates ＋Add view | **Yes** |

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
- **AC2** Pressing **Properties** while a copy is active opens that copy's ephemeral view settings; changing device, theme, or font scale re-renders **only that tab**, leaving Original, the other tabs, and the `@Preview` **source** unchanged.
- **AC3** Pressing **Properties** while **Original** is active opens Android Studio's `@Preview` picker exactly as today.
- **AC4** Each tab shows a title: `Original` for tab 0, `View N` for an unconfigured copy, and a settings summary (e.g. `Pixel 7 · Dark · 1.3×`) once configured.
- **AC5** Several copies can coexist, each with its own settings; each supports zoom/pan and click-to-source independently, and the toolbar's zoom/fit/hand-tool/Save-PNG/Copy act on the **active** tab.
- **AC6** Selecting a **different** preview discards every copy and its cached image (memory freed), leaving only Original; closing a tab frees that view's image; with no copies the tab strip is hidden and the panel is exactly today's.
- **AC7** When the view-override capability is unavailable on this build, **＋ Add view** is hidden and the panel behaves exactly as today; a copy whose render fails shows a failed/retry state within its own tab.
- **AC8** Phase 1–5 behaviour does not regress; `./gradlew test` is green (existing plus new pure tests for the view-list lifecycle, `ViewConfig`, `ViewTitle`, and the catalog).
