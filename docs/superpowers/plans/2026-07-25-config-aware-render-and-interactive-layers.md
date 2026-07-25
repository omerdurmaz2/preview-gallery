# Config-aware Render & Interactive Layers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render each preview with its own `@Preview` configuration, refresh when the picker edits it, and make the rendered image hover-highlight and click-to-source like the editor.

**Architecture:** `RenderModelResolver` builds a config-aware `ComposePreviewElement` via Android Studio's `AnnotationFilePreviewElementFinder` (falling back to today's default-config element); `LiveRenderer` also converts the render's `ViewInfo` tree into a plugin-owned `PreviewViewNode` model; `PreviewRenderPanel` hit-tests that model for a hover outline and click-to-source; and `RenderPipeline.rerenderCurrent()`, driven by the picker's change signal, re-renders in place.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-25-config-aware-render-and-interactive-layers-design.md](../specs/2026-07-25-config-aware-render-and-interactive-layers-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`.
- **Never use the Kotlin `!!` operator.**
- **AS-internal API (`com.android.tools.*`) only in `render/`: `LiveRenderer`, `RenderModelResolver` (and the existing `PreviewPickerBridge`/`GalleryPickerTracker`, `RenderApiProbe`).** `PreviewViewNode`, `PreviewRenderPanel`, `RenderPipeline`, `PreviewGalleryPanel` must not import `com.android.tools.*`.
- **Every AS-internal call site is guarded** against `Exception` and `LinkageError`; the capability probe gates each feature. Both features degrade to no-op on failure — never remove existing behaviour (spec §6, D4).
- **Never render on the EDT; PSI/project-model access under a read action.** (Established in Phase 2/3; the render already runs off the read action after PG3-6 — do not regress that.)
- **Pure-logic tests are consolidated into the final test task (Task 6)** — the standing preference across Phase 1–3. AS-internal tasks (2, 3, 5) end with a `runIde` gate verified by the user, which is behaviour confirmation, not a unit test.
- Commit message format: `[PG4-N] - Task name`.
- All documentation, code comments, and commit messages in English.
- Phase 1–3 behaviour must not regress; the suite is currently 101 tests green.

## Verification style

Tasks 1 and 4 are pure Kotlin and fully specified. Tasks 2, 3, 5 are **discovery-with-a-verification-gate**: the
spec's unknowns V1–V4 (how the finder is invoked, how `ComposeViewInfoParserKt` maps to source, how a
`SourceLocation` navigates, how panel points map to render pixels) cannot be settled without a running IDE, so
each ends with a `runIde` check by the user, exactly as the Phase 2 render gate and the Phase 3 picker gate did.

---

### Task 1: `rerenderCurrent()` and the picker refresh wiring

**Goal:** Editing a value in the picker re-renders the current preview in place. This is the deferred PG3-3 work,
and it is pure platform code — no AS-internal API.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Produces: `RenderPipeline.rerenderCurrent()` — re-render the entry currently displayed, without changing
  selection, debounced, respecting the generation counter.

- [ ] **Step 1: Read the current pipeline**

Read `RenderPipeline.kt` fully. It already keeps the selected entry for its generation counter (from PG2-6 and
PG3-6) and has a private `render(entry, gen)` plus a debounce `Alarm`. Identify the field that holds the current
entry (e.g. `currentEntry`); if there is none, you will add one in Step 2.

- [ ] **Step 2: Add `rerenderCurrent()`**

Ensure `dispatch(...)` and `requestBuildAndRender(...)` record the entry they act on into a
`private var currentEntry: PreviewEntry? = null`. Then add, next to `select`:

```kotlin
    /**
     * Re-render whatever is currently displayed. Used after the picker edits the @Preview annotation: the
     * selection has not changed, so [select] would be a no-op, but the source has. Debounced and generation-
     * guarded like every other render path, so a burst of picker edits does not queue a render each.
     */
    fun rerenderCurrent() {
        val entry = currentEntry ?: return
        alarm.cancelAllRequests()
        alarm.addRequest({ render(entry, ++generation) }, DEBOUNCE_MS)
    }
```

Use the same `alarm`, `generation` and `DEBOUNCE_MS` the class already defines. If `render` is `private`, calling
it from here is fine — same class.

- [ ] **Step 3: Wire the picker's change signal**

In `PreviewGalleryPanel`, replace the stub body of `onPickerModification()`:

```kotlin
    private fun onPickerModification() {
        pipeline.rerenderCurrent()
    }
```

`onPickerModification` may be invoked off the EDT (it comes from `GalleryPickerTracker`). `rerenderCurrent` only
touches the `Alarm` (a `SWING_THREAD` alarm) and the generation counter; if the alarm requires the EDT, marshal
with `ApplicationManager.getApplication().invokeLater { pipeline.rerenderCurrent() }` — check how the class's
other alarm calls are made and match them. Remove the now-obsolete `thisLogger().info(...)` line and its import
if unused.

- [ ] **Step 4: Verify it compiles and the suite is green**

Run: `./gradlew compileKotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL, 101 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderPipeline.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "[PG4-1] - Re-render the current preview on a picker change"
```

---

### Task 2: Config-aware preview element (GATE)

**Goal:** Render each preview with the device/api/size/showSystemUi from its own `@Preview`, instead of the fixed
default. Settles spec unknown V1.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`

**Interfaces:**
- Consumes: `PreviewEntry` (`indexed.composableFqn`, `indexed.displayName`, `file`, `moduleName`).
- Produces: `RenderModelResolver` builds a config-aware element; the render path is otherwise unchanged.

- [ ] **Step 1: Study `AnnotationFilePreviewElementFinder`**

The finder is `com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder`, in the bundled
`com.android.tools.design` plugin (already a dependency). Use `javap -p` (and `javap -c` where the call shape is
unclear) against `~/Applications/Android Studio.app/Contents/plugins/*/lib/*.jar` to find:
- how to obtain the finder (an `object`? a singleton? an extension-point instance?),
- the method that returns a file's `ComposePreviewElement`s (likely `suspend` / coroutine — note how AS calls it),
- the element type it returns, and whether it is `XmlSerializable` (has `toPreviewXml()`) like the current
  `SingleComposePreviewElementInstance`.

Record the exact signatures in your report. These are internal; the probe (below) guards them at runtime.

- [ ] **Step 2: Build the config-aware element, with a fallback**

In `RenderModelResolver.buildPreviewElement(entry)` (currently returns a default-config
`SingleComposePreviewElementInstance`), first try the finder: get the file's elements, select the one whose
composable FQN matches `entry.indexed.composableFqn` and whose preview name matches `entry.indexed.displayName`,
and return it. If the finder yields no match, is unavailable, or throws, return the existing default-config
element unchanged. Guard the finder call against `Exception` and `LinkageError`.

Keep the whole thing inside the read action `resolve` already runs in. The returned element must still be
`XmlSerializable` so `LiveRenderer`'s `toPreviewXml()` path is untouched; if the finder returns a parametrized
template rather than a single instance, treat it as "no match" and fall back (spec R1).

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Extend the probe**

Add the finder class to `RenderApiProbe`'s reflective check (a new entry, or fold it into the render list), so
`isAvailable()`/a new accessor reflects whether the config-aware path can run. Match the existing probe style.

Run: `./gradlew compileKotlin && ./gradlew test` — Expected: BUILD SUCCESSFUL, 101 tests passing.

- [ ] **Step 4: runIde gate (needs the user)**

Do NOT commit before this passes. Report the steps:
1. `./gradlew runIde`, open a real Compose project, open **Compose Gallery**.
2. Select a preview declared `@Preview(showSystemUi = true)` → it must render **with the system UI chrome**, not
   as a bare component.
3. Select one with an explicit `device=` or `widthDp=/heightDp=` → it must render at that size/device.
4. Compare against a plain `@Preview` (still default) to confirm the difference is real.

If a config-annotated preview still renders at the default, capture the outcome and any `idea.log` error — that
is the V1 answer, and the controller decides the next move. Confirm a plain preview still renders (the fallback).

- [ ] **Step 5: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderModelResolver.kt src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt
git commit -m "[PG4-2] - Render each preview with its own @Preview configuration"
```

---

### Task 3: The view tree — `ViewInfo` to `PreviewViewNode` (GATE)

**Goal:** After a render, expose a plugin-owned tree of node bounds + source locations, so the panel can hit-test
it. Settles spec unknown V2.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt`

**Interfaces:**
- Produces:
  - `data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?)`
  - `data class PreviewViewNode(val bounds: java.awt.Rectangle, val sourceLocation: PreviewSourceLocation?, val children: List<PreviewViewNode>)`
  - `RenderOutcome.Success(val image: BufferedImage, val viewTree: List<PreviewViewNode>)` — `viewTree` empty when unavailable.

- [ ] **Step 1: Write the plugin-owned model**

`src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt` — no `com.android.tools.*`:

```kotlin
package com.devomer.previewgallery.model

import java.awt.Rectangle

/** Where a composable is declared, resolved from a rendered node's source key. */
data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?)

/**
 * One node of the rendered composable tree, in render-pixel space. Plugin-owned so the UI never touches
 * Android Studio's `ViewInfo`; [LiveRenderer] converts the AS tree into this.
 */
data class PreviewViewNode(
    val bounds: Rectangle,
    val sourceLocation: PreviewSourceLocation?,
    val children: List<PreviewViewNode>,
)
```

- [ ] **Step 2: Add `viewTree` to `Success`**

In `RenderOutcome.kt`, change `Success` to carry the tree, defaulting to empty so existing construction sites
keep compiling until updated:

```kotlin
    data class Success(val image: BufferedImage, val viewTree: List<PreviewViewNode> = emptyList()) : RenderOutcome
```

Run: `./gradlew compileKotlin` — Expected: BUILD SUCCESSFUL (the default keeps old call sites valid).

- [ ] **Step 3: Study the ViewInfo → source path**

Against the jars: `com.android.tools.rendering.RenderResult.getRootViews(): ImmutableList<ViewInfo>` gives the
raw tree; `com.android.tools.idea.compose.preview.ComposeViewInfoParserKt` (and `ComposeViewInfo`,
`SourceLocation`/`SourceLocationImpl`) turn it into source-located nodes. Use `javap -p`/`javap -c` to find the
top-level parse function, the `ComposeViewInfo` accessors for bounds and source location, and what
`SourceLocation` exposes (file name, line, offset). Record the signatures.

- [ ] **Step 4: Convert in `LiveRenderer`**

After the successful render (where `Success(image)` is currently built), read `result.getRootViews()`, parse to
`ComposeViewInfo` via the parser, and map recursively into `PreviewViewNode` (bounds from the node's
left/top/right/bottom as a `Rectangle`, `sourceLocation` from the node's source location if present). Wrap the
whole conversion in a guard: on any `Exception`/`LinkageError`, log once and use `emptyList()` — the render still
returns `Success(image, emptyList())`. Then return `Success(image, viewTree)`.

Run: `./gradlew compileKotlin && ./gradlew test` — Expected: BUILD SUCCESSFUL, 101 tests passing.

- [ ] **Step 5: Extend the probe**

Add the view-info parser class(es) to `RenderApiProbe` so a missing API is detected up front.

- [ ] **Step 6: runIde gate (needs the user)**

Do NOT commit before this passes. Since there is no UI yet, verify via a temporary log (removed before commit):
in `LiveRenderer`, after building the tree, `thisLogger().info("viewTree nodes=<count>, first source=<...>")`.
1. `./gradlew runIde`, open a Compose project, select a preview.
2. Confirm `idea.log` shows a non-zero node count and at least one node carrying a source location
   (file + line matching the composable).

If the count is zero or no source locations appear, capture it — that is the V2 answer. Remove the temporary log
before committing.

- [ ] **Step 7: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt src/main/kotlin/com/devomer/previewgallery/render/RenderApiProbe.kt
git commit -m "[PG4-3] - Expose the rendered view tree with source locations"
```

---

### Task 4: Hit-testing over the view tree

**Goal:** Pure logic to find the innermost node at a point, and to map between panel and render coordinates. No
AS API, no Swing painting — just geometry.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTester.kt`

**Interfaces:**
- Consumes: `PreviewViewNode`.
- Produces:
  - `PreviewViewHitTester.innermostAt(roots: List<PreviewViewNode>, point: Point): PreviewViewNode?`
  - `PreviewViewHitTester.imageDrawRect(panel: Dimension, image: Dimension): Rectangle` — where the scaled image
    is drawn (fit, centered), so callers can map panel↔render points.
  - `PreviewViewHitTester.toRenderPoint(panelPoint: Point, drawRect: Rectangle, image: Dimension): Point?` — null
    if the panel point is outside the drawn image.

- [ ] **Step 1: Write the geometry**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTester.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewViewNode
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Pure geometry for the interactive overlay: where the fitted image is drawn, how a panel point maps to a
 * render-pixel point, and which composable node is innermost at a render point. No Swing, no AS API — unit-tested.
 */
object PreviewViewHitTester {

    /** The rectangle the image occupies when scaled to fit [panel] preserving aspect ratio, centered. */
    fun imageDrawRect(panel: Dimension, image: Dimension): Rectangle {
        if (image.width <= 0 || image.height <= 0 || panel.width <= 0 || panel.height <= 0) return Rectangle()
        val scale = minOf(panel.width.toDouble() / image.width, panel.height.toDouble() / image.height)
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        val x = (panel.width - w) / 2
        val y = (panel.height - h) / 2
        return Rectangle(x, y, w, h)
    }

    /** Map a panel point to render-pixel space, or null if it falls outside the drawn image. */
    fun toRenderPoint(panelPoint: Point, drawRect: Rectangle, image: Dimension): Point? {
        if (!drawRect.contains(panelPoint) || drawRect.width == 0 || drawRect.height == 0) return null
        val fx = (panelPoint.x - drawRect.x).toDouble() / drawRect.width
        val fy = (panelPoint.y - drawRect.y).toDouble() / drawRect.height
        return Point((fx * image.width).toInt(), (fy * image.height).toInt())
    }

    /** The deepest node whose bounds contain [point]; null if none. Depth-first, smallest containing wins. */
    fun innermostAt(roots: List<PreviewViewNode>, point: Point): PreviewViewNode? {
        var best: PreviewViewNode? = null
        fun visit(node: PreviewViewNode) {
            if (!node.bounds.contains(point)) return
            best = node
            node.children.forEach { visit(it) }
        }
        roots.forEach { visit(it) }
        return best
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTester.kt
git commit -m "[PG4-4] - Hit-testing geometry for the interactive overlay"
```

---

### Task 5: Hover outline + click-to-source in the panel (GATE)

**Goal:** Hovering the rendered image outlines the innermost composable; clicking navigates to its source.
Settles spec unknowns V3 (source → navigation) and V4 (coordinate mapping, verified live).

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`

**Interfaces:**
- Consumes: `PreviewViewNode`, `PreviewViewHitTester`, `RenderOutcome.Success.viewTree`.
- Produces: `PreviewRenderPanel.onNavigateToSource: (PreviewSourceLocation) -> Unit` — the panel raises a
  navigation request; `PreviewGalleryPanel` resolves it to an editor open.

- [ ] **Step 1: Hold the view tree and draw the overlay**

In `PreviewRenderPanel`, when showing a `Success`, keep its `viewTree` and the drawn image's `Rectangle` (compute
it with `PreviewViewHitTester.imageDrawRect` when painting the image). Add a `@Volatile private var hovered:
PreviewViewNode?`. Override the image label's painting (or paint on a glass overlay component) to draw the
`hovered` node's bounds — mapped from render space back to panel space via the draw rect — as a 1px outline in a
selection colour (e.g. `JBUI.CurrentTheme...` or `UIUtil.getFocusedBorderColor()`), only when `hovered != null`.

- [ ] **Step 2: Hover and click listeners**

Add a `MouseMotionListener`: on move, `toRenderPoint(e.point, drawRect, imageDim)`; if non-null,
`hovered = PreviewViewHitTester.innermostAt(viewTree, renderPoint)`, else `hovered = null`; repaint. Throttle
repaints so mouse-move stays cheap (only repaint when `hovered` actually changes). Add a `MouseListener`: on
click, compute the innermost node the same way and, if it has a `sourceLocation`, call
`onNavigateToSource(location)`. Both listeners are inert when `viewTree` is empty (Feature B degraded).

- [ ] **Step 2b: Reset on non-Success**

When the panel shows anything other than `Success` (RENDERING/FAILED/UNSUPPORTED/IDLE/NEEDS_BUILD), clear
`viewTree`, `hovered`, and the draw rect so a stale overlay never paints over a non-image state.

- [ ] **Step 3: Resolve navigation in `PreviewGalleryPanel`**

Wire `renderPanel.onNavigateToSource = { location -> navigateToSource(location) }`. Implement `navigateToSource`:
resolve the file by `location.fileName` within the current entry's module/project (the current entry's own
`VirtualFile` is the common case — prefer it when its name matches; otherwise search the project scope by name),
then `OpenFileDescriptor(project, file, location.offset ?: 0).navigate(true)` under the platform's normal
navigation. If `offset` is null but `lineNumber` is set, use `OpenFileDescriptor(project, file, lineNumber, 0)`.
Guard against a missing file (no-op).

- [ ] **Step 4: Verify it compiles and the suite is green**

Run: `./gradlew compileKotlin && ./gradlew test`
Expected: BUILD SUCCESSFUL, 101 tests passing.

- [ ] **Step 5: runIde gate (needs the user)**

Do NOT commit before this passes.
1. `./gradlew runIde`, open a Compose project, select a preview that nests components (e.g. a Column of items).
2. Move the mouse over the image → the innermost composable under the cursor is outlined, and the outline follows
   the cursor into nested components.
3. Click a nested component → the editor opens its source at the right line.
4. Confirm hovering the empty margins outlines nothing and clicking there does nothing.

If the outline is offset from the cursor, that is the V4 coordinate answer; if the click lands on the wrong line,
that is V3. Capture either and report.

- [ ] **Step 6: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "[PG4-5] - Hover outline and click-to-source over the render"
```

---

### Task 6: Tests and manual verification

**Files:**
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTesterTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/render/RenderPipelineRerenderTest.kt`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: `PreviewViewHitTesterTest` (plain JUnit)**

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewViewNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

class PreviewViewHitTesterTest {

    private fun node(x: Int, y: Int, w: Int, h: Int, children: List<PreviewViewNode> = emptyList()) =
        PreviewViewNode(Rectangle(x, y, w, h), null, children)

    @Test fun `draw rect fits and centers`() {
        // 100x200 image into a 200x200 panel -> scale 1.0 by height, width 100, centered x=50
        assertEquals(Rectangle(50, 0, 100, 200), PreviewViewHitTester.imageDrawRect(Dimension(200, 200), Dimension(100, 200)))
    }

    @Test fun `panel point outside the image maps to null`() {
        val draw = Rectangle(50, 0, 100, 200)
        assertNull(PreviewViewHitTester.toRenderPoint(Point(10, 10), draw, Dimension(100, 200)))
    }

    @Test fun `panel point maps into render space`() {
        val draw = Rectangle(0, 0, 100, 200)   // 1:1
        assertEquals(Point(50, 100), PreviewViewHitTester.toRenderPoint(Point(50, 100), draw, Dimension(100, 200)))
    }

    @Test fun `innermost node wins over its parent`() {
        val child = node(10, 10, 20, 20)
        val parent = node(0, 0, 100, 100, listOf(child))
        assertEquals(child, PreviewViewHitTester.innermostAt(listOf(parent), Point(15, 15)))
        assertEquals(parent, PreviewViewHitTester.innermostAt(listOf(parent), Point(5, 5)))
    }

    @Test fun `no node contains the point`() {
        assertNull(PreviewViewHitTester.innermostAt(listOf(node(0, 0, 10, 10)), Point(50, 50)))
    }
}
```

- [ ] **Step 2: `RenderPipelineRerenderTest` (plain JUnit, fake renderer)**

Model it on the existing pipeline tests. Assert: `rerenderCurrent()` with nothing selected attempts no render;
after a selection renders, `rerenderCurrent()` renders the same entry again; a result from a superseded
generation is ignored. If the pipeline's constructor needs a fake `LiveRenderer`/`BuildService`, reuse whatever
fakes the existing `RenderPipeline` tests already use; if none exist, build minimal ones in the test file. Show
the actual test code you wrote in the report.

- [ ] **Step 3: Run the suite**

Run: `./gradlew test`
Expected: PASS — 101 existing plus the new tests, no skips.

- [ ] **Step 4: Manual verification (AC1–AC6, needs the user)**

In `runIde` against a real Compose project:
- AC1 a `@Preview(showSystemUi=true)` renders with system UI; a `device=` renders at that device
- AC2 changing the device in the picker refreshes the render without reselecting
- AC3 hover outlines the innermost composable
- AC4 click navigates to its source line
- AC5 (if feasible) with a probe class name broken, previews still render at default and hover/click are inert
- AC6 `./gradlew test` green

- [ ] **Step 5: Changelog and commit**

Add an "Added — config-aware render, live picker refresh, hover-to-source" entry to `CHANGELOG.md`, then:

```bash
git add src/test/kotlin/com/devomer/previewgallery CHANGELOG.md
git commit -m "[PG4-6] - Tests and changelog for config-aware render and interactive layers"
```
