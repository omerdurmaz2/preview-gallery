# Preview Gallery — Config-aware Render & Interactive Layers Design

| | |
|---|---|
| **Status** | Approved — ready for implementation planning |
| **Date** | 2026-07-25 |
| **Scope** | Apply each preview's `@Preview` arguments to the render, refresh on picker change, and make the rendered image hover-highlight and click-to-source like the editor |
| **Builds on** | [Phase 2 MVP](2026-07-23-preview-gallery-phase2-mvp-design.md), [Property Picker](2026-07-24-preview-property-picker-design.md) |
| **Target IDE** | Android Studio Panda 4 — build `AI-253.32098.37.2534.15336583` (platform branch 253) |

---

## 1. Scope

Two related gaps, both about making the gallery's render behave like the editor's Compose preview tab.

### A. Config-aware render + picker refresh

Today `RenderModelResolver` builds the preview with `PreviewConfiguration.cleanAndGet(null…)` — a fixed default
— so `@Preview(device=…, showSystemUi=…, apiLevel=…, widthDp=…)` is ignored: every preview renders at the same
default device regardless of its annotation. And the property picker's change signal is a stub
(`onPickerModification` only logs), so editing a value does not refresh the render. The picker exists so a change
can be seen immediately; that only works once both halves are fixed.

### B. Hover outline + click-to-source

The editor's preview lets you hover a composable to see its outline and click it to jump to its source. The
gallery shows a flat image with no such interaction.

### In scope

- Render each preview with the configuration from its own `@Preview` annotation.
- Re-render the current preview when the picker reports a change (no reselect).
- Hover over the rendered image highlights the innermost composable's bounds.
- Clicking navigates the editor to that composable's source.

### Out of scope

- Full interactive mode (touch, gestures, animation, state) — that is a separate render session and event
  injection; rejected during design.
- Editing properties by any means other than the existing picker.
- Persisting a per-preview configuration override in plugin state (the source annotation remains the single
  source of truth, per the picker feature's D1).
- Navigating anywhere other than the composable's own source (no cross-screen navigation).

### 1.1 Decisions

| # | Question | Decision | Rationale |
|---|---|---|---|
| D1 | How to apply the `@Preview` config | Use Android Studio's `AnnotationFilePreviewElementFinder` to build a `ComposePreviewElement` from the PSI, which already carries the annotation's device/api/size/etc. | Least code and exactly AS's own behaviour. Parsing `@Preview` arguments by hand (device strings, size units, uiMode flags) would duplicate a lot of AS logic and drift from it |
| D2 | Interactivity depth | Hover outline + click-to-source | User's call. It is the discoverable, editor-like behaviour without the cost of a full interactive render session |
| D3 | Where the ViewInfo tree is exposed | Converted in `LiveRenderer` to a plugin-owned `PreviewViewNode` model; the panel never sees `com.android.tools.*` | Keeps the AS-internal surface confined to `render/`, as every prior phase did |
| D4 | Failure posture | Config-aware element falls back to the current default-config path; a missing/failed ViewInfo tree disables hover/navigate but still shows the image | Same degrade-don't-break posture as the renderer and picker |

---

## 2. Verified API surface

Probed against the Android Studio 253 jars. All in the bundled `org.jetbrains.android` / `com.android.tools.design`
plugins already depended on. `com.android.tools.*` internal API — §6 applies.

```
# A — config-aware preview element
com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder      (builds ComposePreviewElements from a file's @Preview annotations, config included)
com.android.tools.preview.ComposePreviewElementInstance                        (the element; XmlSerializable — has toPreviewXml())
com.android.tools.preview.ConfigurablePreviewElement                           (applyTo(configuration) — carries device/api/size onto a Configuration)
com.android.tools.preview.PreviewConfiguration
    static cleanAndGet(Integer apiLevel, Integer width, Integer height, String locale, Float fontScale,
                       Integer uiMode, String device, Integer wallpaper, Consumer<BufferedImage>)

# B — view tree + source navigation
com.android.tools.rendering.RenderResult
    getRootViews(): ImmutableList<ViewInfo>
com.android.ide.common.rendering.api.ViewInfo
    getLeft()/getTop()/getRight()/getBottom(): int      (bounds, in render pixels)
    getChildren(): List<ViewInfo>
    getCookie(): Object                                 (source key for Compose)
com.android.tools.idea.compose.preview.ComposeViewInfoParserKt                 (parses ViewInfo into ComposeViewInfo with source locations)
com.android.tools.idea.compose.preview.ComposeViewInfo
com.android.tools.idea.compose.preview.SourceLocation / SourceLocationImpl     (file name, line, offset)
```

### 2.1 Unverified — settled against a running IDE (the two gate tasks)

| # | Unknown | Where it bites |
|---|---|---|
| V1 | How `AnnotationFilePreviewElementFinder` is invoked, and how to select the element matching a `PreviewEntry` (by `composableFqn` + `displayName`) | Building the config-aware element |
| V2 | How `ComposeViewInfoParserKt` turns a `RenderResult`'s `ViewInfo` list into source-located nodes | The hover/navigate tree |
| V3 | Turning a `SourceLocation` (file + line/offset) into an editor navigation | Click-to-source |
| V4 | The transform from a panel mouse point to render-pixel coordinates (the image is scaled to fit) | Hit-testing the right node |

---

## 3. Architecture

```
@Preview PSI ──▶ RenderModelResolver ──(AnnotationFilePreviewElementFinder)──▶ config-aware ComposePreviewElement
                        │
                        ▼
                  LiveRenderer.render ──▶ RenderResult ──▶ image  +  getRootViews() ──(ComposeViewInfoParser)──▶ PreviewViewNode tree
                        │                                                                        │
                        ▼                                                                        ▼
              RenderOutcome.Success(image, viewTree: List<PreviewViewNode>)          (plugin-owned, no com.android.tools.*)
                        │
                        ▼
              PreviewRenderPanel ── hover: hit-test viewTree → outline overlay
                                 └─ click: node.sourceLocation → OpenFileDescriptor → editor

picker change ──▶ onPickerModification ──▶ RenderPipeline.rerenderCurrent() ──▶ (reads the now-edited annotation)
```

| Component | Change | AS-internal? |
|---|---|---|
| `RenderModelResolver` | Build the element via the finder (config-aware); fall back to the current default path | **Yes** |
| `LiveRenderer` | Also return the view tree, converted from `ViewInfo` to `PreviewViewNode` | **Yes** |
| `PreviewViewNode` | New plugin-owned model: `bounds: Rectangle`, `sourceLocation: PreviewSourceLocation?`, `children` | No |
| `RenderOutcome.Success` | Gains `viewTree: List<PreviewViewNode>` (empty when unavailable) | No |
| `PreviewRenderPanel` | Hover overlay + click-to-source over the view tree | No |
| `RenderPipeline` | `rerenderCurrent()` (was deferred as PG3-3) | No |
| `PreviewGalleryPanel` | `onPickerModification` → `rerenderCurrent()` | No |

`PreviewViewNode` and `PreviewSourceLocation` are the boundary: `LiveRenderer` produces them from AS types, and
the panel consumes only them.

---

## 4. Feature A — config-aware render + refresh

### 4.1 Config-aware element

`RenderModelResolver` currently constructs a `SingleComposePreviewElementInstance` from the FQN with a default
`PreviewConfiguration`. Instead:

1. Run `AnnotationFilePreviewElementFinder` over the preview's `KtFile` to get the file's `ComposePreviewElement`s,
   each carrying its `@Preview` configuration (V1).
2. Select the one whose composable FQN and preview name match the `PreviewEntry`.
3. Feed that element into the existing render path (it is `XmlSerializable`, so `toPreviewXml()` works as before);
   its `ConfigurablePreviewElement.applyTo(configuration)` puts the device/api/size onto the `Configuration`.

If the finder yields no matching element (or throws), fall back to the current default-config
`SingleComposePreviewElementInstance` — the render still works, just at the default device.

### 4.2 Refresh on change

`onPickerModification` (already wired from the picker's `GalleryPickerTracker`) calls
`RenderPipeline.rerenderCurrent()`. The picker has already written the change into the source annotation, so the
re-render re-runs the finder and picks up the new configuration. `rerenderCurrent()` re-renders the current entry
without changing selection and respects the generation counter (a later change supersedes an earlier in-flight
render).

Because the picker may fire many modifications in a row, `rerenderCurrent()` is debounced (reusing the pipeline's
existing debounce/generation machinery) so dragging a slider does not queue a render per pixel.

---

## 5. Feature B — hover outline + click-to-source

### 5.1 The view tree

After a successful render, `LiveRenderer` reads `RenderResult.getRootViews()` and converts it — via
`ComposeViewInfoParserKt` (V2) — into a tree of plugin-owned nodes:

```kotlin
data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?)
data class PreviewViewNode(
    val bounds: java.awt.Rectangle,          // in render-pixel space
    val sourceLocation: PreviewSourceLocation?,
    val children: List<PreviewViewNode>,
)
```

The conversion happens inside `LiveRenderer` (the AS-internal boundary). If parsing fails, the tree is empty and
B is simply inert.

### 5.2 Hover and click

`PreviewRenderPanel` already scales the image to fit. It records the scale + offset of the drawn image, so a
panel mouse point maps to a render-pixel point (V4). On that point:

- **Hover** (`MouseMotionListener`): hit-test the tree for the innermost node whose `bounds` contains the point;
  paint its `bounds` (mapped back to panel space) as an outline overlay. Debounced/throttled so mouse-move stays
  cheap.
- **Click** (`MouseListener`): the innermost node's `sourceLocation`, if present, becomes an
  `OpenFileDescriptor(project, file, offset)` (resolving the file by name within the entry's module/project) and
  navigates the editor (V3). No source location → no-op.

Hit-testing, scaling and painting are plain Swing over the plugin-owned tree — no AS API in the panel.

---

## 6. API-stability posture

Same as the renderer and picker (Phase 2 §5.3): the new AS-internal calls are guarded against `Exception` and
`LinkageError`, and the capability probe (`RenderApiProbe`) is extended with the finder and view-info classes.
Concretely (D4):

- Config-aware element unavailable → fall back to the default-config element; render unaffected.
- View tree unavailable → `viewTree` is empty; hover and click are inert; the image still shows.

Neither failure removes any existing behaviour.

---

## 7. Testing

| Target | How |
|---|---|
| `PreviewViewNode` hit-testing (innermost-node-at-point, scale mapping) | Plain JUnit against a synthetic tree — no IDE fixture |
| `RenderPipeline.rerenderCurrent()` | Plain JUnit with a fake renderer: re-renders the current entry, no-op when nothing selected, respects the generation counter, debounced |
| `RenderModelResolver` element selection (matching a `PreviewEntry` to a finder element) | `BasePlatformTestCase` with Kotlin fixtures if the finder can run headlessly; otherwise verified in the runIde gate |
| Config-aware render, the finder, ViewInfo parsing, hover/click | **Manual, in `runIde`** — AS-internal, same rule as the renderer |

## 8. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | The finder returns an element type the render path can't consume (e.g. a parametrized template) | Medium | Medium | Match only single instances; fall back to the default-config path for anything else (D4) |
| R2 | `ComposeViewInfoParserKt` API differs from the probe | Medium | Medium — B inert | V2 is a gate; guarded, and B degrades to no-op |
| R3 | Coordinate mapping is off, so hover highlights the wrong node | Medium | Low | V4 verified in the gate against a known preview; a wrong outline is cosmetic |
| R4 | `rerenderCurrent` storms during a slider drag | Low | Low | Debounced through the existing pipeline machinery |
| R5 | AS changes finder/view-info APIs on upgrade | High over time | Low | §6 probe + guard; both features degrade, gallery + render keep working |

## 9. Acceptance criteria

| # | Criterion |
|---|---|
| AC1 | A preview declared `@Preview(showSystemUi = true)` renders with the system UI; one with a `device=` renders at that device — not the default |
| AC2 | Changing the device (or showSystemUi, apiLevel, …) in the picker refreshes the render to match, without reselecting |
| AC3 | Hovering the rendered image outlines the innermost composable under the cursor |
| AC4 | Clicking navigates the editor to that composable's source line |
| AC5 | With the finder unavailable, previews still render at the default config; with ViewInfo parsing unavailable, the image still shows and hover/click are simply inert |
| AC6 | `./gradlew test` passes, including the new hit-test and `rerenderCurrent` tests |
```
