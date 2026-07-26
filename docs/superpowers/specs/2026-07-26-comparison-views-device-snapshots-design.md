# Comparison Views — Ephemeral Per-Preview Device Snapshots

| | |
|---|---|
| **Scope** | Phase 6 — compare the current preview across devices *without editing its `@Preview`*, by adding ephemeral in-memory "views" (tabs), each re-rendering the same composable at a chosen device. |
| **Builds on** | [Phase 5](2026-07-26-render-view-zoom-pan-export-design.md) — the zoomable/pannable `ZoomableRenderView` and the config-aware render pipeline. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG6-N]` |

## Goal

Let a user see the *same* preview on more than one device at once, side-stepping the "edit `@Preview`, lose the old view" loop. The current render stays as the **Original** tab; a **＋ Add view** action spins up an ephemeral **comparison view** — an in-memory copy of the selected preview re-rendered at a device the user picks — shown as another tab. Any number of views can be added, each at its own device, each fully zoom/pan/click-to-source. The moment a different preview is selected, every extra view (and its cached image) is discarded and memory is freed.

## Non-Goals

- **Side-by-side grid / contact sheet** — the user chose *tabs*, one view visible at a time (a grid is a separate, later spec).
- **Dimensions other than device** — no dark/light, font-scale, or locale axis in v1 (device only; the others are natural follow-ups).
- **Full AS device catalog or manual width×height entry** — a small *curated* device list only (the picker already covers arbitrary devices by editing source).
- **Persistence** — comparison views are deliberately ephemeral: not saved across a preview switch, tool-window close, or IDE restart.
- **Batch / sheet export** — export stays the existing single-image Save-PNG / Copy on whichever view is active.
- **Frozen-image snapshots** — a view is a *live* re-render at a device, not a static image copy.
- **Editing the `@Preview` source** — that is the existing Properties picker; comparison views never write source.

## Current state (what this builds on)

`PreviewRenderPanel` renders the `LIVE` state into a **single** `ZoomableRenderView` inside a `JBScrollPane` (Phase 5). `RenderPipeline` debounces selection→render (400 ms, monotonic `generation` guard) and calls `LiveRenderer.render(entry): RenderOutcome`. `RenderModelResolver` builds the `(RenderModelModule, Configuration, RenderLogger)` triple **config-aware** — it asks AS's `AnnotationFilePreviewElementFinder` for the preview's real `@Preview` args and applies device/api/size/`showSystemUi`, falling back to a default element. **The device comes solely from the `@Preview`; there is no override path.** Nothing holds a rendered image in memory across selections — every selection re-renders from scratch. `RenderApiProbe` reflectively checks render / picker / config-aware / view-tree capabilities so missing internal API degrades to "unavailable," never a crash.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Add a **tab strip** to `PreviewRenderPanel`'s `LIVE` state. Tab 0 = **Original** (the preview at its own `@Preview` config — today's render, untouched). Extra tabs = comparison views. With only Original present, the strip is **hidden** so the panel looks exactly as today. | Opt-in by construction: the feature is invisible until the user adds a view. Original is a stable baseline that never changes device. |
| D2 | A toolbar **＋ Add view** action creates a comparison view: another render of the **same `PreviewEntry`** with an *ephemeral device override*. Each extra tab has its own **device selector** and its own `ZoomableRenderView`; a **×** closes it. | The user asked for tabs holding independent in-memory copies at different devices. Reusing `ZoomableRenderView` per tab gives zoom/pan/overlay for free. |
| D3 | Device choice = a small **curated** `DeviceOption` list (id + label), *not* the full AS catalog and *not* manual sizes. v1 set (indicative): Pixel 4a, Pixel 7, Pixel Tablet, a foldable. Changing a view's device re-renders that view. | The user explicitly ruled out a catalog ("cihaz kataloğu vs bunlara gerek yok"). A handful of representative form factors covers the compare use case. |
| D4 | **Ephemeral lifecycle.** Comparison views and their cached image/viewTree exist only while the current `PreviewEntry` is selected. Selecting a **different** preview discards every extra view (frees memory) and resets to Original alone. Closing a tab frees that view's image. A **max view cap** (e.g. 5 extra) bounds memory. | Matches the user's "farklı previewe geçildiği anda bellek gider." Bounded + ephemeral = the first deliberate in-memory hold, with no unbounded cache. |
| D5 | Render a view by **reusing** `RenderPipeline`/`LiveRenderer` with a new optional **device override** (a plugin-owned `DeviceOption`/id, *not* an AS type in the signature). Only the **active** tab renders eagerly; inactive tabs render **lazily** on first activation or on device change, then cache. One render at a time (reuse the pipeline's generation/cancellation). | Keeps a single render in flight (cheap, no batch), reuses the proven pipeline, and keeps AS types out of `ui/` and the pipeline signature. |
| D6 | The **only** new AS-internal capability: `RenderModelResolver` applies the override device to the `Configuration` before render, without touching source. It is **guarded + probed**: `RenderApiProbe` gains a device-override capability check; when unavailable, **＋ Add view** is hidden/disabled and the panel behaves exactly as today. | Isolation + degrade-don't-break, as in every prior AS-internal task. Original always works even if override does not. |
| D7 | Each view is a full `ZoomableRenderView` (zoom/pan/hover/click-to-source per view). Click-to-source from an override view navigates to the composable's **real** source — the device override changes the render, not the source. | No new overlay code; the device axis is orthogonal to source location. |
| D8 | **Failure posture** (degrade, don't break): a view whose render fails shows a `FAILED`/retry state *inside that tab* and never breaks the other tabs or Original; an unavailable override degrades to the no-view panel. | Consistent with the plugin's established resilience. |
| D9 | **Isolation.** The tab manager, `DeviceOption`/`DeviceCatalog`, the view-list lifecycle, and all UI are AS-free (`ui/`, `model/`). The device→AS-`Device` mapping and the `Configuration` override live only in `render/`. No `com.android.tools.*` outside `render/`. | Keeps the Phase 1–5 boundary. |

## Interfaces (indicative)

```kotlin
// model/ — pure, unit-tested, no Swing/AS
data class DeviceOption(val id: String, val label: String)      // curated; id maps to an AS device in render/
object DeviceCatalog { val DEFAULT: List<DeviceOption> }         // the curated v1 set

// ui/ — ephemeral comparison-view state (no Swing/AS)
data class ComparisonView(val id: Int, val device: DeviceOption?)  // device == null → Original (@Preview config)
class ComparisonViewList {                                         // pure; enforces Original-at-0 + max cap
    val views: List<ComparisonView>
    fun add(device: DeviceOption?): ComparisonView?               // null if at cap
    fun close(id: Int)                                            // no-op for Original
    fun setDevice(id: Int, device: DeviceOption)
    fun clearExtras()                                            // on preview switch → back to Original only
}

// render/ — device override on the existing pipeline (AS-internal mapping stays here)
// RenderPipeline.render(entry, deviceOverride: DeviceOption?)   // null → today's config-aware path
// LiveRenderer.render(entry, deviceOverride: DeviceOption?)
// RenderModelResolver: map DeviceOption.id → AS Device, set it on the Configuration; null → unchanged.
```

## Unknowns (discovery gates — settled in `runIde`, like every prior AS-internal task)

| # | Unknown | Where it bites | Degrade |
|---|---------|----------------|---------|
| V1 | Can the render device be overridden on our layoutlib path (via AS `Configuration.setDevice` / the `RenderModelModule`+`Configuration` builder) *without editing source*, and does layoutlib re-render at the new device? Where to obtain the `Device` by id (`ConfigurationManager` / `DeviceManager`). | D6 override — the whole feature's engine. | Probe fails → hide **＋ Add view**; panel is exactly today's. |
| V2 | The exact device **ids** AS resolves for the curated set (do `pixel_4a` / `pixel_7` / `pixel_tablet` / a fold id resolve on this build?). | D3 curated list. | Unresolved id → that `DeviceOption` is dropped from the list (verified at the gate). |
| V3 | Memory/latency of holding N rendered images + re-rendering per tab. | D4 cap + lazy render. | Cap the extra-view count; render lazily; free on switch/close. |

## Architecture

```
PreviewRenderPanel (LIVE)
   ┌───────────────────────────────────────────────┐
   │ tab strip:  [Original] [Pixel 7 ×] [Tablet ×] … │   hidden when only Original
   ├───────────────────────────────────────────────┤
   │ active view → JBScrollPane → ZoomableRenderView │   zoom/pan/overlay per view
   └───────────────────────────────────────────────┘
   toolbar: ＋ Add view · (per-view) device ▾ · − + Fit 100% · Hand · SavePNG · Copy

   ＋ Add view / device change
        │  ComparisonViewList.add/setDevice        (ui/, pure)
        ▼
   RenderPipeline.render(entry, deviceOverride)     (render/)
        └── LiveRenderer.render(entry, deviceOverride)
              └── RenderModelResolver: id → AS Device → Configuration.setDevice   (AS-internal)
        ▲
   RenderOutcome.Success(image, viewTree) → cached in the active ComparisonView’s ZoomableRenderView

   selection change → ComparisonViewList.clearExtras() → drop images, back to Original
```

## Components

| Unit | Responsibility | AS-internal? |
|------|----------------|--------------|
| `DeviceOption` / `DeviceCatalog` (model/) | Curated device list (id + label), pure data | No |
| `ComparisonView` / `ComparisonViewList` (ui/) | Ephemeral view state: add/close/setDevice/clearExtras, Original-at-0 + max-cap invariants | No |
| `PreviewRenderPanel` (ui/) | Host the tab strip + one `ZoomableRenderView` per view; wire ＋Add-view / device selector / close; clear extras on selection change | No |
| `ZoomableRenderView` (ui/) | Reused per tab, unchanged (zoom/pan/overlay/click-to-source) | No |
| `RenderPipeline` / `LiveRenderer` (render/) | Gain an optional `deviceOverride: DeviceOption?` (plugin-owned type in the signature) | Yes (impl) |
| `RenderModelResolver` (render/) | Map `DeviceOption.id` → AS `Device`, set it on the `Configuration`; `null` → today's path | **Yes** |
| `RenderApiProbe` (render/) | Add a device-override capability probe → gate ＋Add-view | **Yes** |

## Testing

- **Pure unit (JUnit, no fixture):** `ComparisonViewList` (add up to the cap then null; `close` frees an extra but never Original; `setDevice`; `clearExtras` returns to Original-only; Original stays at index 0); `DeviceCatalog.DEFAULT` shape (non-empty, unique ids). This is where the branching/lifecycle logic lives, so it is unit-tested.
- **Manual `runIde` gate:** add a view and see the same preview re-render at the chosen device; multiple views at different devices; per-view zoom/pan/click-to-source; selection change frees the extras; tab close frees an image; the degrade path (override unavailable → ＋Add-view hidden) — and V1/V2 (override works on the render path; the curated ids resolve). Same manual-verification posture as Phase 2–5 render work.

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R1 | Device override not supported on our render path (V1) → the whole feature can't render variants. | Medium | High | Probe first; hide ＋Add-view when unavailable; Original (today's render) is untouched. Gate settles it early. |
| R2 | N held images + per-tab re-render is memory/latency-heavy (V3). | Medium | Medium | Lazy per-tab render, cache once, hard cap on extra views, free on switch/close. |
| R3 | Curated device ids don't resolve on this build (V2). | Medium | Low | Gate verifies each id; unresolved ids are dropped from the list, not shown. |
| R4 | Click-to-source from an override view lands wrong (device changed the view tree). | Low | Low | Source location is independent of device; `PreviewViewHitTester` reused unchanged; gate re-checks. |
| R5 | Interaction with the Properties picker (picker edits Original's source while overrides are ephemeral). | Low | Low | v1: the Properties action stays bound to **Original** only; override views have the device selector, not the picker. |

## Acceptance Criteria

- **AC1** With a preview selected, **＋ Add view** adds a tab that is a copy of the current preview; choosing a device on it re-renders the *same composable* at that device **without editing the `@Preview` source**.
- **AC2** Several views can be added, each at a different device; the tab strip switches between them; each view supports zoom/pan and click-to-source independently.
- **AC3** Tab 0 (**Original**) always shows the preview at its own `@Preview` config, unchanged from today; with no extra views the tab strip is hidden.
- **AC4** Selecting a **different** preview discards every extra view and its cached image (memory freed), leaving only Original for the new preview; closing a tab frees that view's image.
- **AC5** When the device-override capability is unavailable on this build, **＋ Add view** is hidden/disabled and the panel behaves exactly as today (degrade, no crash).
- **AC6** A view whose render fails shows a failed/retry state within that tab without breaking the other tabs, Original, or the panel.
- **AC7** Phase 1–5 behavior does not regress; `./gradlew test` is green (existing plus new pure tests for the view-list lifecycle and the device catalog).
