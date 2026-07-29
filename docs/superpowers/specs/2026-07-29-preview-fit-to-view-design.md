# Fit-to-View First Render — Show the Whole Preview at Android Studio's Size

| | |
|---|---|
| **Scope** | Phase 12 — the first render of a preview shows the composable *whole*, inside the render pane, at the size Android Studio's own preview shows it. Fixes the zoom floor that makes Fit under-shrink, the layout race that makes Fit not run at all, and the missing dp normalization that makes every render ~2.75× larger than Android Studio's. |
| **Builds on** | [Phase 6 zoom/pan/export](2026-07-26-render-view-zoom-pan-export-design.md) — that phase introduced `ZoomableRenderView`, `ZoomMath`, and the Fit / 100% toolbar actions this one corrects. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 |
| **Commit prefix** | `[PG12-N]` |

## Goal

Open a preview in the gallery and the rendered image overflows the pane: the user sees the top-left corner of a phone and has to scroll to learn what the composable looks like. Android Studio's own preview shows the same composable whole, at a readable size, the moment it appears. The gallery should do the same — the first render is a *look at this*, not a *go find it*.

After the first render the user is in charge: zoom in to inspect, pan, or leave it. Nothing here changes what the zoom controls do; it changes where zoom *starts* and what "100%" means.

## Non-Goals

- **Changing what is rendered.** `RenderingMode.SHRINK` vs. `NORMAL`, `disableDecorations()`, the device, the density, the `@Preview` arguments — all untouched. This phase only changes how the resulting image is *displayed*.
- **Changing the exported image.** `RenderImageExporter` keeps writing the full-resolution layoutlib image. Display scaling is display-only.
- **Persisting zoom across renders or restarts.** Every new render still starts at Fit, as today.
- **Re-fitting on every tool window resize.** A resize after the user has settled on a zoom leaves that zoom alone.
- **A high-quality multi-step downscaler.** See [Follow-ups](#follow-ups).

## Current state (what this builds on)

`LiveRenderer` hands `RenderOutcome.Success(image, viewTree)` to `PreviewRenderPanel.showImage`, which calls `renderView.setContent(image, viewTree)`, then `centerPanel.validate()`, then `renderView.fitToViewport()`. `ZoomableRenderView` holds one `zoomFactor: Double`, uses it for `getPreferredSize`, `drawImage`, the hover outline, and `renderPointOf` (hit-testing), and its setter clamps to `coerceIn(ZoomMath.LADDER.first(), ZoomMath.LADDER.last())` — that is `[0.25, 4.0]`. `ZoomMath.fitFactor(viewport, image)` returns `min(viewportW/imageW, viewportH/imageH)` capped at `1.0`, and returns `1.0` for a degenerate (zero-sized) viewport or image.

Three independent defects fall out of that:

1. **The zoom floor swallows Fit.** layoutlib renders at the device's pixel density — a Pixel-class device at 440 dpi is a 1080×2340 image. Whenever the true fit factor falls below `0.25` the setter clamps it back up, the view reports a preferred size larger than the viewport, and the scroll pane grows scrollbars. For a 2340 px tall image that threshold is a render pane shorter than **585 px**; for a 1440×3120 device it is 780 px. A render pane that shares a tool window with the preview tree, a toolbar and a status row is routinely shorter than either. Fit silently does not fit.
2. **Fit runs before there is a viewport.** On the first render after the tool window opens, the enclosing `JViewport` still reports a `0×0` extent. `fitFactor` reads that as degenerate and returns `1.0` — full size. The `centerPanel.validate()` call at `PreviewRenderPanel.kt:351` exists to work around exactly this and does not always win; nothing retries once a real extent arrives.
3. **The display unit is device pixels, not dp.** Android Studio's design surface draws a preview at *dp* size: at 100% zoom, 1 dp is 1 logical screen pixel, so a 393×851 dp phone preview occupies 393×851. The gallery draws the raw 1080×2340 layoutlib pixels, so the same preview is ~2.75× larger and "100%" means something different from Android Studio's "100%".

Verified against the local Android Studio install (`plugins/android/lib/android.jar`, `layoutlib-api.jar`): `RenderTask.getHardwareConfigHelper()` is public, `HardwareConfigHelper.getConfig()` returns a `HardwareConfig`, and `HardwareConfig.getDensity().getDpiValue()` is the dpi layoutlib actually rendered at. `HardwareConfigHelper` is constructed from the `Device`, so this is the render's own dpi, not a re-derivation. `Configuration.getDensity()` reads the folder configuration's density qualifier and falls back to `Density.MEDIUM` (160) — the same value, from the same device state, and a safe secondary source.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **`RenderOutcome.Success` carries `dpi: Int = 160`.** `LiveRenderer` fills it from `task.hardwareConfigHelper.config.density.dpiValue`, falling back to `model.configuration.density.dpiValue`, then to `160`. | The view cannot convert pixels to dp without the render's density. Reading it from the `RenderTask` gets the value layoutlib used rather than one re-derived from the device. The `160` default makes the conversion the identity, so any guard failure degrades to today's exact behaviour instead of a wrong size. |
| D2 | Both fallbacks are guarded against `Exception` and `LinkageError`, like every other `com.android.tools.*` call in `LiveRenderer`. | House rule (design §5.3): an API shape change on another IDE build must degrade, never crash. |
| D3 | **`ZoomableRenderView` draws in dp space.** `setContent(image, viewTree, dpi)` stores `contentScale = 160.0 / dpi`; a new `displayScale = zoomFactor * contentScale` replaces `zoomFactor` in `getPreferredSize`, `paintComponent`'s `drawImage`, the hover-outline rectangle, and `renderPointOf`. | One derived value at one place keeps the four consumers consistent by construction. Because the *image* stays full-resolution and only the draw is scaled, a HiDPI screen still gets a sharp result and the export path is untouched. |
| D4 | After D3, `zoomFactor` means **the Android Studio zoom percentage**: the toolbar's `100%` shows the preview at dp size, exactly as Android Studio's own 100% does. | This is the "same displayed size" half of the request. It also puts the typical fit factor near `0.8` instead of `0.20`, so Fit stops living at the bottom of the range. |
| D5 | **`ZoomMath` gains `MIN = 0.05` and `MAX = 4.0`;** `LADDER` stops being the clamp range and is only the set of step stops. The `zoomFactor` setter clamps to `[MIN, MAX]`. | Defect 1. Fit must be free to pick a factor the ladder does not contain — a large tablet preview in a narrow pane genuinely needs less than 25%. `MIN` is a sanity floor against a degenerate zero, not a UX limit. `stepIn`/`stepOut` are unchanged, so the buttons still walk the familiar 25/50/75/100/150/200/300/400 stops. |
| D6 | **`fitFactor` takes the content size in dp and may upscale,** clamped to `[MIN, MAX]`. The `1.0` cap is removed. | The user chose "small previews grow to fill the pane", which is what Android Studio's zoom-to-fit does. A 48 dp icon at 100% in a 800 px pane is a speck; at fit it is legible. Feeding `fitFactor` dp rather than pixels is what makes its result a *zoom percentage* consistent with D4. |
| D7 | The dp conversion lives in `ZoomMath` as pure functions — `contentScale(dpi)` and `dpSize(imagePx, dpi)` — not inline in the view. `dpi <= 0` yields `contentScale = 1.0`. | `ZoomableRenderView` is Swing and effectively untestable headless; `ZoomMath` is pure and already unit-tested. Keeping the arithmetic there is how this phase gets test coverage at all. |
| D8 | **`ZoomableRenderView` gains a `pendingFit` flag.** `setContent` sets it and attempts a fit; `fitToViewport` clears it only when the viewport reports a non-degenerate extent, and returns leaving it set otherwise. A `ComponentListener` installed on the enclosing viewport in `addNotify` (removed in `removeNotify`) retries the fit on resize *while the flag is set*. Any manual zoom — toolbar action, Ctrl+wheel, trackpad pinch — clears it. | Defect 2, without the failure modes of the alternatives: an `invokeLater` retry loop spins forever on a component that is never shown, and an unconditional re-fit on resize would throw away a zoom the user chose. The flag makes "we still owe this render a fit" explicit and self-limiting. |
| D9 | `PreviewRenderPanel`'s existing `centerPanel.validate()` before `fitToViewport()` (lines 351 and 443) stays. | It is now a fast path rather than the only mechanism. Removing it would make every first render depend on a resize event that may not arrive. |
| D10 | Comparison-view tabs get the same treatment: the extra `ZoomableRenderView` at `PreviewRenderPanel.kt:525` receives `outcome.dpi` too. | A comparison tab renders a different device — often a different density — than Original. Passing dpi per view is what keeps two tabs comparable on screen. |
| D11 | Draw quality: keep `VALUE_INTERPOLATION_BILINEAR` and add `KEY_RENDERING = VALUE_RENDER_QUALITY`. | Downscaling ~1080 px to ~393 px in one bilinear step aliases thin strokes and text. A cached multi-step downscale would fix it properly but adds an image cache and its invalidation to a change that is otherwise arithmetic. Deferred deliberately — see [Follow-ups](#follow-ups). |

## Architecture

| Unit | Change | Responsibility |
|---|---|---|
| `model/RenderOutcome.kt` | `Success` gains `dpi: Int = 160`. | Carries the render's density to the display layer. |
| `render/LiveRenderer.kt` | `verifySomethingWasDrawn` takes the resolved dpi; a new guarded `renderDpi(task, model)` helper resolves it (D1, D2). | Reads the density layoutlib rendered at. |
| `ui/ZoomMath.kt` | Adds `MIN`, `MAX`, `contentScale(dpi)`, `dpSize(imagePx, dpi)`; `fitFactor` takes dp content size, drops the `1.0` cap, clamps to `[MIN, MAX]` (D5–D7). | All zoom/scale arithmetic. Pure, unit-tested. |
| `ui/ZoomableRenderView.kt` | `setContent(image, viewTree, dpi)`; `contentScale` field; `displayScale` derived value used by `getPreferredSize`, `paintComponent`, hover outline, `renderPointOf`; `pendingFit` + viewport `ComponentListener` (D3, D8); `KEY_RENDERING` hint (D11). | Draws and hit-tests in dp space; owes each new render a fit until it can honour one. |
| `ui/PreviewRenderPanel.kt` | Passes `success.dpi` / `outcome.dpi` to both the Original view and comparison tabs (D10). | Unchanged responsibilities. |

Data flow, unchanged in shape:

```
LiveRenderer ──RenderOutcome.Success(image, viewTree, dpi)──> PreviewRenderPanel
                                                                    │
                                                    setContent(image, viewTree, dpi)
                                                                    ▼
                                                          ZoomableRenderView
                                          contentScale = 160/dpi ; zoomFactor = fit
                                          displayScale = zoomFactor * contentScale
```

## Worked example

A Pixel-class `@Preview` (393×851 dp at 440 dpi) in a 400×560 render pane:

| | Today | After |
|---|---|---|
| Image | 1080×2340 px | 1080×2340 px (unchanged) |
| `contentScale` | — (implicitly 1.0) | `160/440 = 0.3636` |
| dp size | — | `393×851` |
| Fit factor | `min(400/1080, 560/2340) = min(0.370, 0.239) = 0.239` → clamped up to **0.25** | `min(400/393, 560/851) = min(1.018, 0.658) =` **0.658** |
| Drawn size | `1080×2340 × 0.25 = 270×585` — **585 > 560**, so it overflows and scrollbars appear | `1080×2340 × 0.658 × 0.3636 = 259×560` — fits, whole |
| What `100%` shows | 1080×2340 (overflows by far) | 393×851 — identical to Android Studio |

## Testing

`ZoomMathTest` is where this phase is verified; the existing `fitFactor` cases change meaning and are rewritten rather than kept.

| Case | Expectation |
|---|---|
| `contentScale(440)` | `≈ 0.3636` |
| `contentScale(160)` | `1.0` — identity, the fallback path |
| `contentScale(0)` and `contentScale(-1)` | `1.0` — no division blow-up |
| `dpSize(Dimension(1080, 2340), 440)` | `≈ 393×851` |
| `fitFactor` with content smaller than the viewport | greater than `1.0` — upscales (D6) |
| `fitFactor` with a huge tablet in a narrow pane | below `0.25`, proving the ladder no longer floors it (D5) |
| `fitFactor` beyond either bound | clamped to `MIN` / `MAX` |
| `fitFactor` with a zero viewport or zero content | `1.0` — degenerate guard preserved |
| `stepIn` / `stepOut` / `anchorScroll` | unchanged; existing cases still pass |

`ZoomableRenderView`'s Swing behaviour (the `pendingFit` retry, the listener lifecycle) is exercised through a `BasePlatformTestCase` that builds the view inside a `JBScrollPane`, calls `setContent` with a zero-sized viewport, asserts the zoom is untouched and the flag still set, then sizes the scroll pane, fires the layout, and asserts the fit landed. If the headless harness cannot produce a viewport resize event, the test drives `fitToViewport()` directly after sizing — the flag's state before and after is the assertion either way.

## Risks

| Risk | Mitigation |
|---|---|
| `RenderTask.getHardwareConfigHelper()` or `HardwareConfig.getDensity()` changes shape on another IDE build. | D2's guards: fall back to `Configuration.getDensity()`, then to `160`, which reproduces today's rendering exactly. |
| A density the folder qualifier reports differs from what layoutlib used (e.g. an override path). | The primary source is the `RenderTask`'s own `HardwareConfig`, so this only matters on the fallback, where a one-bucket error is a few percent of scale — visible to nobody. |
| Aliasing after the downscale makes text look worse than today. | D11's `RENDER_QUALITY` hint; a proper multi-step downscale is a scoped follow-up, not a blocker. |
| `pendingFit` never clears because the viewport never resizes. | The flag only suppresses a *re-fit*; the view still renders at whatever `zoomFactor` holds, and every manual zoom clears it. Worst case is today's behaviour. |

## Follow-ups

- **Cached multi-step downscale.** Render a half-by-half reduced `BufferedImage` when `displayScale < 0.5`, cache it against `(image identity, displayScale)`, invalidate on `setContent` and on zoom change. Worth doing if D11's hint proves insufficient in practice; measure before building.
- **`RenderConfig.kt` is dead code.** Its `deviceSpec = "spec:width=411dp,height=891dp"` and `RenderConfig.DEFAULT` are referenced nowhere in `src/main` or `src/test`. Unrelated to this phase; noted so the next reader does not mistake it for the size source.
