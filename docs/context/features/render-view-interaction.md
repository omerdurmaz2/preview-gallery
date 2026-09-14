# Render view interaction

**Status:** shipped · **Phases:** PG4 (interactive-layer half) · PG5 · PG12 (fit/dp-size half) · PG24-1 (gesture half) · PG25 · **Last change:** 68f7d8e [PG25-8] 2026-09-14

Everything the user does to, and on top of, one rendered preview image: zoom and pan it, export it, hover/select/navigate the Compose view tree drawn under it, and measure the distance between two of its components. Nearly all of the logic is pure, unit-tested geometry (`ZoomMath`, `PreviewViewHitTester`, `MeasurementGeometry`) driving a single Swing component, `ZoomableRenderView`, so the Android-Studio-internal surface stays confined to the tree `LiveRenderer` hands it. All phases below are shipped and covered by tests; the newest layer — Figma-style measurement — landed 2026-09-14.

## What the user gets

- **Zoom.** A stepped ladder (`Zoom in`/`Zoom out`: 25/50/75/100/150/200/300/400%), a `Fit` button, and `100%` (Actual size) on the toolbar; continuous zoom on `Ctrl`/`Cmd`+wheel and macOS trackpad pinch, anchored at the cursor. `100%` shows the composable at Android Studio's own **dp** size, not layoutlib's device-pixel size; `Fit` may upscale a small preview to fill the pane (never anchors on the ladder's 25% floor). Every new render starts at `Fit`.
- **Pan.** Scrollbars, plain wheel (vertical) / Shift+wheel (horizontal) — including the small sub-notch events a trackpad emits for a slow two-finger drag — and a toggled `Hand tool` that drags to pan. While the hand tool is on, hover/select/navigate/measure are all inert.
- **Export.** `Save PNG…` and `Copy image`, both on the raw, full native-resolution render with no overlay, independent of the current zoom.
- **View-tree overlay.** Hovering the render outlines the innermost composable under the cursor. A single left click **selects** it and leaves a persistent 2px outline (does not move the editor or steal focus); a double click **navigates** the editor to its source; `Esc` clears the selection; a new render always clears it. Clicking a source file that shares its name with another project file is disambiguated by `packageHash`.
- **Measurement.** With a component selected, holding Alt (Option on macOS) while pointing at a *different* component draws Figma-style lines between them with a dp label: a gap line when they don't overlap on an axis (with a dashed guide in the diagonal case), or the four edge/padding offsets when one contains or overlaps the other. Releasing Alt removes the lines; pressing Alt again without moving the mouse redraws them immediately.
- **Per tab.** Original and every comparison-view tab each have their own independent zoom, pan, hover, selection and measurement state — the toolbar always acts on whichever tab is visible.
- **Not available on strips.** The `@PreviewParameter` strip and the snapshot reference strip get zoom/pan/fit/export (through the same gesture code), but no hover outline, selection, navigation or measurement — they carry images with no per-image view tree.

## How it works

After a successful render, [`LiveRenderer.buildViewTree`](../../../src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt) converts Android Studio's `ViewInfo`/`ComposeViewInfo` tree into a list of plugin-owned [`PreviewViewNode`](../../../src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt) (bounds in render-pixel space, plus an optional `PreviewSourceLocation` carrying `fileName`/`lineNumber`/`packageHash`), behind `RenderApiProbe.isViewTreeAvailable()` and a guard against `Exception`/`LinkageError` — an incompatible IDE build degrades to an empty tree, never to a lost image. `RenderOutcome.Success` also carries the `dpi` layoutlib rendered at (resolved elsewhere in `LiveRenderer`; see the rendering doc).

`PreviewRenderPanel.showImage` feeds `image` + `viewTree` + `dpi` to `ZoomableRenderView.setContent`, which derives `contentScale = ZoomMath.contentScale(dpi)` (`160/dpi`) and the content's dp size, marks a pending fit, and calls `fitToViewport()`. From then on every on-screen dimension multiplies by one derived `displayScale = zoomFactor * contentScale`: `getPreferredSize`, the image draw, the hover/selection outlines and `renderPointOf` (the inverse, used for hit-testing) all read this single value, so they cannot disagree (PG12-3).

Mouse input, all mapped through `renderPointOf`/`displayScale` into render-pixel space and then `PreviewViewHitTester`:

- `mouseMoved` → `innermostAt(viewTree, point)` → `hovered`, repainted.
- `mouseClicked` with `clickCount == 1` → `selectAt` → same hit test → `selected` (2px outline persists across a later hover).
- `clickCount == 2` → `navigateAt` → `sourceChainAt` (innermost-first list of source locations) → the `onNavigateToSource` callback, wired by `PreviewRenderPanel` straight through to `PreviewGalleryPanel.navigateToSource`, which walks the chain outward until `resolveSourceFile` resolves one to a project `VirtualFile`. `resolveSourceFile` prefers the currently selected entry's own file, else looks the file name up project-wide; when more than one project file shares that name it disambiguates via `SourceFileDisambiguator.pick(location.packageHash, candidates)`, each candidate's hash recomputed from its own package name (`packageHashOf`, `Math.abs(packageFqName.hashCode())` — the same algorithm Android Studio uses internally).
- Alt-down, with a selection and a *different* hovered node → `currentMeasurements()` → `MeasurementGeometry.measure(selected.bounds, hovered.bounds)`, pure rectangle geometry returning gap or edge-offset lines; painted at `displayScale` with a label from `MeasurementGeometry.formatDp(lengthPx, dpi)`, which rounds to the simplest dp value Compose's own `Density.roundToPx` would have produced for that pixel length.

Wheel, `Ctrl`/`Cmd`+wheel and macOS trackpad pinch all go through one shared [`ViewportGestures`](../../../src/main/kotlin/com/devomer/previewgallery/ui/ViewportGestures.kt) instance installed in `init`. It knows nothing about what it zooms: it calls back into a small `Zoom` interface (`ZoomableRenderView`'s own binding here, `PreviewRenderPanel.StripZoom` for the reference/parameter strip) that reports the current factor and applies a new one anchored at the cursor via `ZoomMath.anchorScroll`.

The persistent toolbar (`PreviewRenderPanel.updateActionsBar`) rebuilds a `DefaultActionGroup` on every `show()`/tab change and targets whichever `ZoomableRenderView` is active (`activeView()`): zoom/fit/actual-size act on its `zoomFactor`/`fitToViewport()`, the hand-tool `ToggleAction` mirrors `handToolActive`, and `Save PNG…`/`Copy image` call `RenderImageExporter` against `exportImage()` — the active view's `rawImage()`, or, when a snapshot is showing, the on-screen reference strip repainted into a fresh `BufferedImage`.

| Class / file | Responsibility |
|---|---|
| [ZoomableRenderView](../../../src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt) | The zoomable/pannable component: `zoomFactor`/`displayScale`, paints image + hover/selection outlines + measurements, hit-tests mouse input, hosts the hand tool and the Esc clear-selection action |
| [ZoomMath](../../../src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt) | Pure arithmetic: step ladder, `MIN`/`MAX` bounds, px↔dp conversion, fit factor, cursor-anchor scroll math, wheel zoom/pan constants |
| [ViewportGestures](../../../src/main/kotlin/com/devomer/previewgallery/ui/ViewportGestures.kt) | Wheel (plain pan, Ctrl/Cmd zoom) and trackpad-pinch (`Magnificator`) handling shared by any scroll-paned component via the `Zoom` callback |
| [PreviewViewHitTester](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTester.kt) | Pure geometry: panel-point → render-point, innermost node at a point, innermost-first source chain at a point |
| [MeasurementGeometry](../../../src/main/kotlin/com/devomer/previewgallery/ui/MeasurementGeometry.kt) | Pure geometry: lines/guides between two rectangles (gap vs. edge-offset), and the dp label for a measured pixel length |
| [RenderImageExporter](../../../src/main/kotlin/com/devomer/previewgallery/ui/RenderImageExporter.kt) | Save-as-PNG and clipboard copy of a raw `BufferedImage` |
| [SourceFileDisambiguator](../../../src/main/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguator.kt) | Pure pick of the right same-named candidate file by `packageHash`, else the first |
| [PreviewViewNode](../../../src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt) | Plugin-owned render-pixel-space tree node + `PreviewSourceLocation` (`fileName`, `lineNumber`, `offset` always null, `packageHash`) |
| [PreviewRenderPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt) (toolbar parts) | Builds zoom/fit/hand-tool/export actions per active tab, drives `exportImage()`, wires `onNavigateToSource` |
| [LiveRenderer.buildViewTree](../../../src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt) | Converts Android Studio's view-info tree into `PreviewViewNode`, guarded/probed (render pipeline; rest of this file belongs to the rendering doc) |

Not in this doc's file list but load-bearing for click-to-source: `PreviewGalleryPanel.navigateToSource`/`resolveSourceFile`/`packageHashOf` do the actual editor-open once `onNavigateToSource` fires.

## Key decisions

- Hover outline + click-to-source only, no full interactive/touch render mode — user's call; discoverable, editor-like behaviour without a separate interactive render session — spec D2 (`2026-07-25-config-aware-render-and-interactive-layers-design.md`).
- Overlay coordinates are `renderPoint = componentPoint / displayScale`, no letterbox/fit-rect math, because the component's own bounds *are* the zoomed image — spec D5 (`2026-07-26-render-view-zoom-pan-export-design.md`); widened from `zoomFactor` to `displayScale` (zoom × dp conversion) in PG12-3, `f6c2348`.
- The zoom factor is bounded by `ZoomMath.MIN`/`MAX` (0.05–4.0), not by the ladder's own ends — spec D2 originally clamped the factor to the ladder; superseded by PG12-1 `d9a26d9`/fit-to-view spec D5, because clamping `Fit` to the ladder's 25% floor made a tall device render overflow a short pane and grow scrollbars on the very first render.
- `Fit` may upscale a small preview to fill the viewport — spec D2 originally capped `Fit` at 1.0 ("never auto-upscale"); superseded by PG12-1 `d9a26d9`/fit-to-view spec D6, matching Android Studio's own zoom-to-fit.
- `100%` means Android Studio's dp size, not layoutlib's device-pixel size — fit-to-view spec D3/D4, shipped in `f6c2348` [PG12-3].
- The first render's `Fit` is deferred (`pendingFit`) until the enclosing viewport actually reports a size, retried from a `ComponentListener` on the viewport rather than a timer or an unconditional re-fit on every resize (which would discard a zoom the user chose) — fit-to-view spec D8, `d4a7ce6` [PG12-4]. `PreviewRenderPanel` duplicates the same flag (`referenceFitPending`) for the reference strip, which is a plain component, not a `ZoomableRenderView`.
- Export acts on the raw, native-resolution image, independent of zoom and without the overlay, because that is the shareable artefact — spec D6 (`2026-07-26-...`), `91178c1`/`2f47272` [PG5-2/3].
- `packageHash` disambiguates click-to-source among same-named project files, falling back to `firstOrNull` when the hash is absent or unmatched (never worse than pre-PG5-4 behaviour) — spec D7, `1cd5456` [PG5-4].
- Wheel/pinch handling is one `ViewportGestures` class shared by the render view and the reference strip, because AWT only forwards a wheel event to the scroll pane's own handling when the component installs **no** `MouseWheelListener` at all — there is no partial opt-in — `19908b0` [PG24-1].
- A single click now **selects** (2px persistent outline) instead of navigating; a double click navigates. This is a deliberate break from the PG4/PG5 habit, needed so a selection can outlive the pointer while measuring — component-measurement spec D1, `b43e34b` [PG25-4] (also called out in `CHANGELOG.md`).
- The measurement label rounds to the simplest dp value Compose's own `Density.roundToPx` would have produced for that pixel length (whole dp, else half, else one decimal) rather than a plain `px / density` conversion, because the plain conversion showed `Arrangement.spacedBy(6.dp)` as `6.2dp` — `68f7d8e` [PG25-8].
- `ZoomableRenderView.isFocusable = true` is kept even though `Component.isFocusable()` already defaults to true, because `setFocusable()` is what makes `DefaultFocusTraversalPolicy` accept the component; without the explicit call the lightweight peer is never reachable by focus traversal, and Alt-via-key-events would only ever work by accident — `788e70f` [PG25-7].
- Selection/measurement is out of scope wherever `RenderOutcome.MultiSuccess` applies (`@PreviewParameter` and snapshot strips carry no per-image view tree) — `model/RenderOutcome.kt` doc comment, component-measurement spec D7.

## Android Studio and platform internals relied on

- `ComposeViewInfoParserKt`/`ComposeViewInfo`/`SourceLocation` (`com.android.tools.idea.compose.preview`) — read inside `LiveRenderer.buildViewTree`/`toPreviewViewNode`, off the EDT and off any read action; probed (`RenderApiProbe.isViewTreeAvailable()`) and guarded against `Exception`/`LinkageError`. Full detail is the rendering doc's; this area only consumes the resulting `PreviewViewNode` list.
- `SourceLocation.packageHash`, plus Android Studio's own package-name hashing (`Math.abs(packageFqName.hashCode())`) reverse-engineered from `design-tools.jar` via `javap` — spec V1 (`2026-07-26-render-view-zoom-pan-export-design.md`). No public contract; a future build hashing differently just falls back to `firstOrNull`, never worse.
- `com.intellij.ui.components.Magnificator`/`ZoomableViewport` for macOS trackpad pinch — the same technique the bundled Images plugin's `ImageContainerPane` uses. Installed as a client property inside a `try/catch` for both `Exception` and `LinkageError`, so a shape mismatch costs pinch-zoom only — never the wheel path, which is installed first for that reason.
- `Magnificator`'s contract: the callback must **not** set `viewport.viewPosition` itself — `JBViewport`'s `ZoomingDelegate` derives the scrollbar delta from the callback's *returned* point, so `ViewportGestures.onMagnify` lets the owner's `applyAt` do the scrolling and then re-expresses that result relative to the gesture's focal point.
- `MouseWheelEvent.getPreciseWheelRotation()`, never `getWheelRotation()`/`getUnitsToScroll()` — the integer notch count is 0 for the small events a macOS trackpad emits during a slow two-finger drag, which is what made panning silently do nothing before PG24-1.
- `JViewport`/`JBScrollPane` sizing race: a freshly-added scroll pane's viewport reports a 0×0 extent because `add()` does not lay out synchronously; `pendingFit` plus a `ComponentListener` added/removed in `addNotify`/`removeNotify` retries once a real size arrives, instead of polling.
- The render's own density (`RenderTask.hardwareConfigHelper.config.density.dpiValue`, falling back to `Configuration.density.dpiValue`, then 160) is resolved inside `LiveRenderer` (rendering doc) and reaches this area only as `RenderOutcome.Success.dpi`.
- `JBUIScale.scale(Float)`, not `JBUI.scale(Float)` — the plan originally specified the latter for the measurement stroke/dash widths; switched during the PG25-7 review because `JBUI.scale(float)` is deprecated on platform 261.
- AWT `Toolkit.getDefaultToolkit().systemClipboard` with a hand-built `Transferable` for `DataFlavor.imageFlavor` — no platform-specific handling; whether this actually pastes into an external app on a given JBR/OS was an explicit unverified gate (spec V2), settled manually rather than by an automated test.

## Tests

- [ZoomMathTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt) — ladder step in/out + clamp, `contentScale`/`dpSize` conversion, `fitFactor` (shrink/upscale/below-ladder-floor/clamped/degenerate), `anchorScroll`, `scaleBy`, wheel zoom/pan factor math. Pure, no fixture.
- [PreviewViewHitTesterTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTesterTest.kt) — panel-to-render point mapping, innermost-node-wins, the source chain (innermost first, skips sourceless nodes, drops non-containing ones).
- [MeasurementGeometryTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/MeasurementGeometryTest.kt) — every row of the spec's worked-examples table (side-by-side, stacked, diagonal with its mirrored guide case, containment both ways, partial overlap, touching/identical bounds giving nothing), plus the three `formatDp` rounding tiers.
- [SourceFileDisambiguatorTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguatorTest.kt) — hash match, null-hash fallback, no-match fallback, empty candidates.
- [RenderImageExporterTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/RenderImageExporterTest.kt) — one test: `savePng` round-trips through `ImageIO`. No automated test for `copyToClipboard` (see the AWT clipboard note above).
- [ZoomableRenderViewTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt) — dp-space sizing (`getPreferredSize`, hover outline, hit-testing all through `displayScale` rather than raw `zoomFactor`, at a non-identity density), the deferred-fit state machine, and the full PG25 interaction surface: a click selects without navigating, a double click navigates, clicking outside/Esc/new content each clear the selection, the hand tool blocks both select and measure, the selection outline actually paints, and Alt from a mouse move, a key press, or a focus loss each turns measurement on and off.
- [ZoomableRenderViewZoomAndPanTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewZoomAndPanTest.kt) — split out of the class above in PG12-3 to keep it focused: wheel pan (plain/shift, precise-rotation, clamped to the content extent), Ctrl/Cmd+wheel continuous zoom anchored at the cursor, and the `Magnificator` pinch path (installs, scales, no-ops at scale 1).
- [PreviewRenderPanelTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanelTest.kt) — mostly the snapshot/Properties capability gate, but incidentally pins that zoom/fit/export controls appear together and that the hand tool is render-only (absent for a reference strip).
- Every PG25 test was shown to fail with its production line reverted (`a76f200` [PG25-6]; the plan's revert table lists the exact mutation per test).

Notable test gaps:
- `PreviewGalleryPanel.navigateToSource`/`resolveSourceFile`/`packageHashOf` — the actual editor-open once `onNavigateToSource` fires, packageHash disambiguation included — has no test; only the pure `SourceFileDisambiguator.pick` is covered (verified: neither name appears anywhere under `src/test/`).
- No test exercises real trackpad/pinch hardware or an actual OS clipboard paste into another app; both are manual `runIde` gates by design (zoom-pan-export spec V2/V3).

## Open items

- [gap] Selection is not preserved across a re-render — every new render clears it, so edit-then-measure means reselecting each time — component-measurement spec "Out of scope", D6.
- [gap] No multi-selection, and no Figma-style measure-to-parent without hovering — component-measurement spec "Out of scope".
- [gap] No "select parent" for a parent fully covered by its children — component-measurement spec "Out of scope".
- [limitation] Hover/select/navigate/measure only work where a view tree exists; `@PreviewParameter` strips and snapshot reference strips carry images with no per-image view tree — `model/RenderOutcome.kt` (`MultiSuccess` doc), component-measurement spec D7.
- [limitation] A gap built from two separately-rounded positions (e.g. `SpaceBetween`, leftover weight pixels) can still be a pixel off even after PG25-8's rounding-aware label — component-measurement spec "Label" section, `68f7d8e` [PG25-8].
- [limitation] When the render's density can't be read, `LiveRenderer` falls back to 160 dpi and the measurement label then shows raw pixels labelled as dp — the same fallback zoom's dp conversion relies on — component-measurement spec "Label" section.
- [bug] A measurement label pill centered on a line at the render's edge can get clipped by the component bounds; cosmetic, parked at the PG25 final review (2026-09-14). `paintLabel` computes its rectangle from the text metrics with no containment clamp (`ui/ZoomableRenderView.kt`).
- [bug] On Windows and Linux, holding Alt to measure also triggers IntelliJ's tool-window stripe-number overlay; measurement itself still works. Parked at the PG25 final review (2026-09-14); platform behaviour, not verifiable in this codebase.
- [debt] No automated coverage for `PreviewGalleryPanel.navigateToSource`/`resolveSourceFile`/`packageHashOf` (see Tests).
- [idea] A cached multi-step downscale for `displayScale < 0.5`, to reduce bilinear aliasing on a heavily zoomed-out render — fit-to-view spec "Follow-ups"; explicitly "measure before building", not started.

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG4 (Feature B) | 2026-07-25 – 2026-07-26 | `90aa43c`, `dd83b98`, `67b333e`, `8a37c75` | Hit-testing geometry, hover outline and (single-click) click-to-source over the then-static, fit-to-panel render image; `PreviewViewNode`/`PreviewSourceLocation` introduced (`573612e`, shared with the rendering doc) |
| PG5 | 2026-07-26 | `00a82aa`, `91178c1`, `2f47272`, `e46a2c7`, `1cd5456`, `761ad5e` | Replaced the static image label with `ZoomableRenderView` in a `JBScrollPane`: zoom ladder, hand-tool pan, Save PNG/Copy export, native `ActionToolbar` icons, `packageHash` click disambiguation, first-render fit via layout validation |
| PG12 (fit/dp-size half) | 2026-07-29 – 2026-07-30 | `d9a26d9`, `f6c2348`, `c370718`, `d4a7ce6`, `d27168d`, `533e075` | Converted the display to dp space (`contentScale`/`displayScale`), freed `Fit` from the ladder's 25% floor and the 1.0 no-upscale cap, deferred the first fit until the viewport has a real size, documented trackpad pinch as a ladder-constrained continuous input |
| PG24-1 (gesture half) | 2026-08-20 | `19908b0` | Extracted wheel/pinch handling into `ViewportGestures`, shared with the reference strip; fixed precise-rotation panning, the 3px-per-notch pan distance, and per-event ladder jumping |
| PG25-1..8 | 2026-09-14 | `6cec72e`, `5f8fcf4`, `c5c108d`, `b43e34b`, `556e4be`, `a76f200`, `788e70f`, `68f7d8e` | Figma-style measurement: click now selects (double click navigates), Alt-hover draws gap/edge-offset lines with a rounding-aware dp label between the selection and the hovered node |

## References

- [Config-aware render & interactive layers design](../../superpowers/specs/2026-07-25-config-aware-render-and-interactive-layers-design.md) — §5 (Feature B) and D2–D4
- [Config-aware render & interactive layers plan](../../superpowers/plans/2026-07-25-config-aware-render-and-interactive-layers.md)
- [Render view zoom/pan/export design](../../superpowers/specs/2026-07-26-render-view-zoom-pan-export-design.md)
- [Render view zoom/pan/export plan](../../superpowers/plans/2026-07-26-render-view-zoom-pan-export.md)
- [Preview fit-to-view design](../../superpowers/specs/2026-07-29-preview-fit-to-view-design.md)
- [Preview fit-to-view plan](../../superpowers/plans/2026-07-29-preview-fit-to-view.md)
- [Component measurement design](../../superpowers/specs/2026-09-14-component-measurement-design.md)
- [Component measurement plan](../../superpowers/plans/2026-09-14-component-measurement.md)
- [Feature overview](../../feature-overview.md) — §1 (zoom/pan/export/measure/click-to-source as seen by a user)
- [CHANGELOG](../../../CHANGELOG.md) — `[Unreleased]` (measurement, click→select) and `[0.1.0]` (everything else in this doc)
- [README](../../../README.md) — Known limitations
