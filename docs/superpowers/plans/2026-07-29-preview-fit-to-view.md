# Fit-to-View First Render Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first render of a preview shows the whole composable inside the render pane, at the size Android Studio's own preview shows it.

**Architecture:** Three independent defects, fixed in dependency order. `ZoomMath` gains the device-pixel→dp conversion and loses the zoom-ladder floor as a clamp. `RenderOutcome.Success` carries the density layoutlib rendered at. `ZoomableRenderView` multiplies its zoom factor by that dp conversion for every on-screen dimension, and holds a `pendingFit` flag so a fit requested before the viewport is laid out is honoured once it is.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform Gradle plugin, platform 253 (Android Studio Panda 4, local install at `/Users/odurmaz/Applications/Android Studio.app`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` for Swing tests.

**Design spec:** [docs/superpowers/specs/2026-07-29-preview-fit-to-view-design.md](../specs/2026-07-29-preview-fit-to-view-design.md)

## Global Constraints

- **Commit prefix `[PG12-N]`.** Message pattern: `[PG12-1] - Task name`. Every commit ends with the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.
- **Never use Kotlin's `!!` operator.**
- **Never run `./gradlew` while a `runIde` sandbox is live.** Before every Gradle invocation, check liveness with exactly these two patterns and kill only what they match:
  ```bash
  pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
  ```
  A hit means a sandbox is up: `kill -9` those PIDs and wait for them to disappear before building. Never kill a process without the `sandbox.mode` marker — that is the user's own Android Studio.
- **Every `com.android.tools.*` call is guarded** against `Exception` and `LinkageError` and degrades to prior behaviour; nothing new may throw out of `LiveRenderer.render` except `ProcessCanceledException`.
- **Docs, comments, KDoc and commit messages are written in English.**
- If Gradle fails with `instrumentIdeaExtensions doesn't support the nested "skip" element`, the config cache is corrupt: re-run with `--no-configuration-cache`, or `rm -rf .gradle/configuration-cache`.

## File Structure

| File | Create/Modify | Responsibility after this plan |
|---|---|---|
| `src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt` | Modify | All zoom and scale arithmetic: step ladder, hard zoom bounds, device-pixel→dp conversion, fit. Pure — no Swing, no AS API. |
| `src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt` | Modify | Unit tests for the above. |
| `src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt` | Modify | Carries the render's density to the display layer. |
| `src/test/kotlin/com/devomer/previewgallery/model/RenderOutcomeTest.kt` | Create | Pins the safe default density. |
| `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt` | Modify | Reads the density layoutlib rendered at, guarded, with two fallbacks. |
| `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt` | Modify | Draws, sizes and hit-tests in dp space; owes each new render a fit until it can honour one. |
| `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt` | Create | Swing tests for dp sizing and the deferred-fit state machine. |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt` | Modify | Passes the render's density to the Original view and to each comparison tab. |
| `compose-preview-gallery-plugin-spec.md` | Modify | Records the render-pane zoom contract. |

---

### Task 1: `ZoomMath` — dp conversion and unbounded fit

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `const val ZoomMath.MIN: Double` = `0.05`
  - `const val ZoomMath.MAX: Double` = `4.0`
  - `fun ZoomMath.contentScale(dpi: Int): Double`
  - `fun ZoomMath.dpSize(imagePx: java.awt.Dimension, dpi: Int): java.awt.Dimension`
  - `fun ZoomMath.fitFactor(viewport: java.awt.Dimension, content: java.awt.Dimension): Double` — signature unchanged, but the second argument is now the content size **in dp**, and the result may exceed `1.0`.
  - `LADDER`, `stepIn`, `stepOut`, `anchorScroll` — unchanged.

- [ ] **Step 1: Write the failing tests**

Replace the whole of `src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt` with:

```kotlin
package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Point

class ZoomMathTest {

    @Test fun `stepIn goes to the next ladder level and clamps at max`() {
        assertEquals(1.5, ZoomMath.stepIn(1.0), 1e-9)
        assertEquals(0.5, ZoomMath.stepIn(0.25), 1e-9)
        assertEquals(4.0, ZoomMath.stepIn(4.0), 1e-9)      // already max
        assertEquals(0.75, ZoomMath.stepIn(0.63), 1e-9)    // off-ladder -> next up
    }

    @Test fun `stepOut goes to the previous ladder level and clamps at min`() {
        assertEquals(0.75, ZoomMath.stepOut(1.0), 1e-9)
        assertEquals(0.25, ZoomMath.stepOut(0.25), 1e-9)   // already the lowest ladder stop
        assertEquals(0.5, ZoomMath.stepOut(0.63), 1e-9)    // off-ladder -> next down
    }

    @Test fun `contentScale converts render pixels to dp`() {
        assertEquals(0.363636, ZoomMath.contentScale(440), 1e-6) // a Pixel-class phone
        assertEquals(0.5, ZoomMath.contentScale(320), 1e-9)
        assertEquals(1.0, ZoomMath.contentScale(160), 1e-9)      // mdpi -> identity
    }

    @Test fun `contentScale is the identity for a non-positive dpi`() {
        assertEquals(1.0, ZoomMath.contentScale(0), 1e-9)
        assertEquals(1.0, ZoomMath.contentScale(-1), 1e-9)
    }

    @Test fun `dpSize converts a render image size to dp`() {
        assertEquals(Dimension(393, 851), ZoomMath.dpSize(Dimension(1080, 2340), 440))
        assertEquals(Dimension(300, 400), ZoomMath.dpSize(Dimension(300, 400), 160))
        assertEquals(Dimension(0, 0), ZoomMath.dpSize(Dimension(0, 0), 440))
    }

    @Test fun `fitFactor shrinks content larger than the viewport`() {
        assertEquals(0.5, ZoomMath.fitFactor(Dimension(200, 200), Dimension(400, 400)), 1e-9)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 200)), 1e-9) // min(2,1)
    }

    @Test fun `fitFactor upscales content smaller than the viewport`() {
        assertEquals(2.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 100)), 1e-9)
    }

    @Test fun `fitFactor may go below the zoom ladder floor`() {
        // A 1280x2000 dp content in a 300x200 pane needs 10%; the ladder's lowest stop is 25%.
        val factor = ZoomMath.fitFactor(Dimension(300, 200), Dimension(1280, 2000))
        assertEquals(0.1, factor, 1e-9)
        assertTrue("expected $factor below the ladder floor", factor < ZoomMath.LADDER.first())
    }

    @Test fun `fitFactor clamps to the hard bounds`() {
        assertEquals(ZoomMath.MAX, ZoomMath.fitFactor(Dimension(10_000, 10_000), Dimension(10, 10)), 1e-9)
        assertEquals(ZoomMath.MIN, ZoomMath.fitFactor(Dimension(10, 10), Dimension(100_000, 100_000)), 1e-9)
    }

    @Test fun `fitFactor returns 1 for a degenerate viewport or content`() {
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(0, 0), Dimension(50, 50)), 1e-9)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(50, 50), Dimension(0, 0)), 1e-9)
    }

    @Test fun `anchorScroll keeps the point under the cursor stationary`() {
        // cursor at (100,100), scroll (0,0), zoom 1x -> 2x: the render point (100,100) must stay under the cursor.
        assertEquals(Point(100, 100), ZoomMath.anchorScroll(Point(100, 100), 1.0, 2.0, Point(0, 0)))
        // zooming out never yields a negative offset.
        assertEquals(Point(0, 0), ZoomMath.anchorScroll(Point(10, 10), 1.0, 0.5, Point(0, 0)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output (no sandbox running). If there is output, `kill -9` those PIDs first. Then:

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomMathTest"
```

Expected: FAIL — compilation errors, `Unresolved reference: contentScale`, `Unresolved reference: dpSize`, `Unresolved reference: MIN`, `Unresolved reference: MAX`.

- [ ] **Step 3: Write the implementation**

Replace the whole of `src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt` with:

```kotlin
package com.devomer.previewgallery.ui

import java.awt.Dimension
import java.awt.Point
import kotlin.math.roundToInt

/**
 * Pure zoom math for the render view: a discrete ladder of zoom *stops*, the hard bounds the zoom factor lives
 * in, the device-pixel -> dp conversion that makes the on-screen size match Android Studio's own preview, the
 * fit-to-viewport factor, and the scroll adjustment that keeps the point under the cursor stationary across a
 * zoom change. No Swing, no AS API — unit-tested.
 *
 * ## Units (PG12-1)
 *
 * layoutlib renders at the *device's* pixel density: a 393x851 dp phone at 440 dpi comes back as a 1080x2340
 * image. Android Studio's design surface draws that at dp size — at 100% zoom one dp is one logical screen pixel
 * — so the gallery must do the same or "100%" means two different things in the two tools. [contentScale] is
 * that conversion, and the view multiplies it into every on-screen dimension. [fitFactor] therefore takes the
 * content size in **dp**, not in render pixels, so its result is a zoom percentage on the same scale as the
 * toolbar's own 100%.
 */
object ZoomMath {

    /** Discrete zoom levels as fractions: 25% .. 400%. These are the step *stops* only — [MIN]/[MAX] are the
     *  bounds. Fit is free to land between them, or below the lowest one. */
    val LADDER: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)

    /**
     * Hard bounds on the zoom factor. [MIN] is deliberately far below `LADDER.first()`: clamping fit to the
     * ladder's 25% floor is what used to make a tall device render overflow a short pane and grow scrollbars on
     * the very first render (PG12). It is a sanity floor against a degenerate zero, not a UX limit.
     */
    const val MIN: Double = 0.05
    const val MAX: Double = 4.0

    /** Android's baseline density (`DisplayMetrics.DENSITY_DEFAULT`): the dpi at which 1 px is 1 dp. */
    private const val DEFAULT_DPI: Int = 160

    private const val EPS = 1e-6

    /** The smallest ladder level strictly greater than [current]; the maximum if there is none. */
    fun stepIn(current: Double): Double = LADDER.firstOrNull { it > current + EPS } ?: LADDER.last()

    /** The largest ladder level strictly less than [current]; the minimum if there is none. */
    fun stepOut(current: Double): Double = LADDER.lastOrNull { it < current - EPS } ?: LADDER.first()

    /**
     * dp per render pixel for a render made at [dpi]. A non-positive [dpi] — the last-resort fallback when the
     * render's density could not be read — yields 1.0, i.e. raw render pixels, which is exactly the pre-PG12
     * behaviour. Degrading, never guessing.
     */
    fun contentScale(dpi: Int): Double = if (dpi <= 0) 1.0 else DEFAULT_DPI.toDouble() / dpi

    /** [imagePx] expressed in dp for a render made at [dpi], rounded to the nearest whole dp. */
    fun dpSize(imagePx: Dimension, dpi: Int): Dimension {
        val scale = contentScale(dpi)
        return Dimension(
            (imagePx.width * scale).roundToInt().coerceAtLeast(0),
            (imagePx.height * scale).roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * The zoom factor that fits the whole [content] — sized in **dp**, see [dpSize] — inside [viewport], bounded
     * by [MIN]/[MAX]. Content smaller than the viewport is upscaled to fill it, the way Android Studio's own
     * zoom-to-fit does; a 48 dp icon at 100% in a wide pane is a speck. A degenerate viewport or content (the
     * first render, before the scroll pane has been laid out) yields 1.0 and is expected to be retried — see
     * `ZoomableRenderView.retryFitIfPending`.
     */
    fun fitFactor(viewport: Dimension, content: Dimension): Double {
        if (content.width <= 0 || content.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1.0
        val fit = minOf(viewport.width.toDouble() / content.width, viewport.height.toDouble() / content.height)
        return fit.coerceIn(MIN, MAX)
    }

    /**
     * New scroll offset (viewport top-left in zoomed-image pixels) so the image point currently under the cursor
     * stays under it after zooming [oldFactor] -> [newFactor]. [cursorInView] is the cursor within the viewport;
     * [oldScroll] is the current top-left. Never returns a negative offset.
     */
    fun anchorScroll(cursorInView: Point, oldFactor: Double, newFactor: Double, oldScroll: Point): Point {
        val imageX = (oldScroll.x + cursorInView.x) / oldFactor
        val imageY = (oldScroll.y + cursorInView.y) / oldFactor
        val newX = (imageX * newFactor - cursorInView.x).toInt().coerceAtLeast(0)
        val newY = (imageY * newFactor - cursorInView.y).toInt().coerceAtLeast(0)
        return Point(newX, newY)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomMathTest"
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt && git commit -m "$(printf '%s\n' '[PG12-1] - Convert render pixels to dp and unbound the fit factor' '' 'Co-Authored-By: Claude <noreply@anthropic.com>')"
```

---

### Task 2: Carry the render density out of `LiveRenderer`

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt:230-248` (`verifySomethingWasDrawn`), `:176-180` (its call site), `:367-382` (the companion)
- Test: `src/test/kotlin/com/devomer/previewgallery/model/RenderOutcomeTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `RenderOutcome.Success(image: BufferedImage, viewTree: List<PreviewViewNode> = emptyList(), dpi: Int = RenderOutcome.DEFAULT_DPI)` — a third property `dpi: Int`, and `RenderOutcome.DEFAULT_DPI: Int = 160`. Task 3 reads `success.dpi` / `outcome.dpi`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/model/RenderOutcomeTest.kt`:

```kotlin
package com.devomer.previewgallery.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage

/**
 * [RenderOutcome.Success.dpi] is the density layoutlib rendered at (PG12-2). `LiveRenderer` reads it through two
 * guarded Android Studio calls, so the *default* is the contract that matters here: it must be the density at
 * which the dp conversion is the identity, so a guard failure degrades to raw render pixels — the pre-PG12
 * display — instead of scaling the image by a wrong factor.
 */
class RenderOutcomeTest {

    @Test fun `a Success defaults to the identity density`() {
        val outcome = RenderOutcome.Success(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))
        assertEquals(160, RenderOutcome.DEFAULT_DPI)
        assertEquals(RenderOutcome.DEFAULT_DPI, outcome.dpi)
    }

    @Test fun `a Success carries the density it was given`() {
        val outcome = RenderOutcome.Success(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), emptyList(), 440)
        assertEquals(440, outcome.dpi)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output. Then:

```bash
./gradlew test --tests "com.devomer.previewgallery.model.RenderOutcomeTest"
```

Expected: FAIL — `Unresolved reference: DEFAULT_DPI`, `Unresolved reference: dpi`.

- [ ] **Step 3: Add the property to the model**

Replace the whole of `src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt` with:

```kotlin
package com.devomer.previewgallery.model

import java.awt.image.BufferedImage

/**
 * The result of a single [com.devomer.previewgallery.render.LiveRenderer] render.
 *
 * - [Success] carries a standalone [BufferedImage] copied out of layoutlib's image pool, safe to hold on the EDT,
 *   plus the plugin-owned [PreviewViewNode] tree for the render (PG4-3) — empty when AS's view-info parser is
 *   unavailable or the conversion failed; the image is never lost to a tree-conversion failure — plus the
 *   density layoutlib rendered at (PG12-2), which the display layer needs to show the preview at dp size.
 * - [Failure] is a render that was attempted but did not produce an image (build missing, layoutlib error, timeout).
 * - [Unsupported] is a preview the renderer will not attempt (no Android facet, renderer API absent, etc.).
 */
sealed interface RenderOutcome {

    /**
     * @param dpi the density the image was rendered at. Defaults to [DEFAULT_DPI], at which the dp conversion is
     *   the identity — so a renderer that cannot read the real density degrades to the pre-PG12 raw-pixel
     *   display rather than to a wrongly scaled one.
     */
    data class Success(
        val image: BufferedImage,
        val viewTree: List<PreviewViewNode> = emptyList(),
        val dpi: Int = DEFAULT_DPI,
    ) : RenderOutcome

    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome

    companion object {
        /** Android's baseline density (`DisplayMetrics.DENSITY_DEFAULT`): the dpi at which 1 px is 1 dp. */
        const val DEFAULT_DPI: Int = 160
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests "com.devomer.previewgallery.model.RenderOutcomeTest"
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Read the real density in `LiveRenderer`**

In `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt`, change the call site — currently lines 177-180 inside `renderResolved`:

```kotlin
            var image: BufferedImage? = null
            result.processImageIfNotDisposed { pooled -> image = pooled.getCopy() }
            val copied = image
                ?: return failure("Render produced no image", result)

            return verifySomethingWasDrawn(copied, result)
```

to:

```kotlin
            var image: BufferedImage? = null
            result.processImageIfNotDisposed { pooled -> image = pooled.getCopy() }
            val copied = image
                ?: return failure("Render produced no image", result)

            return verifySomethingWasDrawn(copied, result, renderDpi(task, model))
```

Change the signature and the final line of `verifySomethingWasDrawn` (currently line 230 and line 247):

```kotlin
    private fun verifySomethingWasDrawn(image: BufferedImage, result: RenderResult, dpi: Int): RenderOutcome {
```

```kotlin
        val viewTree = buildViewTree(result)
        return RenderOutcome.Success(image, viewTree, dpi)
    }
```

Add this new private method immediately after `verifySomethingWasDrawn`:

```kotlin
    /**
     * The density layoutlib actually rendered at, so the UI can draw the preview at dp size the way Android
     * Studio's own design surface does (PG12-2).
     *
     * The primary source is the task's own `HardwareConfig`: `RenderTask`'s constructor builds it as
     * `new HardwareConfigHelper(device)` (verified on `RenderTask` bytecode in
     * `plugins/android/lib/android.jar`), so this is the value the render used, not a re-derivation from the
     * device. `getHardwareConfigHelper()`, `HardwareConfigHelper.getConfig()` and `HardwareConfig.getDensity()`
     * are all public API on that build.
     *
     * The first fallback is the `Configuration`'s own density qualifier, which `Configuration.getDensity()`
     * reads off the same device state through `FolderConfiguration` (and which itself falls back to
     * `Density.MEDIUM`). The last resort is [RenderOutcome.DEFAULT_DPI], at which the dp conversion is the
     * identity — a guard failure therefore degrades to the pre-PG12 raw-pixel display, never to a wrong size.
     *
     * Guarded with `runCatching` like the other optional AS reads in this file (see the `showDecoration` read in
     * [RenderModelResolver]): both calls are plain in-memory field getters that cannot raise
     * [ProcessCanceledException], so there is nothing here that must be allowed to propagate.
     */
    private fun renderDpi(task: RenderTask, model: RenderModelResolver.Resolved): Int {
        val fromTask = runCatching { task.hardwareConfigHelper.config.density.dpiValue }.getOrNull()
        if (fromTask != null && fromTask > 0) return fromTask
        val fromConfiguration = runCatching { model.configuration.density.dpiValue }.getOrNull()
        if (fromConfiguration != null && fromConfiguration > 0) return fromConfiguration
        thisLogger().info("Render density unavailable; the preview will display at raw render pixels")
        return RenderOutcome.DEFAULT_DPI
    }
```

- [ ] **Step 6: Compile and run the full test suite**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output. Then:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. `LiveRenderer` itself has no unit test — it drives layoutlib and has no seam to substitute (see the `RenderPipelineTest` class doc for the established position on this). Compilation is the check here; the density is verified at the Task 5 `runIde` gate.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/RenderOutcome.kt src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt src/test/kotlin/com/devomer/previewgallery/model/RenderOutcomeTest.kt && git commit -m "$(printf '%s\n' '[PG12-2] - Carry the density layoutlib rendered at out of the renderer' '' 'Co-Authored-By: Claude <noreply@anthropic.com>')"
```

---

### Task 3: Draw, size and hit-test in dp space

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt:345`, `:525`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt` (create)

**Interfaces:**
- Consumes: `ZoomMath.MIN`, `ZoomMath.MAX`, `ZoomMath.contentScale(dpi)`, `ZoomMath.dpSize(imagePx, dpi)`, `ZoomMath.fitFactor(viewport, contentDp)` (Task 1); `RenderOutcome.Success.dpi` (Task 2).
- Produces: `ZoomableRenderView.setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int)` — a **required** third parameter, no default; every call site is updated in this task. `zoomFactor` keeps its name and type and now means the Android-Studio-equivalent zoom percentage.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.image.BufferedImage

/**
 * [ZoomableRenderView] is Swing, so these run on the platform fixture's EDT. They never make a window visible —
 * every assertion is about geometry (`preferredSize`, `zoomFactor`), which `setSize` + `doLayout` establish
 * without a display. A [JBScrollPane] is what supplies the `JViewport` ancestor the view looks for.
 */
class ZoomableRenderViewTest : BasePlatformTestCase() {

    /** A Pixel-class render: 393x851 dp at 440 dpi comes back from layoutlib as 1080x2340 device pixels. */
    private fun pixelClassRender(): BufferedImage = BufferedImage(1080, 2340, BufferedImage.TYPE_INT_ARGB)

    private fun sizedScroll(view: ZoomableRenderView, width: Int, height: Int): JBScrollPane =
        JBScrollPane(view).apply {
            setSize(width, height)
            doLayout()
        }

    fun `test the preferred size at 100 percent is the dp size, not the render pixel size`() {
        val view = ZoomableRenderView()
        view.setContent(pixelClassRender(), emptyList(), 440)
        view.zoomFactor = 1.0
        assertEquals(393, view.preferredSize.width)
        assertEquals(851, view.preferredSize.height)
    }

    fun `test a render at the identity density still displays at its pixel size`() {
        val view = ZoomableRenderView()
        view.setContent(BufferedImage(300, 400, BufferedImage.TYPE_INT_ARGB), emptyList(), 160)
        view.zoomFactor = 1.0
        assertEquals(300, view.preferredSize.width)
        assertEquals(400, view.preferredSize.height)
    }

    fun `test a device-pixel render fits entirely inside a short pane`() {
        val view = ZoomableRenderView()
        val scroll = sizedScroll(view, 400, 560)
        view.setContent(pixelClassRender(), emptyList(), 440)
        val extent = scroll.viewport.extentSize
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
        assertTrue("expected a shrink, got ${view.zoomFactor}", view.zoomFactor < 1.0)
    }

    fun `test the fit is not clamped up to the zoom ladder floor`() {
        val view = ZoomableRenderView()
        // 1080x2340 dp in a ~240x240 pane needs ~10%: below the ladder's 25% floor, comfortably above
        // ZoomMath.MIN, so the assertion below tests the ladder clamp and not the hard bound.
        val scroll = sizedScroll(view, 240, 240)
        view.setContent(pixelClassRender(), emptyList(), 160) // identity density: dp size == pixel size
        val extent = scroll.viewport.extentSize
        assertTrue("expected below ${ZoomMath.LADDER.first()}, got ${view.zoomFactor}",
            view.zoomFactor < ZoomMath.LADDER.first())
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
    }

    fun `test the zoom factor is bounded by ZoomMath rather than by the ladder`() {
        val view = ZoomableRenderView()
        view.zoomFactor = 0.01
        assertEquals(ZoomMath.MIN, view.zoomFactor, 1e-9)
        view.zoomFactor = 99.0
        assertEquals(ZoomMath.MAX, view.zoomFactor, 1e-9)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output. Then:

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomableRenderViewTest"
```

Expected: FAIL — compilation error, `No value passed for parameter 'dpi'` on every `setContent` call.

- [ ] **Step 3: Give the view a dp content scale**

In `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`:

Add to the imports:

```kotlin
import kotlin.math.roundToInt
```

Replace the `zoomFactor` property (currently lines 45-50) and add the two content fields plus the derived scale immediately after it:

```kotlin
    /**
     * The zoom percentage the user sees — and the same percentage Android Studio's own preview means by it: at
     * 1.0 the composable is drawn at dp size, not at the device's pixel size (PG12-3). Bounded by
     * [ZoomMath.MIN]/[ZoomMath.MAX], NOT by [ZoomMath.LADDER] — the ladder is only where the step buttons stop,
     * and clamping fit to its 25% floor is what used to make a tall render overflow a short pane.
     */
    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(ZoomMath.MIN, ZoomMath.MAX)
            revalidate() // preferredSize changed -> scroll pane updates scrollbars
            repaint()
        }

    /** dp per render pixel for the current image (see [ZoomMath.contentScale]); 1.0 until content arrives. */
    private var contentScale: Double = 1.0

    /** The current image's size in dp — what [fitToViewport] fits, so its result is a zoom percentage. */
    private var contentDp: Dimension = Dimension(0, 0)

    /**
     * The factor every on-screen dimension is expressed in: the user's zoom percentage times the render's own
     * pixel-to-dp conversion. Deriving it once is what keeps [getPreferredSize], the drawn image, the hover
     * outline and [renderPointOf] from disagreeing.
     */
    private val displayScale: Double get() = zoomFactor * contentScale
```

Replace `setContent` and `clearContent` (currently lines 80-93):

```kotlin
    /**
     * A new render's image + view tree, plus the density it was rendered at ([RenderOutcome.Success.dpi]); resets
     * zoom to [fitToViewport] and clears any prior hover.
     */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        this.contentScale = ZoomMath.contentScale(dpi)
        this.contentDp = ZoomMath.dpSize(Dimension(image.width, image.height), dpi)
        fitToViewport()
    }

    fun clearContent() {
        image = null
        viewTree = emptyList()
        hovered = null
        contentScale = 1.0
        contentDp = Dimension(0, 0)
        revalidate(); repaint()
    }
```

Replace `fitToViewport` and `getPreferredSize` (currently lines 97-106):

```kotlin
    fun fitToViewport() {
        if (image == null) return
        val vp = enclosingViewport()?.extentSize ?: size
        zoomFactor = ZoomMath.fitFactor(vp, contentDp)
    }

    override fun getPreferredSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        val scale = displayScale
        return Dimension(
            (img.width * scale).roundToInt().coerceAtLeast(1),
            (img.height * scale).roundToInt().coerceAtLeast(1),
        )
    }
```

Replace `paintComponent` (currently lines 108-128):

```kotlin
    override fun paintComponent(g: Graphics) {
        val img = image ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            // The layoutlib image is at device pixel density and is usually drawn well under 1:1 (a 1080 px wide
            // render inside a ~400 px pane), so ask for the quality path on top of bilinear filtering.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val scale = displayScale
            g2.drawImage(img, 0, 0, (img.width * scale).roundToInt(), (img.height * scale).roundToInt(), null)
            val node = hovered
            if (!handToolActive && node != null) {
                val b = node.bounds
                g2.color = HOVER_OUTLINE
                g2.drawRect(
                    (b.x * scale).roundToInt(), (b.y * scale).roundToInt(),
                    (b.width * scale).roundToInt().coerceAtLeast(0),
                    (b.height * scale).roundToInt().coerceAtLeast(0),
                )
            }
        } finally {
            g2.dispose()
        }
    }
```

Replace `renderPointOf` (currently lines 130-133):

```kotlin
    private fun renderPointOf(p: Point): Point? {
        val scale = displayScale
        if (image == null || scale <= 0.0) return null
        return Point((p.x / scale).toInt(), (p.y / scale).toInt())
    }
```

- [ ] **Step 4: Update both call sites in `PreviewRenderPanel`**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`, line 345:

```kotlin
        renderView.setContent(image, success.viewTree)
```

becomes:

```kotlin
        renderView.setContent(image, success.viewTree, success.dpi)
```

and line 525, inside `renderInto`:

```kotlin
                    extraView.setContent(outcome.image, outcome.viewTree)
```

becomes:

```kotlin
                    // PG12-3: per view, not once for the panel — a comparison tab may render a different device,
                    // and therefore a different density, than Original.
                    extraView.setContent(outcome.image, outcome.viewTree, outcome.dpi)
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomableRenderViewTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt && git commit -m "$(printf '%s\n' '[PG12-3] - Draw the preview at dp size, the way Android Studio does' '' 'Co-Authored-By: Claude <noreply@anthropic.com>')"
```

---

### Task 4: Honour the fit once the viewport has a size

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt`

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: `internal val ZoomableRenderView.isFitPending: Boolean` and `internal fun ZoomableRenderView.retryFitIfPending()`. Both exist so the state machine is testable without pumping AWT `ComponentEvent`s; the installed listener does nothing but call `retryFitIfPending`.

- [ ] **Step 1: Write the failing tests**

Append these three tests to `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt`, inside the class:

```kotlin
    fun `test a fit requested before layout is deferred, not applied at full size`() {
        val view = ZoomableRenderView()
        JBScrollPane(view) // never sized: the viewport reports a 0x0 extent
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue("the fit should still be owed", view.isFitPending)
        assertEquals(1.0, view.zoomFactor, 1e-9)
    }

    fun `test the deferred fit lands once the viewport has a size`() {
        val view = ZoomableRenderView()
        val scroll = JBScrollPane(view)
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue(view.isFitPending)

        scroll.setSize(400, 560)
        scroll.doLayout()
        view.retryFitIfPending() // exactly what the installed ComponentListener does on resize

        assertFalse("the fit should have been honoured", view.isFitPending)
        val extent = scroll.viewport.extentSize
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
    }

    fun `test a manual zoom cancels the pending fit and survives a later resize`() {
        val view = ZoomableRenderView()
        val scroll = JBScrollPane(view)
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue(view.isFitPending)

        view.zoomFactor = 2.0
        assertFalse("a deliberate zoom retires the debt", view.isFitPending)

        scroll.setSize(400, 560)
        scroll.doLayout()
        view.retryFitIfPending()

        assertEquals(2.0, view.zoomFactor, 1e-9)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output. Then:

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomableRenderViewTest"
```

Expected: FAIL — `Unresolved reference: isFitPending`, `Unresolved reference: retryFitIfPending`.

- [ ] **Step 3: Add the pending-fit state machine**

In `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`:

Add to the imports:

```kotlin
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
```

In the `zoomFactor` setter, clear the debt — the property becomes:

```kotlin
    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(ZoomMath.MIN, ZoomMath.MAX)
            // Any assignment settles what this view owes the current render: either it IS the fit (from
            // fitToViewport), or the user picked a zoom themselves and a later resize must not overwrite it.
            pendingFit = false
            revalidate() // preferredSize changed -> scroll pane updates scrollbars
            repaint()
        }
```

Add the flag, the retry, and the listener after the `contentDp` field:

```kotlin
    /**
     * Whether the current image still owes this view a fit (PG12-4).
     *
     * On the first render after the tool window opens, `add()` does not lay the scroll pane out synchronously, so
     * the enclosing viewport still reports a 0x0 extent and [fitToViewport] has nothing to fit against — the
     * preview would show at 100%, overflowing the pane, which is exactly the bug this phase fixes. Rather than
     * retry on a timer (which spins forever on a component that is never shown) or re-fit on every resize (which
     * would throw away a zoom the user chose), the debt is recorded here and settled by the first resize that
     * gives the viewport a real size — or by the user zooming, via the [zoomFactor] setter.
     */
    private var pendingFit: Boolean = false

    /** Exposed for `ZoomableRenderViewTest`: the state machine above, without pumping AWT ComponentEvents. */
    internal val isFitPending: Boolean get() = pendingFit

    /** The whole body of [fitListener]. Internal so the test can drive it directly. */
    internal fun retryFitIfPending() {
        if (pendingFit) fitToViewport()
    }

    private val fitListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) = retryFitIfPending()
    }
```

Make `setContent` record the debt and `clearContent` drop it:

```kotlin
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        this.contentScale = ZoomMath.contentScale(dpi)
        this.contentDp = ZoomMath.dpSize(Dimension(image.width, image.height), dpi)
        this.pendingFit = true
        fitToViewport()
    }

    fun clearContent() {
        image = null
        viewTree = emptyList()
        hovered = null
        contentScale = 1.0
        contentDp = Dimension(0, 0)
        pendingFit = false
        revalidate(); repaint()
    }
```

Make `fitToViewport` refuse to fit against a viewport that has no size yet:

```kotlin
    fun fitToViewport() {
        if (image == null) return
        val vp = enclosingViewport()?.extentSize ?: size
        // Not laid out yet: leave pendingFit standing so the resize retry picks this up. Assigning a factor here
        // would settle the debt with a meaningless 1.0.
        if (vp.width <= 0 || vp.height <= 0) return
        zoomFactor = ZoomMath.fitFactor(vp, contentDp)
    }
```

Install and remove the listener alongside the view's own attachment, immediately after `fitListener`:

```kotlin
    /**
     * The listener goes on the *viewport*, not on this component: this component's own size is its zoomed extent,
     * which changes for reasons that have nothing to do with the pane growing. Swing pairs
     * [addNotify]/[removeNotify], so the listener is installed exactly once per attachment — including when
     * `PreviewRenderPanel` reparents a comparison view through `JBScrollPane.setViewportView`.
     */
    override fun addNotify() {
        super.addNotify()
        enclosingViewport()?.addComponentListener(fitListener)
    }

    override fun removeNotify() {
        enclosingViewport()?.removeComponentListener(fitListener) // before super: the ancestor is still reachable
        super.removeNotify()
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.ZoomableRenderViewTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt && git commit -m "$(printf '%s\n' '[PG12-4] - Honour the first render fit once the viewport has a size' '' 'Co-Authored-By: Claude <noreply@anthropic.com>')"
```

---

### Task 5: Record the zoom contract in the spec, and gate on `runIde`

**Files:**
- Modify: `compose-preview-gallery-plugin-spec.md` (insert a new `### 5.4` after the `### 5.3 Render panel states` table, which ends at line 121 with the `UNSUPPORTED` row)

**Interfaces:**
- Consumes: the behaviour built in Tasks 1-4.
- Produces: nothing consumed by code.

- [ ] **Step 1: Add the spec section**

Insert immediately after the `### 5.3 Render panel states` table and before the `---` that precedes `## 6. Architecture`:

```markdown
### 5.4 Render panel zoom (PG12)

| Rule | Behaviour |
|---|---|
| Unit | The preview is drawn at **dp**, not at the device's pixel density. layoutlib renders a 393x851 dp phone at 440 dpi as a 1080x2340 image; the panel scales that by `160 / dpi` so `100%` means what it means in Android Studio's own preview. The image is kept at full resolution — only the draw is scaled, so a HiDPI screen stays sharp and PNG export is unaffected. |
| Density source | `RenderTask.getHardwareConfigHelper().getConfig().getDensity()` — the density the render actually used. Falls back to `Configuration.getDensity()`, then to 160 dpi, at which the conversion is the identity (raw render pixels, the pre-PG12 display). |
| First render | Starts at **Fit**: the whole composable is visible in the pane without scrolling. If the scroll pane has not been laid out yet — the usual case on the first render after the tool window opens — the fit is deferred and applied on the first resize that gives the viewport a real size. |
| Fit bounds | Fit may land anywhere in `[5%, 400%]`, including below the 25% step stop; the zoom ladder (25/50/75/100/150/200/300/400) is only where the *deliberate* zoom inputs stop — the step buttons, Ctrl+wheel, and trackpad pinch, which resolves each gesture to one ladder step rather than a continuous scale. Content smaller than the pane is upscaled to fill it, as Android Studio's zoom-to-fit does. |
| User zoom wins | Any deliberate zoom — toolbar, Ctrl+wheel, trackpad pinch — cancels the pending fit; a later resize never overwrites it. The next render starts at Fit again. |
```

- [ ] **Step 2: Commit the spec**

```bash
git add compose-preview-gallery-plugin-spec.md && git commit -m "$(printf '%s\n' '[PG12-5] - Record the render panel zoom contract in the spec' '' 'Co-Authored-By: Claude <noreply@anthropic.com>')"
```

- [ ] **Step 3: Build the plugin for the gate**

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"; pgrep -f "gradlew.*runIde"
```

Expected: no output. Then:

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Hand the gate to the user**

Do **not** launch `runIde` and then run further Gradle commands. Ask the user to run the sandbox and check, in a real Android Studio project:

```bash
./gradlew runIde
```

Checklist to give them:

1. Open the Preview Gallery tool window and select a plain `@Preview`. The whole composable is visible immediately — no scrollbars, no scrolling needed to see the bottom.
2. Click `100%` in the render toolbar. The preview is now the same on-screen size as the same composable in Android Studio's own preview pane, side by side.
3. Select a `@Preview(showSystemUi = true)`. The full device frame is visible whole on the first render too.
4. Zoom in with Ctrl+wheel or the toolbar, then drag the tool window's splitter to resize the pane. The chosen zoom survives the resize.
5. Select a different preview. It starts at Fit again.
6. Add a comparison view with a different device. Both tabs fit their own pane on their first render.
7. Export a PNG (`Save image`). The written file is still the full-resolution render (e.g. 1080x2340), not the on-screen size.

- [ ] **Step 5: Report the gate result**

Report exactly what the user observed, including anything that failed. Do not mark the plan complete on an unverified gate.

---

## Self-Review

**Spec coverage:**

| Spec item | Task |
|---|---|
| D1 `RenderOutcome.Success.dpi`, filled from the task's `HardwareConfig` with two fallbacks | Task 2 |
| D2 guarded AS reads | Task 2, Step 5 |
| D3 `contentScale` / `displayScale` across the four consumers | Task 3, Step 3 |
| D4 `zoomFactor` means the AS zoom percentage | Task 3 (property KDoc + `preferredSize` tests) |
| D5 `ZoomMath.MIN`/`MAX`, ladder demoted to step stops, setter re-clamped | Task 1 (math) + Task 3 (setter) |
| D6 `fitFactor` takes dp, may upscale, clamped to `[MIN, MAX]` | Task 1 |
| D7 conversion lives in `ZoomMath` as pure functions, `dpi <= 0` → 1.0 | Task 1 |
| D8 `pendingFit` + viewport `ComponentListener`, cleared by manual zoom | Task 4 |
| D9 `centerPanel.validate()` stays | Untouched by every task — no step edits `PreviewRenderPanel.kt:351` or `:443` |
| D10 comparison tabs get their own dpi | Task 3, Step 4 |
| D11 `KEY_RENDERING = VALUE_RENDER_QUALITY` | Task 3, Step 3 (`paintComponent`) |
| Testing table (all ten cases) | Task 1 tests + Task 3/4 tests |
| Risks: fallback chain, `pendingFit` never clearing | Task 2 Step 5 KDoc; Task 4 Step 3 KDoc |

Follow-ups in the spec (cached multi-step downscale, dead `RenderConfig.kt`) are deliberately not tasks here — both are recorded as follow-ups, not requirements.

**Type consistency:** `setContent(image, viewTree, dpi)` is introduced in Task 3 and used unchanged in Task 4's tests. `ZoomMath.contentScale`/`dpSize`/`MIN`/`MAX` are defined in Task 1 and referenced with those exact names in Tasks 3 and 4. `RenderOutcome.DEFAULT_DPI` is defined in Task 2 and referenced in Task 2's `LiveRenderer` change only. `isFitPending`/`retryFitIfPending` are defined and used in Task 4 alone.
