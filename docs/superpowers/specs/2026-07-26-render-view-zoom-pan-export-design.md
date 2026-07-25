# Render View Polish — Zoom, Pan, Export & Click-Hardening Design

| | |
|---|---|
| **Scope** | Phase 5 — make the single render view zoomable/pannable, exportable, and harden click-to-source against same-named files. |
| **Builds on** | [Phase 4](2026-07-25-config-aware-render-and-interactive-layers-design.md) — config-aware render + the hover-outline/click-to-source interactive layer. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG5-N]` |

## Goal

Turn the render pane from a static fit-to-panel image into an inspectable view: zoom in to see detail, pan around, and export the render — while the Phase 4 hover-outline and click-to-source keep working. Plus a targeted fix so click-to-source lands on the right file when two source files share a name.

## Non-Goals

- A thumbnail **grid** of many previews (that is a separate, later spec).
- Multi-config / device-matrix / `@PreviewParameter` rendering (separate spec).
- Zoom/export on anything but the current single render (no batch export).
- Editing or interacting *inside* the composable (no interactive Compose mode).

## Current state (what this replaces)

`PreviewRenderPanel` shows a `LIVE` render as a `RenderImageLabel` (a `JBLabel` subclass) whose icon is the render `BufferedImage` scaled to fit and **capped at 1.0** (no upscaling), centered. That label also paints the Phase 4 hover outline and hosts the hover/click `MouseListener`s, mapping panel↔render pixels through `currentDrawRect()` (derived from the actual scaled icon). `PreviewViewHitTester` (`toRenderPoint`, `innermostAt`, `sourceChainAt`) is the pure geometry. Click navigation runs `PreviewGalleryPanel.navigateToSource` → `resolveSourceFile(fileName)`, which prefers the selected entry's own file and otherwise takes `FilenameIndex.getVirtualFilesByName(name, projectScope).firstOrNull()`. `PreviewSourceLocation(fileName, lineNumber, offset)` carries only those three (offset is always null; AS's `SourceLocation` exposes `fileName`/`lineNumber`/`packageHash`, no char offset).

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Replace `RenderImageLabel` with a new `ZoomableRenderView` (a `JComponent`) hosted in a `JBScrollPane`, for the `LIVE` state only. | The chosen full-zoom model (cursor-anchored zoom, arbitrary pan) needs a component whose `preferredSize` is the zoomed extent so the scroll pane can scroll it — a `JLabel`-with-icon cannot express that. Owning the component keeps full control of the overlay + no coupling to the platform's Images-plugin viewer. |
| D2 | **Discrete zoom ladder**: `25, 50, 75, 100, 150, 200, 300, 400` (%). The view holds a `Double` zoom factor. `+`/`−` and `Ctrl`+scroll move through the ladder (from an off-ladder factor they snap to the next ladder step in that direction); `Ctrl`+scroll is **anchored at the cursor**. `Fit` sets the factor to fit the whole image in the viewport **capped at 1.0** (a smaller-than-viewport preview shows at 100 %, never auto-upscaled); `100%` sets 1:1. New render defaults to **Fit**. | The user asked for stepped (not continuous) zoom. A `Double` factor lets `Fit` be an exact (possibly off-ladder) level while stepping stays on the ladder; the 1.0 cap on `Fit` preserves today's no-upscale default; cursor-anchored stepping matches AS's own preview. |
| D3 | **Pan** via all of: scroll pane scrollbars, plain scroll-wheel (vertical; `Shift`+wheel horizontal), and a **hand-tool** drag. | The user wants both scrollbars and a hand tool. `Ctrl`+wheel is zoom, plain wheel is pan — the standard split. |
| D4 | A toolbar **hand-tool toggle**. ON → cursor is a hand, drag pans, and the hover-outline + click-to-source are **inert** (pure pan mode). OFF (default) → Phase 4 behavior: hover outlines, click navigates; no drag-pan. | Click is already taken by navigate (Phase 4); a modal hand tool is the clean, AS-familiar way to add drag-pan without a gesture conflict. |
| D5 | Overlay coordinates: **render-pixel = componentPoint / zoomFactor**. No letterbox/centering math in the mapping — the `ZoomableRenderView`'s own bounds *are* the zoomed image. | The scroll pane handles positioning; inside the component the image origin is `(0,0)` scaled by `zoomFactor`, so the mapping collapses to a single divide. Simpler and exact vs Phase 4's fit-rect. |
| D6 | **Export** = two toolbar actions on the **raw** render `BufferedImage` (native resolution, no overlay, zoom-independent): *Save PNG…* (file chooser, default name from the preview's display name) via `ImageIO`, and *Copy* to the system clipboard via an AWT image `Transferable`. | Raw native render is the shareable artifact; the zoomed viewport is not. Two low-effort, high-utility actions. |
| D7 | **Click-hardening**: add `packageHash: Int?` to `PreviewSourceLocation`, captured in `LiveRenderer.toPreviewViewNode` from AS's `SourceLocation`. `resolveSourceFile` disambiguates same-named project files by matching each candidate file's package hash; falls back to `firstOrNull` when packageHash is absent or nothing matches. | Fixes the reviewer-flagged same-named-file misnavigation. The fallback guarantees no regression when the hash can't be resolved. |
| D8 | Failure posture (same as every prior phase — degrade, don't break): export IO/clipboard errors log + show a balloon notification, never crash; a missing/unmatched packageHash falls back to `firstOrNull`; zoom/pan are pure Swing with no AS API, nothing to guard. | Consistent with the plugin's established resilience. |
| D9 | AS-internal boundary unchanged: the only new AS-internal touch is reading `SourceLocation.packageHash` inside `LiveRenderer` (`render/`), guarded and probed. `ZoomableRenderView`, the zoom math, the export actions, and `resolveSourceFile` use only plugin-owned types + platform Swing/VFS — no `com.android.tools.*`. | Keeps the Phase 1–4 isolation rule (`com.android.tools.*` only in `render/`). |

## Interfaces (indicative)

```kotlin
// ui/ — pure, unit-tested, no Swing/AS
object ZoomMath {
    val LADDER: List<Int>                             // discrete percent steps: 25, 50, 75, 100, 150, 200, 300, 400
    fun stepIn(currentFactor: Double): Double         // smallest ladder factor strictly above current (clamped to 4.0)
    fun stepOut(currentFactor: Double): Double        // largest ladder factor strictly below current (clamped to 0.25)
    fun fitFactor(viewport: Dimension, image: Dimension): Double  // fit the whole image, capped at 1.0 (no auto-upscale)
    /** New scroll offset so the render point under the cursor stays under it after a zoom change. */
    fun anchorScroll(cursorInView: Point, oldFactor: Double, newFactor: Double, oldScroll: Point): Point
}

// ui/ — the zoomable, pannable render component (Swing; no AS API)
class ZoomableRenderView : JComponent {
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>)
    fun clearContent()
    var handToolActive: Boolean
    var zoomFactor: Double               // Fit/100% set it directly; +/-/Ctrl-scroll snap through LADDER
    fun fitToViewport()
    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit
    fun rawImage(): BufferedImage?       // for export
    // preferredSize = image * zoom; paints image + (hand-off) hover outline; mouse maps via /zoom
}

// model/ — plugin-owned, gains the disambiguator
data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?, val packageHash: Int?)
```

## Unknowns (discovery gates — settled in `runIde`, like every prior AS-internal task)

| # | Unknown | Where it bites |
|---|---------|----------------|
| V1 | What `com.android.tools...SourceLocation.packageHash` actually is, and how to derive the same hash from a candidate `VirtualFile`'s package (is it `packageFqName.hashCode()`? of what string?). | D7 disambiguation. Fallback = `firstOrNull`, so a wrong guess degrades to today's behavior, never worse. |
| V2 | Whether an AWT image `Transferable` copied on this JBR/macOS actually pastes into external apps (Slack, docs). | D6 clipboard export. |
| V3 | Cursor-anchored zoom scroll math against a real `JBScrollPane` viewport (does `anchorScroll` keep the point stable across ladder steps?). | D2 zoom feel; verified live, math is unit-tested first. |

## Architecture

```
PreviewRenderPanel (LIVE)
        │  show(Success(image, viewTree))
        ▼
   JBScrollPane
        └── ZoomableRenderView(image, viewTree)      preferredSize = image × zoom
              • paint: image@zoom + (hand-tool OFF) hovered outline
              • mouse: renderPoint = p / zoom → PreviewViewHitTester.innermostAt / sourceChainAt
              • Ctrl+wheel → ZoomMath.stepIn/Out @ cursor → anchorScroll
              • hand-tool ON → drag adjusts scroll; overlay inert
        ▲
   actionsBar toolbar: − + Fit 100% [zoom%] · HandTool · SavePNG · Copy
        │ SavePNG/Copy → rawImage() → ImageIO / clipboard
        ▼
PreviewGalleryPanel.onNavigateToSource(chain)
        └── navigateToSource → resolveSourceFile(fileName, packageHash)   // D7
```

## Components

| Unit | Responsibility | AS-internal? |
|------|----------------|--------------|
| `ZoomMath` (ui/) | Pure zoom ladder + fit + cursor-anchor scroll math | No |
| `ZoomableRenderView` (ui/) | Zoomable/pannable image + hover/click overlay + hand-tool mode | No |
| `RenderImageExporter` (ui/) | Save-PNG (ImageIO + file chooser) and Copy-to-clipboard of the raw image | No |
| `PreviewRenderPanel` (ui/) | Host the scroll pane + view for LIVE; wire the toolbar actions; clear on non-LIVE | No |
| `PreviewSourceLocation` (model/) | + `packageHash` | No |
| `LiveRenderer.toPreviewViewNode` (render/) | Capture `packageHash` from AS `SourceLocation` (guarded, probed) | **Yes** |
| `RenderApiProbe` (render/) | (Optional) extend the view-tree probe with the packageHash accessor | **Yes** |
| `PreviewGalleryPanel.resolveSourceFile` (ui/) | Disambiguate same-named files by packageHash, else `firstOrNull` | No |
| `PreviewViewHitTester` (ui/) | Reused unchanged (`innermostAt`, `sourceChainAt`) | No |

## Testing

- **Pure unit (JUnit, no fixture):** `ZoomMath` (ladder step in/out + clamp at both ends, `fitPercent`, `anchorScroll` keeps a known point stable); the packageHash disambiguation (given candidate files + hashes → the right pick, and `firstOrNull` fallback when none/absent). This is where the branching risk lives, so it is unit-tested.
- **Manual `runIde` gate:** real zoom stepping at the cursor, all three pan mechanisms, the hand-tool mode switch, hover/click correctness at several zoom levels, Save-PNG + Copy of the raw render, and V1/V2 (packageHash match; clipboard paste into an external app). Same manual-verification posture as the Phase 2–4 render/UI work.

## Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R1 | Overlay misaligns under zoom (coordinate regression from Phase 4). | Medium | Medium | `renderPoint = p/zoom` is unit-tested; the gate re-checks hover at 100 %, Fit, 200 %. |
| R2 | Hand-tool mode is confusing (user clicks expecting navigation while in pan mode). | Medium | Low | Distinct hand cursor + the toggle's pressed state; hover outline off in pan mode signals the mode. |
| R3 | packageHash semantics guessed wrong → same-named files still ambiguous. | Medium | Low | Fallback to `firstOrNull` = today's behavior; V1 is a gate; no regression. |
| R4 | Clipboard image flavor not honored on this JBR/OS (V2). | Low | Low | Save-PNG still works; clipboard failure logs + notifies, never crashes. |
| R5 | Zoomed render of a huge preview is memory-heavy (scaled repaint). | Low | Low | Paint scales on the fly from the one raw image (no per-zoom copy); the raw image already exists. |

## Acceptance Criteria

- **AC1** `Ctrl`+scroll over the render steps the zoom ladder anchored at the cursor; `+`/`−`/`Fit`/`100%` work; the zoom-% label reflects the level.
- **AC2** When zoomed past the viewport, the render pans via scrollbars, plain scroll-wheel, and the hand-tool drag.
- **AC3** Hand-tool ON → drag pans and clicks do **not** navigate; hand-tool OFF (default) → hover outlines and click navigates (Phase 4), no drag-pan.
- **AC4** Hover outline and click-to-source land on the correct composable at 100 %, Fit, and a zoomed-in level.
- **AC5** *Save PNG…* writes the raw native-resolution render (no overlay); *Copy* puts it on the clipboard; failures notify, never crash.
- **AC6** Clicking a component whose source file shares its name with another project file navigates to the correct file (or falls back to the previous behavior when packageHash is unavailable).
- **AC7** Phase 1–4 behavior does not regress; `./gradlew test` is green (existing + new pure tests).
