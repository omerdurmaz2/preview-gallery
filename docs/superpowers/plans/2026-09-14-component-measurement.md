# Component Distance Measurement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Select a component in the rendered preview with a click and, while Alt is held, draw the distance in dp
from it to the hovered component, Figma style.

**Architecture:** All geometry (which lines, where, how long, label text) lives in a new pure `MeasurementGeometry`
object with no Swing and no Android Studio types. `ZoomableRenderView`, which already owns the hover outline,
click-to-source and the render-pixel → screen transform (`displayScale`), gains the selection state, the Alt state
and the painting. Nothing outside `ui/` changes except README and CHANGELOG.

**Tech Stack:** Kotlin, Swing, IntelliJ Platform (`AnAction`, `JBUI`, `JBColor`, `GraphicsUtil`), JUnit 4 for
pure tests, `BasePlatformTestCase` for Swing tests, Gradle via `./gradlew`.

**Spec:** [2026-09-14-component-measurement-design.md](../specs/2026-09-14-component-measurement-design.md)

## Global Constraints

- **Workflow:** implement first. Tasks 1–3 have no test-first steps. Every test is written in Task 4, and every new
  test must be shown to fail with its production change reverted.
- **Sandbox rule:** never run `./gradlew` while a runIde sandbox is live. Before every Gradle command,
  `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` must both print nothing.
- **No new comments:** new code gets no comments or KDoc. Existing KDoc that a change makes untrue is edited to stay
  true, as the tasks below show, and nothing more.
- **No `!!` in Kotlin.**
- **Commits:** subject `[PG25-n] - Title`, a body saying why, and the trailer
  `Co-Authored-By: Claude MODEL <noreply@anthropic.com>`. `MODEL` is the executing model's own name (for example
  `Opus 5`), written without angle brackets. Never push; the user pushes.
- **Visual values from the spec:**
  - Selection outline is `BasicStroke(JBUIScale.scale(2f))` in `HOVER_OUTLINE`; the hover outline stays the default 1 px.
  - Measurement color is `JBColor(Color(0xF24822), Color(0xF24822))`. Lines are `JBUIScale.scale(1f)` solid; guides are
    `JBUIScale.scale(1f)` dashed `3/3`.
  - The label is white `JBUI.Fonts.smallFont()` text on a rounded pill of the measurement color, centered on the
    line's midpoint.
  - Label text: `String.format(Locale.ROOT, "%.1f", dp).removeSuffix(".0") + "dp"`, with
    `dp = px * ZoomMath.contentScale(dpi)`.
- **Known unrelated flake:** `McpHttpServerTest` fails intermittently with `BindException: Address already in use`.
  It is tracked separately; do not chase it.
- **Stale test bytecode:** if a test fails with `NoSuchMethodError` naming an old signature, rerun with
  `--no-build-cache --rerun-tasks` before suspecting the code.

---

### Task 1: `MeasurementGeometry`, the pure measurement rules

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/MeasurementGeometry.kt`

**Interfaces:**
- Consumes: `ZoomMath.contentScale(dpi: Int): Double` (existing, same package).
- Produces:
  - `MeasurementGeometry.Line(x1: Double, y1: Double, x2: Double, y2: Double)`, a data class in render pixels.
  - `MeasurementGeometry.Measurement(line: Line, lengthPx: Int, guide: Line? = null)`, a data class.
  - `MeasurementGeometry.measure(selected: Rectangle, hovered: Rectangle): List<Measurement>`. For intersecting
    rectangles the order is horizontal lines first (start edges, then end edges), then vertical. For gaps it is the
    horizontal gap first.
  - `MeasurementGeometry.formatDp(lengthPx: Int, dpi: Int): String`.

- [ ] **Step 1: Create the file**

```kotlin
package com.devomer.previewgallery.ui

import java.awt.Rectangle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object MeasurementGeometry {

    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    data class Measurement(val line: Line, val lengthPx: Int, val guide: Line? = null)

    fun measure(selected: Rectangle, hovered: Rectangle): List<Measurement> {
        val sx = Span(selected.x, selected.x + selected.width)
        val sy = Span(selected.y, selected.y + selected.height)
        val hx = Span(hovered.x, hovered.x + hovered.width)
        val hy = Span(hovered.y, hovered.y + hovered.height)
        val disjointX = sx.disjointFrom(hx)
        val disjointY = sy.disjointFrom(hy)
        val measurements = if (!disjointX && !disjointY) {
            edgeOffsets(sx, hx, sy.overlapCenter(hy), Axis.X) + edgeOffsets(sy, hy, sx.overlapCenter(hx), Axis.Y)
        } else {
            listOfNotNull(
                if (disjointX) gap(sx, hx, sy, hy, Axis.X) else null,
                if (disjointY) gap(sy, hy, sx, hx, Axis.Y) else null,
            )
        }
        return measurements.filter { it.lengthPx > 0 }
    }

    fun formatDp(lengthPx: Int, dpi: Int): String =
        String.format(Locale.ROOT, "%.1f", lengthPx * ZoomMath.contentScale(dpi)).removeSuffix(".0") + "dp"

    private enum class Axis { X, Y }

    private class Span(val start: Int, val end: Int) {
        val center: Double get() = (start + end) / 2.0

        fun disjointFrom(other: Span): Boolean = end <= other.start || other.end <= start

        fun overlapCenter(other: Span): Double = (max(start, other.start) + min(end, other.end)) / 2.0
    }

    private fun edgeOffsets(s: Span, h: Span, across: Double, axis: Axis): List<Measurement> = listOf(
        segment(min(s.start, h.start), max(s.start, h.start), across, axis),
        segment(min(s.end, h.end), max(s.end, h.end), across, axis),
    )

    private fun gap(s: Span, h: Span, sAcross: Span, hAcross: Span, axis: Axis): Measurement {
        val hAfter = s.end <= h.start
        val from = if (hAfter) s.end else h.end
        val to = if (hAfter) h.start else s.start
        if (!sAcross.disjointFrom(hAcross)) return segment(from, to, sAcross.overlapCenter(hAcross), axis)
        val across = sAcross.center
        val hEdge = (if (hAfter) to else from).toDouble()
        val hFacing = (if (hAcross.start >= sAcross.end) hAcross.start else hAcross.end).toDouble()
        return segment(from, to, across, axis).copy(guide = line(hEdge, across, hEdge, hFacing, axis))
    }

    private fun segment(from: Int, to: Int, across: Double, axis: Axis): Measurement =
        Measurement(line(from.toDouble(), across, to.toDouble(), across, axis), to - from)

    private fun line(along1: Double, across1: Double, along2: Double, across2: Double, axis: Axis): Line = when (axis) {
        Axis.X -> Line(along1, across1, along2, across2)
        Axis.Y -> Line(across1, along1, across2, along2)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`, with no warning mentioning `MeasurementGeometry.kt`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/MeasurementGeometry.kt
git commit -F - <<'EOF'
[PG25-3] - Compute the distances between two component bounds

The pure half of the measurement: which lines to draw between a selected and a
hovered bounds rectangle, where they sit, how long they are, and the dp label.
Separated rectangles get gap lines, with a guide in the diagonal case;
intersecting ones get the offsets between matching edges, which is the padding
when one contains the other.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
EOF
```

---

### Task 2: Click selects, double-click navigates, Esc clears

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt` (the existing click-to-source
  test moves to a double click, because the gesture changed)
- Modify: `README.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: `PreviewViewHitTester.innermostAt(roots, point)` (existing).
- Produces, used by Tasks 3 and 4:
  - `ZoomableRenderView.selectedNode: PreviewViewNode?` (internal, read-only)
  - `ZoomableRenderView.clearSelectionAction: AnAction` (internal)
  - private `selected: PreviewViewNode?` and `selectNode(node: PreviewViewNode?)`
  - private `drawOutline(g2: Graphics2D, bounds: Rectangle, scale: Double)`
  - the test helper `clickAt(view, x, y, clickCount: Int = 1)`

- [ ] **Step 1: Imports.** In `ZoomableRenderView.kt` replace

```kotlin
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.ui.JBColor
import java.awt.Color
```

with

```kotlin
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
```

and replace

```kotlin
import java.awt.Point
import java.awt.RenderingHints
```

with

```kotlin
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
```

- [ ] **Step 2: Class KDoc.** Replace the line

```kotlin
 * enclosing viewport and the overlay is inert; otherwise hover outlines and click navigates (Phase 4).
```

with

```kotlin
 * enclosing viewport and the overlay is inert; otherwise hover outlines, a click selects and a double click navigates.
```

- [ ] **Step 3: Selection state and the Esc action.** Replace

```kotlin
    @Volatile private var hovered: PreviewViewNode? = null

    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}
```

with

```kotlin
    @Volatile private var hovered: PreviewViewNode? = null

    private var selected: PreviewViewNode? = null

    internal val selectedNode: PreviewViewNode? get() = selected

    internal val clearSelectionAction: AnAction = object : DumbAwareAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected != null
        }

        override fun actionPerformed(e: AnActionEvent) = selectNode(null)
    }

    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}
```

- [ ] **Step 4: `init`.** Replace the whole `init` block

```kotlin
    init {
        isOpaque = true
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { if (!handToolActive) updateHover(e.point) }
            override fun mouseDragged(e: MouseEvent) { if (handToolActive) panBy(e) }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (handToolActive) panStart = e.point }
            override fun mouseReleased(e: MouseEvent) { panStart = null }
            override fun mouseClicked(e: MouseEvent) {
                if (!handToolActive && SwingUtilities.isLeftMouseButton(e)) navigateAt(e.point)
            }
            override fun mouseExited(e: MouseEvent) { if (hovered != null) { hovered = null; repaint() } }
        })
        ViewportGestures.install(this, ZoomBinding())
    }
```

with

```kotlin
    init {
        isOpaque = true
        isFocusable = true
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { if (!handToolActive) updateHover(e.point) }
            override fun mouseDragged(e: MouseEvent) { if (handToolActive) panBy(e) }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (handToolActive) panStart = e.point }
            override fun mouseReleased(e: MouseEvent) { panStart = null }
            override fun mouseClicked(e: MouseEvent) {
                if (handToolActive || !SwingUtilities.isLeftMouseButton(e)) return
                when (e.clickCount) {
                    1 -> selectAt(e.point)
                    2 -> navigateAt(e.point)
                }
            }
            override fun mouseExited(e: MouseEvent) { if (hovered != null) { hovered = null; repaint() } }
        })
        clearSelectionAction.registerCustomShortcutSet(CommonShortcuts.ESCAPE, this)
        ViewportGestures.install(this, ZoomBinding())
    }
```

- [ ] **Step 5: Clear the selection with the content.** Replace

```kotlin
     * zoom to [fitToViewport] and clears any prior hover.
     */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
```

with

```kotlin
     * zoom to [fitToViewport] and clears any prior hover and selection.
     */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>, dpi: Int) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        this.selected = null
```

and in `clearContent()` replace

```kotlin
        viewTree = emptyList()
        hovered = null
        contentScale = 1.0
```

with

```kotlin
        viewTree = emptyList()
        hovered = null
        selected = null
        contentScale = 1.0
```

- [ ] **Step 6: Paint the selection.** In `paintComponent` replace

```kotlin
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

with

```kotlin
            val baseStroke = g2.stroke
            selected?.let {
                g2.color = HOVER_OUTLINE
                g2.stroke = BasicStroke(JBUIScale.scale(2f))
                drawOutline(g2, it.bounds, scale)
                g2.stroke = baseStroke
            }
            val node = hovered
            if (!handToolActive && node != null && node !== selected) {
                g2.color = HOVER_OUTLINE
                drawOutline(g2, node.bounds, scale)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawOutline(g2: Graphics2D, bounds: Rectangle, scale: Double) {
        g2.drawRect(
            (bounds.x * scale).roundToInt(), (bounds.y * scale).roundToInt(),
            (bounds.width * scale).roundToInt().coerceAtLeast(0),
            (bounds.height * scale).roundToInt().coerceAtLeast(0),
        )
    }
```

- [ ] **Step 7: Select on click.** Replace

```kotlin
    private fun navigateAt(p: Point) {
```

with

```kotlin
    private fun selectAt(p: Point) {
        requestFocusInWindow()
        val rp = renderPointOf(p)
        selectNode(if (rp == null) null else PreviewViewHitTester.innermostAt(viewTree, rp))
    }

    private fun selectNode(node: PreviewViewNode?) {
        if (node === selected) return
        selected = node
        repaint()
    }

    private fun navigateAt(p: Point) {
```

- [ ] **Step 8: Keep the existing click-to-source test on the new gesture.** In `ZoomableRenderViewTest.kt` replace

```kotlin
    private fun clickAt(view: ZoomableRenderView, x: Int, y: Int) {
        view.dispatchEvent(
            MouseEvent(
                view, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1,
            ),
        )
    }
```

with

```kotlin
    private fun clickAt(view: ZoomableRenderView, x: Int, y: Int, clickCount: Int = 1) {
        view.dispatchEvent(
            MouseEvent(
                view, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, x, y, clickCount, false, MouseEvent.BUTTON1,
            ),
        )
    }
```

then replace

```kotlin
     * fixture and geometry as the hover-outline test above, but exercised through a left click instead of a
```

with

```kotlin
     * fixture and geometry as the hover-outline test above, but exercised through a double click instead of a
```

and replace

```kotlin
        clickAt(view, 320, 320)

        assertEquals(listOf(source), received)
```

with

```kotlin
        clickAt(view, 320, 320, clickCount = 2)

        assertEquals(listOf(source), received)
```

- [ ] **Step 9: Docs.** In `README.md` replace

```markdown
- Zoom, pan, save as PNG, copy to clipboard. Click any part of a render to open the code that draws it.
```

with

```markdown
- Zoom, pan, save as PNG, copy to clipboard. Double-click any part of a render to open the code that draws it.
```

In `CHANGELOG.md` replace

```markdown
## [Unreleased]

## [0.1.0] - 2026-08-20
```

with

```markdown
## [Unreleased]

### Changed

- Opening a composable's source from the render now takes a double click; a single click selects the composable.

## [0.1.0] - 2026-08-20
```

- [ ] **Step 10: Compile and run the existing view tests**

Run: `./gradlew compileKotlin compileTestKotlin && ./gradlew test --rerun --tests 'com.devomer.previewgallery.ui.ZoomableRenderViewTest'`
Expected: `BUILD SUCCESSFUL`; all 10 existing tests pass, including the click-to-source one on a double click.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt README.md CHANGELOG.md
git commit -F - <<'EOF'
[PG25-4] - Select a component with a click, navigate with a double click

Measuring needs a selection that outlives the pointer, and a single click was
already taken by click-to-source. A click now selects the innermost component
and keeps a 2 px outline on it; a double click navigates as the single click
did. Selecting no longer moves the editor or takes focus away from the render.

Esc clears the selection through a component-scoped action that is disabled
while nothing is selected, so Esc keeps its tool-window meaning otherwise. A
new render clears the selection, and the hand tool leaves it alone.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
EOF
```

---

### Task 3: Alt draws the measurement

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`
- Modify: `README.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: `MeasurementGeometry.measure`, `MeasurementGeometry.formatDp`, `MeasurementGeometry.Line`,
  `MeasurementGeometry.Measurement` (Task 1); `selected`, `selectedNode`, `drawOutline` (Task 2).
- Produces, used by Task 4:
  - `ZoomableRenderView.currentMeasurements(): List<MeasurementGeometry.Measurement>` (internal). It is empty unless
    something is selected, a different node is hovered, and Alt is down.
  - The view's key listener, which reacts to `VK_ALT` press and release.
  - The view's focus listener, whose `focusLost` drops the Alt state.

- [ ] **Step 1: Imports.** Replace

```kotlin
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
```

with

```kotlin
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
```

and replace

```kotlin
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
```

with

```kotlin
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
```

- [ ] **Step 2: Class KDoc.** Replace

```kotlin
 * enclosing viewport and the overlay is inert; otherwise hover outlines, a click selects and a double click navigates.
```

with

```kotlin
 * enclosing viewport and the overlay is inert; otherwise hover outlines, a click selects, a double click navigates,
 * and holding Alt measures from the selection to the hovered node.
```

- [ ] **Step 3: State.** Replace

```kotlin
    internal val selectedNode: PreviewViewNode? get() = selected
```

with

```kotlin
    internal val selectedNode: PreviewViewNode? get() = selected

    private var altDown: Boolean = false

    private var dpi: Int = 0
```

- [ ] **Step 4: Alt from mouse moves, keys and focus.** In `init` replace

```kotlin
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { if (!handToolActive) updateHover(e.point) }
            override fun mouseDragged(e: MouseEvent) { if (handToolActive) panBy(e) }
        })
```

with

```kotlin
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (handToolActive) return
                setAltDown(e.isAltDown)
                updateHover(e.point)
            }
            override fun mouseDragged(e: MouseEvent) { if (handToolActive) panBy(e) }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ALT) setAltDown(true) }
            override fun keyReleased(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ALT) setAltDown(false) }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = setAltDown(false)
        })
```

- [ ] **Step 5: Keep the density for the label.** In `setContent` replace

```kotlin
        this.selected = null
        this.contentScale = ZoomMath.contentScale(dpi)
```

with

```kotlin
        this.selected = null
        this.dpi = dpi
        this.contentScale = ZoomMath.contentScale(dpi)
```

- [ ] **Step 6: Paint the measurements.** In `paintComponent` replace

```kotlin
            if (!handToolActive && node != null && node !== selected) {
                g2.color = HOVER_OUTLINE
                drawOutline(g2, node.bounds, scale)
            }
        } finally {
```

with

```kotlin
            if (!handToolActive && node != null && node !== selected) {
                g2.color = HOVER_OUTLINE
                drawOutline(g2, node.bounds, scale)
            }
            paintMeasurements(g2, currentMeasurements(), scale)
        } finally {
```

- [ ] **Step 7: The measurement functions.** Replace

```kotlin
    private fun drawOutline(g2: Graphics2D, bounds: Rectangle, scale: Double) {
```

with

```kotlin
    internal fun currentMeasurements(): List<MeasurementGeometry.Measurement> {
        val from = selected ?: return emptyList()
        val to = hovered ?: return emptyList()
        if (!altDown || to === from) return emptyList()
        return MeasurementGeometry.measure(from.bounds, to.bounds)
    }

    private fun setAltDown(down: Boolean) {
        if (altDown == down) return
        altDown = down
        repaint()
    }

    private fun paintMeasurements(g2: Graphics2D, measurements: List<MeasurementGeometry.Measurement>, scale: Double) {
        if (measurements.isEmpty()) return
        g2.color = MEASURE_COLOR
        val solid = BasicStroke(JBUIScale.scale(1f))
        val dashed = BasicStroke(
            JBUIScale.scale(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            floatArrayOf(JBUIScale.scale(3f), JBUIScale.scale(3f)), 0f,
        )
        measurements.forEach { measurement ->
            measurement.guide?.let {
                g2.stroke = dashed
                drawLine(g2, it, scale)
            }
            g2.stroke = solid
            drawLine(g2, measurement.line, scale)
        }
        g2.font = JBUI.Fonts.smallFont()
        GraphicsUtil.setupAAPainting(g2)
        GraphicsUtil.setupAntialiasing(g2)
        measurements.forEach { measurement ->
            val line = measurement.line
            paintLabel(
                g2,
                MeasurementGeometry.formatDp(measurement.lengthPx, dpi),
                (line.x1 + line.x2) / 2 * scale,
                (line.y1 + line.y2) / 2 * scale,
            )
        }
    }

    private fun drawLine(g2: Graphics2D, line: MeasurementGeometry.Line, scale: Double) {
        g2.drawLine(
            (line.x1 * scale).roundToInt(), (line.y1 * scale).roundToInt(),
            (line.x2 * scale).roundToInt(), (line.y2 * scale).roundToInt(),
        )
    }

    private fun paintLabel(g2: Graphics2D, text: String, centerX: Double, centerY: Double) {
        val metrics = g2.fontMetrics
        val padX = JBUI.scale(4)
        val padY = JBUI.scale(1)
        val width = metrics.stringWidth(text) + padX * 2
        val height = metrics.height + padY * 2
        val x = (centerX - width / 2.0).roundToInt()
        val y = (centerY - height / 2.0).roundToInt()
        val arc = JBUI.scale(6)
        g2.color = MEASURE_COLOR
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = Color.WHITE
        g2.drawString(text, x + padX, y + padY + metrics.ascent)
    }

    private fun drawOutline(g2: Graphics2D, bounds: Rectangle, scale: Double) {
```

- [ ] **Step 8: The color.** Replace

```kotlin
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
```

with

```kotlin
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
        private val MEASURE_COLOR = JBColor(Color(0xF24822), Color(0xF24822))
```

- [ ] **Step 9: Docs.** In `README.md` replace

```markdown
- Zoom, pan, save as PNG, copy to clipboard. Double-click any part of a render to open the code that draws it.
```

with

```markdown
- Zoom, pan, save as PNG, copy to clipboard. Double-click any part of a render to open the code that draws it.
- Measure spacing the way Figma does: click a component, hold Alt (Option on macOS) and point at another to read
  the distance between them in dp, or the four paddings when one contains the other.
```

In `CHANGELOG.md` replace

```markdown
## [Unreleased]

### Changed
```

with

```markdown
## [Unreleased]

### Added

- Distance measurement on the render: select a composable, hold Alt (Option on macOS) and point at another to see
  the gap between them in dp, or the four paddings when one contains the other.

### Changed
```

- [ ] **Step 10: Compile and run the existing view tests**

Run: `./gradlew compileKotlin compileTestKotlin && ./gradlew test --rerun --tests 'com.devomer.previewgallery.ui.ZoomableRenderViewTest'`
Expected: `BUILD SUCCESSFUL`; all 10 tests pass.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt README.md CHANGELOG.md
git commit -F - <<'EOF'
[PG25-5] - Measure the distance to the hovered component while Alt is down

With a selection, holding Alt over another component draws the lines
MeasurementGeometry computes, each with its dp label, on top of both outlines.

Alt is read from key events, so pressing it without moving the mouse shows the
measurement at once, and from every mouse move, so it still works while focus
sits in the editor after a double-click navigation. Losing focus drops the key
state, so a release that happens outside the view cannot leave the lines stuck.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
EOF
```

---

### Task 4: Test & Review

**Files:**
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/MeasurementGeometryTest.kt`
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt`

**Interfaces:**
- Consumes: everything Tasks 1–3 produced.

- [ ] **Step 1: Write `MeasurementGeometryTest.kt`**

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.ui.MeasurementGeometry.Line
import com.devomer.previewgallery.ui.MeasurementGeometry.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Rectangle

class MeasurementGeometryTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rectangle(left, top, right - left, bottom - top)

    private val paddings = listOf(
        Measurement(Line(0.0, 25.0, 20.0, 25.0), 20),
        Measurement(Line(80.0, 25.0, 100.0, 25.0), 20),
        Measurement(Line(50.0, 0.0, 50.0, 10.0), 10),
        Measurement(Line(50.0, 40.0, 50.0, 50.0), 10),
    )

    @Test
    fun `side by side gives one horizontal gap at the middle of the vertical overlap`() {
        assertEquals(
            listOf(Measurement(Line(100.0, 30.0, 120.0, 30.0), 20)),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(120, 10, 160, 60)),
        )
    }

    @Test
    fun `stacked gives one vertical gap at the middle of the horizontal overlap`() {
        assertEquals(
            listOf(Measurement(Line(50.0, 50.0, 50.0, 70.0), 20)),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(10, 70, 90, 90)),
        )
    }

    @Test
    fun `diagonal gives both gaps from the selection center, each with a guide to the hovered node`() {
        assertEquals(
            listOf(
                Measurement(Line(100.0, 25.0, 150.0, 25.0), 50, Line(150.0, 25.0, 150.0, 80.0)),
                Measurement(Line(50.0, 50.0, 50.0, 80.0), 30, Line(50.0, 80.0, 150.0, 80.0)),
            ),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(150, 80, 200, 120)),
        )
    }

    @Test
    fun `a hovered node up and to the left gets its guides to its own facing edges`() {
        assertEquals(
            listOf(
                Measurement(Line(50.0, 125.0, 100.0, 125.0), 50, Line(50.0, 125.0, 50.0, 50.0)),
                Measurement(Line(125.0, 50.0, 125.0, 100.0), 50, Line(125.0, 50.0, 50.0, 50.0)),
            ),
            MeasurementGeometry.measure(rect(100, 100, 150, 150), rect(0, 0, 50, 50)),
        )
    }

    @Test
    fun `a selection inside the hovered node gives its four paddings`() {
        assertEquals(paddings, MeasurementGeometry.measure(rect(20, 10, 80, 40), rect(0, 0, 100, 50)))
    }

    @Test
    fun `a hovered node inside the selection gives its four paddings`() {
        assertEquals(paddings, MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(20, 10, 80, 40)))
    }

    @Test
    fun `partially overlapping nodes give the offsets between their matching edges`() {
        assertEquals(
            listOf(
                Measurement(Line(0.0, 37.5, 50.0, 37.5), 50),
                Measurement(Line(100.0, 37.5, 150.0, 37.5), 50),
                Measurement(Line(75.0, 0.0, 75.0, 25.0), 25),
                Measurement(Line(75.0, 50.0, 75.0, 75.0), 25),
            ),
            MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(50, 25, 150, 75)),
        )
    }

    @Test
    fun `touching nodes measure nothing`() {
        assertEquals(emptyList<Measurement>(), MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(100, 0, 150, 50)))
    }

    @Test
    fun `nodes with the same bounds measure nothing`() {
        assertEquals(emptyList<Measurement>(), MeasurementGeometry.measure(rect(0, 0, 100, 50), rect(0, 0, 100, 50)))
    }

    @Test
    fun `labels are whole dp when the value is whole and one decimal otherwise`() {
        assertEquals("16dp", MeasurementGeometry.formatDp(44, 440))
        assertEquals("16.4dp", MeasurementGeometry.formatDp(45, 440))
        assertEquals("16dp", MeasurementGeometry.formatDp(16, 160))
    }
}
```

- [ ] **Step 2: Extend `ZoomableRenderViewTest.kt`.** Replace the imports

```kotlin
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
```

with

```kotlin
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
```

then replace

```kotlin
    private fun moveTo(view: ZoomableRenderView, x: Int, y: Int) {
        view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false))
    }
```

with

```kotlin
    private fun moveTo(view: ZoomableRenderView, x: Int, y: Int, modifiers: Int = 0) {
        view.dispatchEvent(
            MouseEvent(view, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), modifiers, x, y, 0, false),
        )
    }

    private fun measuredView(): Triple<ZoomableRenderView, PreviewViewNode, PreviewViewNode> {
        val left = node(40, 40, 100, 60, PreviewSourceLocation("Left.kt", 1, offset = null, packageHash = null))
        val right = node(200, 40, 100, 60, PreviewSourceLocation("Right.kt", 1, offset = null, packageHash = null))
        val view = ZoomableRenderView()
        view.setContent(BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB), listOf(left, right), dpi = 160)
        view.zoomFactor = 1.0
        return Triple(view, left, right)
    }

    private fun altKey(view: ZoomableRenderView, id: Int): KeyEvent =
        KeyEvent(view, id, System.currentTimeMillis(), 0, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED)
```

and add these tests before the final closing `}` of the class:

```kotlin
    fun `test a single click selects the innermost node without navigating`() {
        val (view, left, _) = measuredView()
        var navigated = false
        view.onNavigateToSource = { navigated = true }

        clickAt(view, 50, 50)

        assertSame(left, view.selectedNode)
        assertFalse(navigated)
    }

    fun `test clicking outside every node clears the selection`() {
        val (view, _, _) = measuredView()
        clickAt(view, 50, 50)
        assertNotNull(view.selectedNode)

        clickAt(view, 10, 10)

        assertNull(view.selectedNode)
    }

    fun `test the escape action is bound to Escape and clears the selection only when there is one`() {
        val (view, _, _) = measuredView()
        val action = view.clearSelectionAction
        assertSame(CommonShortcuts.ESCAPE, action.shortcutSet)

        val idle = TestActionEvent.createTestEvent(action)
        action.update(idle)
        assertFalse(idle.presentation.isEnabled)

        clickAt(view, 50, 50)
        val armed = TestActionEvent.createTestEvent(action)
        action.update(armed)
        assertTrue(armed.presentation.isEnabled)

        action.actionPerformed(armed)
        assertNull(view.selectedNode)
    }

    fun `test new content clears the selection`() {
        val (view, left, right) = measuredView()
        clickAt(view, 50, 50)
        assertNotNull(view.selectedNode)

        view.setContent(BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB), listOf(left, right), dpi = 160)

        assertNull(view.selectedNode)
    }

    fun `test the hand tool neither selects nor measures`() {
        val (view, left, _) = measuredView()
        clickAt(view, 50, 50)
        view.handToolActive = true

        clickAt(view, 220, 50)
        moveTo(view, 220, 50, InputEvent.ALT_DOWN_MASK)

        assertSame(left, view.selectedNode)
        assertTrue(view.currentMeasurements().isEmpty())
    }

    fun `test a click leaves the selection outline drawn without any hover`() {
        val view = ZoomableRenderView()
        view.background = Color.WHITE
        view.setContent(BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB), listOf(node(800, 800, 110, 110)), dpi = 440)
        view.zoomFactor = 1.0
        view.setSize(view.preferredSize)
        assertEquals(Color.WHITE.rgb, paintToImage(view).getRGB(311, 291))

        clickAt(view, 320, 320)

        assertTrue(paintToImage(view).getRGB(311, 291) != Color.WHITE.rgb)
    }

    fun `test measurements appear only while the mouse move reports Alt`() {
        val (view, left, right) = measuredView()
        clickAt(view, 50, 50)

        moveTo(view, 220, 50)
        assertTrue(view.currentMeasurements().isEmpty())

        moveTo(view, 220, 50, InputEvent.ALT_DOWN_MASK)
        assertFalse(view.currentMeasurements().isEmpty())
        assertEquals(MeasurementGeometry.measure(left.bounds, right.bounds), view.currentMeasurements())
    }

    fun `test the Alt key toggles measurements without a mouse move and losing focus drops them`() {
        val (view, _, _) = measuredView()
        clickAt(view, 50, 50)
        moveTo(view, 220, 50)

        view.keyListeners.forEach { it.keyPressed(altKey(view, KeyEvent.KEY_PRESSED)) }
        assertFalse(view.currentMeasurements().isEmpty())

        view.keyListeners.forEach { it.keyReleased(altKey(view, KeyEvent.KEY_RELEASED)) }
        assertTrue(view.currentMeasurements().isEmpty())

        view.keyListeners.forEach { it.keyPressed(altKey(view, KeyEvent.KEY_PRESSED)) }
        view.focusListeners.forEach { it.focusLost(FocusEvent(view, FocusEvent.FOCUS_LOST)) }
        assertTrue(view.currentMeasurements().isEmpty())
    }
```

- [ ] **Step 3: Run both suites green**

Run: `./gradlew test --rerun --tests 'com.devomer.previewgallery.ui.MeasurementGeometryTest' --tests 'com.devomer.previewgallery.ui.ZoomableRenderViewTest'`
Expected: `BUILD SUCCESSFUL`; 10 geometry tests and 18 view tests pass. If one fails, fix the production code, not the
expectation, unless the expectation contradicts the spec's worked-examples table.

- [ ] **Step 4: Revert-check every new test.** Apply **one** revert at a time, run the named suite, confirm the named
test fails for the stated reason, then restore with `git checkout -- <file>` and move to the next. Paste each
failing output into the report.

| # | Revert in production code | Suite | Must fail |
|---|---|---|---|
| 1 | `Span.disjointFrom`: both `<=` → `<` | `MeasurementGeometryTest` | `touching nodes measure nothing` |
| 2 | `edgeOffsets`: `min(s.start, h.start), max(s.start, h.start)` → `s.start, h.start` | `MeasurementGeometryTest` | `a selection inside the hovered node gives its four paddings` |
| 3 | `gap`: `hAcross.start >= sAcross.end` → `hAcross.start < sAcross.end` | `MeasurementGeometryTest` | `diagonal gives both gaps…` and `a hovered node up and to the left…` |
| 4 | `formatDp`: drop `.removeSuffix(".0")` | `MeasurementGeometryTest` | `labels are whole dp…` (`16.0dp`) |
| 5 | `mouseClicked`: `1 -> selectAt(e.point)` → `1 -> navigateAt(e.point)` | `ZoomableRenderViewTest` | `a single click selects the innermost node without navigating` |
| 6 | `selectAt`: last line → `PreviewViewHitTester.innermostAt(viewTree, rp ?: return)?.let(::selectNode)` | `ZoomableRenderViewTest` | `clicking outside every node clears the selection` |
| 7 | delete `clearSelectionAction.registerCustomShortcutSet(CommonShortcuts.ESCAPE, this)` | `ZoomableRenderViewTest` | `the escape action is bound to Escape…` |
| 8 | `update`: `selected != null` → `true` | `ZoomableRenderViewTest` | `the escape action is bound to Escape…` |
| 9 | `setContent`: delete `this.selected = null` | `ZoomableRenderViewTest` | `new content clears the selection` |
| 10 | `mouseClicked`: `handToolActive \|\| ` removed from the guard | `ZoomableRenderViewTest` | `the hand tool neither selects nor measures` |
| 11 | `mouseMoved`: delete `if (handToolActive) return` | `ZoomableRenderViewTest` | `the hand tool neither selects nor measures` |
| 12 | `paintComponent`: delete the `selected?.let { … }` block | `ZoomableRenderViewTest` | `a click leaves the selection outline drawn without any hover` |
| 13 | `mouseMoved`: delete `setAltDown(e.isAltDown)` | `ZoomableRenderViewTest` | `measurements appear only while the mouse move reports Alt` |
| 14 | delete the `addFocusListener` block | `ZoomableRenderViewTest` | `the Alt key toggles measurements…` (last assertion) |

Run: `./gradlew test --rerun --tests 'com.devomer.previewgallery.ui.<Suite>'`
Expected: `BUILD FAILED` naming the test in the table. After restoring, `git status` must show only the two test files
as changed.

- [ ] **Step 5: Full suite**

Run: `./gradlew test`
Expected: everything passes except, possibly, the known `McpHttpServerTest` `BindException` flake.

- [ ] **Step 6: Commit the tests**

```bash
git add src/test/kotlin/com/devomer/previewgallery/ui/MeasurementGeometryTest.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomableRenderViewTest.kt
git commit -F - <<'EOF'
[PG25-6] - Test the component measurement

MeasurementGeometryTest walks the spec's worked-examples table, plus the
mirrored guide case and the dp label format. ZoomableRenderViewTest covers the
gestures: a click selects without navigating, clicking outside and Escape and
new content clear the selection, the hand tool neither selects nor measures,
the selection outline is painted, and Alt from a mouse move, a key press or a
focus loss turns the measurement on and off.

Each test was shown to fail with its production line reverted.

Co-Authored-By: Claude MODEL <noreply@anthropic.com>
EOF
```

- [ ] **Step 7: Code review.** Run the `code-review` skill on the PG25 commits, then fix every confirmed finding. Rerun
Step 5 after the fixes and commit them as `[PG25-7] - Apply the review fixes for the component measurement`, with a
body listing what changed and why.

- [ ] **Step 8: Hand the gate to the user.** Stop and ask the user to verify in a fresh `runIde`, with no Gradle
running alongside it:
1. Click a component: a 2 px outline stays after the pointer leaves it. Clicking empty space removes it.
2. Double-click a component: the editor opens its source.
3. Select a child, hold Alt, and point at its parent's padding: four lines, each with a dp label matching the
   composable's `padding`.
4. Select one of two siblings in a `Row` with `spacedBy(8.dp)`, hold Alt, and point at the other: one line labelled
   `8dp`.
5. Release Alt: the lines disappear. Press Alt again without moving the mouse: they reappear.
