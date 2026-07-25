# Render View Polish — Zoom, Pan, Export & Click-Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the single render pane zoomable (stepped ladder, cursor-anchored), pannable (scrollbars + wheel + hand tool), and exportable (PNG + clipboard), keeping the Phase 4 hover/click overlay working, and fix click-to-source for same-named source files.

**Architecture:** A new `ZoomableRenderView` (`JComponent`) hosted in a `JBScrollPane` replaces the fit-only `RenderImageLabel` for the `LIVE` state; a pure `ZoomMath` object drives the zoom ladder and cursor-anchor scroll; a `RenderImageExporter` saves/copies the raw render; and `PreviewSourceLocation` gains a `packageHash` used to disambiguate same-named files during navigation. Zoom/pan/export/overlay stay AS-free (`ui/`); the only new AS-internal touch is reading `SourceLocation.packageHash` in `LiveRenderer` (`render/`), guarded.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · JUnit 4 · Swing / `JBScrollPane` · `ImageIO` · AWT clipboard

**Spec:** [2026-07-26-render-view-zoom-pan-export-design.md](../specs/2026-07-26-render-view-zoom-pan-export-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`.
- **Never use the Kotlin `!!` operator.**
- **AS-internal API (`com.android.tools.*`) only in `render/`.** `ZoomMath`, `ZoomableRenderView`, `RenderImageExporter`, `PreviewRenderPanel`, `PreviewGalleryPanel`, `PreviewSourceLocation`, `PreviewViewHitTester` must NOT import `com.android.tools.*`. The only new AS-internal read is `SourceLocation.packageHash` inside `LiveRenderer`.
- **Every AS-internal call site is guarded** against `Exception` and `LinkageError`, degrading to prior behaviour — never crash, never remove existing behaviour.
- **Never render on the EDT; PSI/project-model access under a read action.** (Unchanged from Phase 2–4.)
- **Pure-logic tests are unit-tested; Swing/AS-internal behaviour is verified by a `runIde` gate** the user runs (the standing posture across Phase 2–4). Baseline: **113 tests green**.
- Commit message format: `[PG5-N] - Task name`.
- All documentation, code comments, and commit messages in English.
- Phase 1–4 behaviour must not regress.

## Verification style

Tasks 1, 2 and the pure matcher in Task 4 are plain Kotlin with real unit tests (TDD). Tasks 3 and 4 are **discovery-with-a-verification-gate**: the spec's unknowns V1 (packageHash semantics), V2 (clipboard paste), V3 (cursor-anchored zoom against a live `JBScrollPane`) cannot be settled without a running IDE, so each ends with a `runIde` check by the user, exactly as the Phase 2–4 render/UI gates did.

---

### Task 1: `ZoomMath` — the pure zoom ladder + cursor-anchor scroll

**Goal:** All zoom arithmetic as a pure, unit-tested object. No Swing, no AS API.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt`

**Interfaces:**
- Produces:
  - `ZoomMath.LADDER: List<Double>` — `[0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0]`
  - `ZoomMath.stepIn(current: Double): Double`, `stepOut(current: Double): Double`
  - `ZoomMath.fitFactor(viewport: Dimension, image: Dimension): Double`
  - `ZoomMath.anchorScroll(cursorInView: Point, oldFactor: Double, newFactor: Double, oldScroll: Point): Point`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
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
        assertEquals(0.25, ZoomMath.stepOut(0.25), 1e-9)   // already min
        assertEquals(0.5, ZoomMath.stepOut(0.63), 1e-9)    // off-ladder -> next down
    }

    @Test fun `fitFactor fits the whole image and never upscales`() {
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 200)), 1e-9) // min(2,1) capped 1
        assertEquals(0.5, ZoomMath.fitFactor(Dimension(200, 200), Dimension(400, 400)), 1e-9) // min(.5,.5)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(50, 50)), 1e-9)   // tiny -> capped 1
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(0, 0), Dimension(50, 50)), 1e-9)       // degenerate -> 1
    }

    @Test fun `anchorScroll keeps the point under the cursor stationary`() {
        // cursor at (100,100), scroll (0,0), zoom 1x -> 2x: the render point (100,100) must stay under the cursor.
        assertEquals(Point(100, 100), ZoomMath.anchorScroll(Point(100, 100), 1.0, 2.0, Point(0, 0)))
        // zooming out never yields a negative offset.
        assertEquals(Point(0, 0), ZoomMath.anchorScroll(Point(10, 10), 1.0, 0.5, Point(0, 0)))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.ZoomMathTest"`
Expected: FAIL — `ZoomMath` unresolved.

- [ ] **Step 3: Write `ZoomMath`**

`src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt`:

```kotlin
package com.devomer.previewgallery.ui

import java.awt.Dimension
import java.awt.Point

/**
 * Pure zoom math for the render view: a discrete ladder of zoom levels, fit-to-viewport (never upscaling), and
 * the scroll adjustment that keeps the point under the cursor stationary across a zoom change. No Swing, no AS
 * API — unit-tested.
 */
object ZoomMath {

    /** Discrete zoom levels as fractions: 25% .. 400%. The view's factor is a Double; these are the step stops. */
    val LADDER: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)

    private const val EPS = 1e-6

    /** The smallest ladder level strictly greater than [current]; the maximum if there is none. */
    fun stepIn(current: Double): Double = LADDER.firstOrNull { it > current + EPS } ?: LADDER.last()

    /** The largest ladder level strictly less than [current]; the minimum if there is none. */
    fun stepOut(current: Double): Double = LADDER.lastOrNull { it < current - EPS } ?: LADDER.first()

    /** The factor that fits the whole [image] inside [viewport], never above 1.0 (no auto-upscale). */
    fun fitFactor(viewport: Dimension, image: Dimension): Double {
        if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1.0
        val fit = minOf(viewport.width.toDouble() / image.width, viewport.height.toDouble() / image.height)
        return minOf(fit, 1.0)
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

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.ZoomMathTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomMath.kt src/test/kotlin/com/devomer/previewgallery/ui/ZoomMathTest.kt
git commit -m "[PG5-1] - Pure zoom ladder and cursor-anchor scroll math"
```

---

### Task 2: `RenderImageExporter` — save PNG + copy to clipboard

**Goal:** Export the raw render image. The file-write path is unit-tested; the clipboard path is verified at the Task 3 gate.

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/RenderImageExporter.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/RenderImageExporterTest.kt`

**Interfaces:**
- Produces:
  - `RenderImageExporter.savePng(image: BufferedImage, file: File): Boolean`
  - `RenderImageExporter.copyToClipboard(image: BufferedImage)`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/devomer/previewgallery/ui/RenderImageExporterTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class RenderImageExporterTest {

    @Test fun `savePng writes a png that reads back at the same size`() {
        val image = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB)
        val file = File.createTempFile("pg5-export", ".png").apply { deleteOnExit() }
        assertTrue(RenderImageExporter.savePng(image, file))
        val readBack = ImageIO.read(file)
        assertEquals(20, readBack.width)
        assertEquals(10, readBack.height)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.RenderImageExporterTest"`
Expected: FAIL — `RenderImageExporter` unresolved.

- [ ] **Step 3: Write `RenderImageExporter`**

`src/main/kotlin/com/devomer/previewgallery/ui/RenderImageExporter.kt`:

```kotlin
package com.devomer.previewgallery.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Exports a raw render [BufferedImage] — no overlay, native resolution. The save path takes a [File] (the caller
 * supplies it from a file chooser) so it is unit-testable without UI; the clipboard path is AWT-only.
 */
object RenderImageExporter {

    /** Writes [image] as PNG to [file]. Returns whether it succeeded; the caller logs/notifies on false. */
    fun savePng(image: BufferedImage, file: File): Boolean =
        try {
            ImageIO.write(image, "png", file)
        } catch (e: IOException) {
            false
        }

    /** Puts [image] on the system clipboard as an AWT image transferable. */
    fun copyToClipboard(image: BufferedImage) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --no-configuration-cache --tests "com.devomer.previewgallery.ui.RenderImageExporterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/RenderImageExporter.kt src/test/kotlin/com/devomer/previewgallery/ui/RenderImageExporterTest.kt
git commit -m "[PG5-2] - Raw render PNG-save and clipboard-copy exporter"
```

---

### Task 3: `ZoomableRenderView` + panel integration + toolbar (GATE)

**Goal:** The zoomable/pannable render view with the Phase 4 overlay preserved, the hand-tool mode, and the toolbar (zoom / fit / 100% / hand-tool / save / copy). Settles V2 (clipboard) and V3 (cursor-anchored zoom).

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`

**Interfaces:**
- Consumes: `ZoomMath` (Task 1), `RenderImageExporter` (Task 2), `PreviewViewHitTester` (existing: `innermostAt`, `sourceChainAt`), `PreviewViewNode`, `PreviewSourceLocation` (existing 3-arg form), `RenderOutcome.Success(image, viewTree)`.
- Produces: `ZoomableRenderView` with `setContent(image, viewTree)`, `clearContent()`, `handToolActive`, `zoomFactor`, `fitToViewport()`, `rawImage(): BufferedImage?`, `onNavigateToSource: (List<PreviewSourceLocation>) -> Unit`.

- [ ] **Step 1: Write `ZoomableRenderView`**

`src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt` — Swing only, no `com.android.tools.*`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * A zoomable, pannable view of a render image with the Phase 4 hover-outline / click-to-source overlay. Swing
 * only — the plugin-owned [PreviewViewNode] tree is in render-pixel space and [PreviewViewHitTester] maps it;
 * no `com.android.tools.*` here. Intended to live inside a `JBScrollPane`: [getPreferredSize] is the zoomed
 * image extent, so the scroll pane provides scrollbars and plain-wheel panning.
 *
 * Coordinates: a mouse point in this component is in zoomed-image space, so `renderPoint = point / zoomFactor`
 * (no letterbox — the component's bounds ARE the zoomed image). When [handToolActive], drag pans the enclosing
 * viewport and the overlay is inert; otherwise hover outlines and click navigates (Phase 4).
 */
class ZoomableRenderView : JComponent() {

    private var image: BufferedImage? = null
    private var viewTree: List<PreviewViewNode> = emptyList()

    @Volatile private var hovered: PreviewViewNode? = null

    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    var zoomFactor: Double = 1.0
        set(value) {
            field = value.coerceIn(ZoomMath.LADDER.first(), ZoomMath.LADDER.last())
            revalidate() // preferredSize changed -> scroll pane updates scrollbars
            repaint()
        }

    var handToolActive: Boolean = false
        set(value) {
            field = value
            hovered = null
            cursor = if (value) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            repaint()
        }

    private var panStart: Point? = null

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
        addMouseWheelListener { e -> onWheel(e) }
    }

    /** A new render's image + view tree; resets zoom to [fitToViewport] and clears any prior hover. */
    fun setContent(image: BufferedImage, viewTree: List<PreviewViewNode>) {
        this.image = image
        this.viewTree = viewTree
        this.hovered = null
        fitToViewport()
    }

    fun clearContent() {
        image = null
        viewTree = emptyList()
        hovered = null
        revalidate(); repaint()
    }

    fun rawImage(): BufferedImage? = image

    fun fitToViewport() {
        val img = image ?: return
        val vp = enclosingViewport()?.extentSize ?: size
        zoomFactor = ZoomMath.fitFactor(vp, Dimension(img.width, img.height))
    }

    override fun getPreferredSize(): Dimension {
        val img = image ?: return Dimension(0, 0)
        return Dimension((img.width * zoomFactor).toInt().coerceAtLeast(1), (img.height * zoomFactor).toInt().coerceAtLeast(1))
    }

    override fun paintComponent(g: Graphics) {
        val img = image ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(img, 0, 0, (img.width * zoomFactor).toInt(), (img.height * zoomFactor).toInt(), null)
            val node = hovered
            if (!handToolActive && node != null) {
                val b = node.bounds
                g2.color = HOVER_OUTLINE
                g2.drawRect(
                    (b.x * zoomFactor).toInt(), (b.y * zoomFactor).toInt(),
                    (b.width * zoomFactor).toInt().coerceAtLeast(0), (b.height * zoomFactor).toInt().coerceAtLeast(0),
                )
            }
        } finally {
            g2.dispose()
        }
    }

    private fun renderPointOf(p: Point): Point? {
        if (image == null || zoomFactor <= 0.0) return null
        return Point((p.x / zoomFactor).toInt(), (p.y / zoomFactor).toInt())
    }

    private fun updateHover(p: Point) {
        if (viewTree.isEmpty()) return
        val rp = renderPointOf(p)
        val next = if (rp == null) null else PreviewViewHitTester.innermostAt(viewTree, rp)
        if (next !== hovered) { hovered = next; repaint() }
    }

    private fun navigateAt(p: Point) {
        if (viewTree.isEmpty()) return
        val rp = renderPointOf(p) ?: return
        val chain = PreviewViewHitTester.sourceChainAt(viewTree, rp)
        if (chain.isNotEmpty()) onNavigateToSource(chain)
    }

    private fun panBy(e: MouseEvent) {
        val start = panStart ?: return
        val viewport = enclosingViewport() ?: return
        val pos = viewport.viewPosition
        // Drag right -> content moves right -> view position decreases (bounded by the scroll pane).
        pos.translate(start.x - e.x, start.y - e.y)
        val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        pos.x = pos.x.coerceIn(0, maxX)
        pos.y = pos.y.coerceIn(0, maxY)
        viewport.viewPosition = pos
        // panStart stays in this component's coordinates, which shift with the viewport, so keep it at the event.
        panStart = e.point
    }

    private fun onWheel(e: MouseWheelEvent) {
        if (!e.isControlDown) return // plain/shift wheel -> let the scroll pane pan
        e.consume()
        val viewport = enclosingViewport()
        val old = zoomFactor
        val next = if (e.wheelRotation < 0) ZoomMath.stepIn(old) else ZoomMath.stepOut(old)
        if (next == old) return
        val cursorInView = viewport?.let { SwingUtilities.convertPoint(this, e.point, it) } ?: e.point
        val oldScroll = viewport?.viewPosition ?: Point(0, 0)
        zoomFactor = next
        if (viewport != null) {
            val target = ZoomMath.anchorScroll(cursorInView, old, next, oldScroll)
            val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
            val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
            viewport.viewPosition = Point(target.x.coerceIn(0, maxX), target.y.coerceIn(0, maxY))
        }
    }

    private fun enclosingViewport(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private companion object {
        private val HOVER_OUTLINE = JBColor(Color(0x3574F0), Color(0x548AF7))
    }
}
```

- [ ] **Step 2: Integrate into `PreviewRenderPanel`**

Replace the `RenderImageLabel` (Phase 4) with `ZoomableRenderView` in a `JBScrollPane`, and add the toolbar. Read the current `PreviewRenderPanel.kt` fully first. The changes:

1. Fields: replace `private val imageLabel = RenderImageLabel()` with
   ```kotlin
   private val renderView = ZoomableRenderView()
   private val renderScroll = com.intellij.ui.components.JBScrollPane(renderView)
   ```
   and delete the entire `RenderImageLabel` inner class + the old `rescale()`/`currentImage`/`imageLabel` machinery.

2. In `init`, wire navigation once: `renderView.onNavigateToSource = { onNavigateToSource(it) }`. Keep the public `var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}` field. Remove the `componentResized`→`rescale()` listener (the scroll pane + `ZoomableRenderView.getPreferredSize` handle resize; a new render calls `fitToViewport`).

3. Replace `showImage(success)` with:
   ```kotlin
   private fun showImage(success: RenderOutcome.Success?) {
       val image = success?.image
       if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
       renderView.setContent(image, success.viewTree)
       centerPanel.add(renderScroll, BorderLayout.CENTER)
       renderView.fitToViewport()
   }
   ```
   In `show(...)`, the non-LIVE branches must clear the view: at the top of `show`, replace `imageLabel.clearContent()` with `renderView.clearContent()`.

4. Toolbar: in `updateActionsBar(entry)`, when the current state is `LIVE` (track it — pass a `Boolean live` or read a stored field), add the render controls to `actionsBar` before/around the Properties action:
   ```kotlin
   private fun renderControls(): List<javax.swing.JComponent> {
       val zoomOut = ActionLink("−") { renderView.zoomFactor = ZoomMath.stepOut(renderView.zoomFactor) }
       val zoomIn = ActionLink("+") { renderView.zoomFactor = ZoomMath.stepIn(renderView.zoomFactor) }
       val fit = ActionLink(PreviewGalleryBundle.message("render.fit")) { renderView.fitToViewport() }
       val actual = ActionLink(PreviewGalleryBundle.message("render.actualSize")) { renderView.zoomFactor = 1.0 }
       val hand = com.intellij.ui.components.JBCheckBox(PreviewGalleryBundle.message("render.handTool")).apply {
           addActionListener { renderView.handToolActive = isSelected }
       }
       val save = ActionLink(PreviewGalleryBundle.message("render.savePng")) { savePng() }
       val copy = ActionLink(PreviewGalleryBundle.message("render.copyImage")) { copyImage() }
       return listOf(zoomOut, zoomIn, fit, actual, hand, save, copy)
   }
   ```
   Only add these when there is a live image (`renderView.rawImage() != null`). Keep the Properties action as today.

5. Export handlers on `PreviewRenderPanel`:
   ```kotlin
   private fun savePng() {
       val image = renderView.rawImage() ?: return
       val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
           PreviewGalleryBundle.message("render.savePng"), "", "png",
       )
       val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
           .createSaveFileDialog(descriptor, project)
       val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "preview.png") ?: return
       if (!RenderImageExporter.savePng(image, wrapper.file)) {
           notify(PreviewGalleryBundle.message("render.saveFailed"))
       }
   }

   private fun copyImage() {
       val image = renderView.rawImage() ?: return
       try {
           RenderImageExporter.copyToClipboard(image)
       } catch (e: Exception) {
           notify(PreviewGalleryBundle.message("render.copyFailed"))
       }
   }

   private fun notify(message: String) {
       com.intellij.notification.NotificationGroupManager.getInstance()
           .getNotificationGroup("Compose Preview Gallery")
           .createNotification(message, com.intellij.notification.NotificationType.WARNING)
           .notify(project)
   }
   ```
   Register the notification group in `plugin.xml` (`<notificationGroup id="Compose Preview Gallery" displayType="BALLOON"/>`), and add the new bundle keys (`render.fit`, `render.actualSize`, `render.handTool`, `render.savePng`, `render.copyImage`, `render.saveFailed`, `render.copyFailed`) to `messages/PreviewGalleryBundle.properties`.

Run: `./gradlew compileKotlin --no-configuration-cache` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify the suite still compiles and is green**

Run: `./gradlew test --no-configuration-cache`
Expected: BUILD SUCCESSFUL, **118 tests** (113 baseline + ZoomMath 4 + Exporter 1). Task 3 adds no tests itself (UI, gated). Report the real number.

- [ ] **Step 4: runIde gate (needs the user)**

Do NOT commit before this passes. `./gradlew runIde`, open a Compose project, select a preview, then verify:
1. **Zoom:** `Ctrl`+scroll over the image steps the zoom in/out **anchored under the cursor**; `+` / `−` / `Fit` / `100%` work; a smaller-than-panel preview at `Fit` shows at ≤100 % (not blown up). (V3)
2. **Pan:** when zoomed larger than the pane, scrollbars appear; plain scroll-wheel pans; `Shift`+wheel pans horizontally.
3. **Hand-tool:** toggle ON → cursor is a hand, drag pans, clicking does **not** navigate; toggle OFF → hover outlines and click navigates to source (Phase 4).
4. **Overlay accuracy:** hover/click land on the right composable at `Fit`, `100%`, and a zoomed-in level.
5. **Export:** *Save PNG…* writes a file that opens as the full-resolution render (no outline); *Copy* then paste into an external app (e.g. a chat/doc) pastes the image. (V2)

Capture any offset (V3), wrong clipboard behaviour (V2), or overlay misalignment and report — the controller decides the next move.

- [ ] **Step 5: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ZoomableRenderView.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties src/main/resources/META-INF/plugin.xml
git commit -m "[PG5-3] - Zoomable, pannable render view with hand-tool and export"
```

---

### Task 4: `packageHash` click-to-source hardening (GATE)

**Goal:** When two project source files share a name, click-to-source opens the right one, using AS's `SourceLocation.packageHash`. Falls back to today's behaviour when the hash is unavailable. Settles V1.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt` (the `PreviewSourceLocation` data class)
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt` (capture `packageHash`)
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (`resolveSourceFile`)
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguator.kt` (pure pick)
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguatorTest.kt`
- Test: extend `src/test/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTesterTest.kt` construction of `PreviewSourceLocation` if needed (add the 4th arg).

**Interfaces:**
- Produces:
  - `PreviewSourceLocation(fileName, lineNumber, offset, packageHash: Int?)`
  - `SourceFileDisambiguator.pick(targetHash: Int?, candidates: List<Candidate>): VirtualFile?` where `Candidate = SourceFileDisambiguator.Candidate(file: VirtualFile, packageHash: Int?)`

- [ ] **Step 1: Study `SourceLocation.packageHash`**

Against the AS-253 jars (`platformLocalPath` in `gradle.properties`; `com.android.tools.idea.compose.preview.SourceLocation`), `javap -p`/`javap -c` to learn: the getter for `packageHash` (type — `int`? nullable?), and **how AS derives it** (is it `packageFqName.hashCode()`? of the file's package? verify from `ComposeViewInfoParser`/`SourceLocationImpl`). Record the exact getter + the hashing input in the report — this is V1. If it cannot be pinned, implement capture + the pure pick anyway; the fallback keeps behaviour correct, and the gate settles the derivation.

- [ ] **Step 2: Add `packageHash` to `PreviewSourceLocation`**

In `PreviewViewNode.kt`:

```kotlin
/** Where a composable is declared, resolved from a rendered node's source key. [packageHash] is AS's
 *  same-named-file disambiguator (see [SourceFileDisambiguator]); null when unavailable. */
data class PreviewSourceLocation(val fileName: String, val lineNumber: Int, val offset: Int?, val packageHash: Int?)
```

`./gradlew compileKotlin --no-configuration-cache` will now fail at the two construction sites (`LiveRenderer.toPreviewViewNode`, the test's `srcNode`) — fix them in the next steps.

- [ ] **Step 3: Capture `packageHash` in `LiveRenderer`**

In `LiveRenderer.toPreviewViewNode`, where `PreviewSourceLocation(location.fileName, location.lineNumber, null)` is built, add the packageHash from the AS `SourceLocation`, guarded so a shape change degrades to `null`:

```kotlin
sourceLocation = if (location.isEmpty()) {
    null
} else {
    val hash = runCatching { location.packageHash }.getOrNull() // V1: exact getter name confirmed in Step 1
    PreviewSourceLocation(location.fileName, location.lineNumber, null, hash)
},
```

(Replace `location.packageHash` with the exact accessor from Step 1. `runCatching{}.getOrNull()` here is acceptable — it is not a swallow of a real error, it is the design's degrade-to-null; `ProcessCanceledException` cannot arise from a field read.)

- [ ] **Step 4: Write the pure disambiguator + its test**

`src/test/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguatorTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceFileDisambiguatorTest {

    // LightVirtualFile is a concrete VirtualFile — no mocking framework (this project wires none); distinct
    // instances give us identity to assert on.
    private fun candidate(name: String, hash: Int?): SourceFileDisambiguator.Candidate =
        SourceFileDisambiguator.Candidate(LightVirtualFile(name), hash)

    @Test fun `picks the candidate whose package hash matches the target`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(b.file, SourceFileDisambiguator.pick(22, listOf(a, b)))
    }

    @Test fun `falls back to the first candidate when the target hash is null`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(a.file, SourceFileDisambiguator.pick(null, listOf(a, b)))
    }

    @Test fun `falls back to the first candidate when nothing matches`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(a.file, SourceFileDisambiguator.pick(99, listOf(a, b)))
    }

    @Test fun `returns null for no candidates`() {
        assertNull(SourceFileDisambiguator.pick(1, emptyList()))
    }
}
```

`src/main/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguator.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.openapi.vfs.VirtualFile

/**
 * Picks the right file among same-named candidates using AS's `packageHash`. Pure — the caller computes each
 * candidate's package hash (needs PSI / a read action) and the target hash comes from the clicked node. Falls
 * back to the first candidate (today's `firstOrNull` behaviour) whenever the target is null or nothing matches,
 * so a wrong/absent hash never navigates worse than before.
 */
object SourceFileDisambiguator {

    data class Candidate(val file: VirtualFile, val packageHash: Int?)

    fun pick(targetHash: Int?, candidates: List<Candidate>): VirtualFile? {
        if (candidates.isEmpty()) return null
        if (targetHash != null) {
            candidates.firstOrNull { it.packageHash == targetHash }?.let { return it.file }
        }
        return candidates.first().file
    }
}
```

- [ ] **Step 5: Wire `resolveSourceFile` to disambiguate**

In `PreviewGalleryPanel`, `navigateToSource(chain)` currently calls `resolveSourceFile(location.fileName)`. Thread the node's `packageHash` through and disambiguate. Change the loop to pass the location, and rewrite `resolveSourceFile`:

```kotlin
private fun navigateToSource(chain: List<PreviewSourceLocation>) {
    for (location in chain) {
        val file = resolveSourceFile(location) ?: continue
        OpenFileDescriptor(project, file, location.lineNumber.coerceAtLeast(0), 0).navigate(true)
        return
    }
}

private fun resolveSourceFile(location: PreviewSourceLocation): VirtualFile? {
    lastSelectedEntry?.file?.let { if (it.name == location.fileName) return it }
    if (DumbService.isDumb(project)) return null
    return ReadAction.compute<VirtualFile?, RuntimeException> {
        val matches = FilenameIndex.getVirtualFilesByName(location.fileName, GlobalSearchScope.projectScope(project)).toList()
        if (matches.size <= 1) return@compute matches.firstOrNull()
        val candidates = matches.map { SourceFileDisambiguator.Candidate(it, packageHashOf(it)) }
        SourceFileDisambiguator.pick(location.packageHash, candidates)
    }
}

/** The same hash AS puts on a SourceLocation, computed from a candidate file's package (V1). Under a read
 *  action (caller holds one). Returns null when the package can't be resolved -> that candidate won't match. */
private fun packageHashOf(file: VirtualFile): Int? {
    val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as? org.jetbrains.kotlin.psi.KtFile ?: return null
    val packageFqn = psi.packageFqName.asString()
    return packageFqn.hashCode() // V1: confirm AS hashes the package FQN with String.hashCode; adjust per Step 1.
}
```

Run: `./gradlew test --no-configuration-cache` — Expected: BUILD SUCCESSFUL, suite green (118 + 4 disambiguator = 122; report the real number).

- [ ] **Step 6: runIde gate (needs the user)**

Do NOT commit before this passes. `./gradlew runIde`, open a Compose project with **two source files sharing a name in different packages/modules** (or contrive one), render a preview whose tree references one of them, click a component from that file, and confirm the editor opens the **correct** file (not the other same-named one). Confirm a normal (unique-name) click still works. If the wrong file opens, capture it — that is the V1 answer (the hash derivation in `packageHashOf` needs adjusting to match AS).

- [ ] **Step 7: Commit (after the gate passes)**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/PreviewViewNode.kt src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt src/main/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguator.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/SourceFileDisambiguatorTest.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewViewHitTesterTest.kt
git commit -m "[PG5-4] - Disambiguate click-to-source for same-named files via packageHash"
```

---

### Task 5: Tests, manual verification & changelog

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test --no-configuration-cache`
Expected: PASS — all existing plus the new pure tests (ZoomMath 4, Exporter 1, Disambiguator 4). No skips. Report the real total.

- [ ] **Step 2: Manual verification (AC1–AC7, needs the user)**

In `runIde` against a real Compose project, confirm each acceptance criterion from the spec:
- AC1 Ctrl+scroll zoom stepping anchored at the cursor; +/−/Fit/100% + zoom-% label.
- AC2 pan via scrollbars, wheel, and hand-tool when zoomed past the viewport.
- AC3 hand-tool ON = drag-pan / clicks inert; OFF = hover + click navigate.
- AC4 hover/click correct at 100%, Fit, and zoomed-in.
- AC5 Save-PNG writes the raw render; Copy pastes into an external app; failures notify, no crash.
- AC6 same-named-file click navigates correctly (or falls back).
- AC7 `./gradlew test` green; Phase 1–4 behaviour unchanged.

- [ ] **Step 3: Changelog and commit**

Add an "Added — zoom/pan/export on the render, and same-named-file click-to-source disambiguation" entry to `CHANGELOG.md` under `### Added`, then:

```bash
git add CHANGELOG.md
git commit -m "[PG5-5] - Changelog for render zoom/pan/export and click hardening"
```
