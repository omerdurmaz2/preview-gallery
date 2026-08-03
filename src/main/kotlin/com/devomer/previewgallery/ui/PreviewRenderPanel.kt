package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.RenderOutcome
import com.devomer.previewgallery.model.ViewOverride
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.InplaceButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JComponent

/** The right side of the tool window's split. Shows the eight [RenderState]s plus a persistent actions bar. */
class PreviewRenderPanel(private val project: Project) : JBPanel<PreviewRenderPanel>(BorderLayout()) {

    var onRender: (PreviewEntry) -> Unit = {}
    var onOpenFile: (PreviewEntry) -> Unit = {}

    /** Fires when the user clicks Properties **while Original is the active tab**, with a screen anchor for the
     *  popup to open next to the button (design/spec P4). Only ever wired to fire when Original is active and
     *  [propertiesAvailable] is true — see [updateActionsBar]/`PropertiesAction`. A copy tab opens
     *  [onEphemeralProperties] instead (PG6-10) and never reaches this callback at all. */
    var onProperties: (PreviewEntry, RelativePoint) -> Unit = { _, _ -> }

    /** Fires when the user clicks Properties **while a copy tab is active** (PG6-10), with that copy's own
     *  current [ViewOverride], a screen anchor for the popup, and an `onEdit: (name, value) -> Unit` callback the
     *  bridge invokes once per edited property. Wired by
     *  [com.devomer.previewgallery.ui.PreviewGalleryPanel] to
     *  [com.devomer.previewgallery.render.EphemeralPickerBridge.showEphemeralPicker]; each `onEdit` call updates
     *  [comparisonViews] and re-renders just that tab — see `PropertiesAction`. Only ever wired to fire when a
     *  copy is active and [deviceOverrideAvailable] is true (mirroring [onProperties]'s own gating for Original). */
    var onEphemeralProperties: (PreviewEntry, ViewOverride, RelativePoint, (String, String) -> Unit) -> Unit =
        { _, _, _, _ -> }

    /** Fires when the user clicks a composable in the rendered image (PG4-5): the hit-tested node's source
     *  location. [PreviewGalleryPanel] resolves it to an editor open. Only ever fires for a [RenderState.LIVE]
     *  render whose [RenderOutcome.Success.viewTree] is non-empty (Feature B available); otherwise inert. */
    var onNavigateToSource: (List<PreviewSourceLocation>) -> Unit = {}

    /** Whether Android Studio's picker API is available on this build (design D4/§5). Set once by the owner
     *  ([com.devomer.previewgallery.ui.PreviewGalleryPanel], from `PreviewPickerBridge.isAvailable()`) before
     *  the first [show] call. When false, the Properties action is never added — a missing API is invisible,
     *  not a dead control. */
    var propertiesAvailable: Boolean = false

    /** Requests an off-EDT render of a [PreviewEntry] with a specific [ViewOverride] for one comparison-view tab
     *  (PG6-4; widened to the full property override in PG6-9). Wired by
     *  [com.devomer.previewgallery.ui.PreviewGalleryPanel] to
     *  [com.devomer.previewgallery.render.RenderPipeline.renderVariant]; the callback is always delivered on the
     *  EDT. Kept separate from [onRender] so a tab-strip render never touches the pipeline's single debounced
     *  Original selection. */
    var onRequestVariant: (PreviewEntry, ViewOverride, (RenderOutcome) -> Unit) -> Unit = { _, _, _ -> }

    /** Whether Android Studio's view-override render path is available on this build (PG6-3, widened in PG6-7).
     *  Set once by the owner ([com.devomer.previewgallery.ui.PreviewGalleryPanel], from
     *  `RenderApiProbe.isViewOverrideAvailable()`) before the first [show] call, mirroring [propertiesAvailable].
     *  When false, the ＋ Add view action is never added to the toolbar — comparison views are invisible, not a
     *  dead control, on a build missing the capability. PG6-8: also gates Properties whenever a copy tab is the
     *  active one (see [updateActionsBar]) — re-rendering a copy needs the same render capability adding one
     *  does, so the two share this one flag rather than a separate probe. */
    var deviceOverrideAvailable: Boolean = false

    private val renderView = ZoomableRenderView()
    private val renderScroll = JBScrollPane(renderView)
    private val actionsBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0))
    private val centerPanel = JBPanel<JBPanel<*>>(BorderLayout())

    // ── PG6-4: the comparison-view tab strip. [comparisonViews] is the pure id/device model (Task 2); every
    // other field below is this panel's own Swing-side state, keyed by extra id. Original has no entry in any of
    // them — it is always [renderView]/[renderScroll], never rebuilt. ──
    private val comparisonViews = ComparisonViewList()
    private val viewTabs = JBTabbedPane()
    private val extraViews = HashMap<Int, ZoomableRenderView>()
    private val extraScrolls = HashMap<Int, JBScrollPane>()

    /** Per-extra-id tab header title label (PG6-8), so [refreshExtraTabTitles] can update a tab's visible title
     *  in place — from [ViewTitle] — whenever its config changes or a close shifts its ordinal, without rebuilding
     *  the whole header component (which would also mean re-registering a fresh close button on every refresh). */
    private val extraTabLabels = HashMap<Int, JBLabel>()

    /** Per-extra-id staleness token: bumped by [renderInto] every time it (re)requests a render for that id. An
     *  async result is applied only if this still matches the token it was issued with — a mismatch means the
     *  config changed again, the tab closed, or [clearComparisonExtras] ran, so the result is dropped. Scoped
     *  per-tab, deliberately independent of [com.devomer.previewgallery.render.RenderPipeline]'s own shared
     *  `generation` (which [onRequestVariant]'s render path never touches, by design — see its doc). */
    private val extraGenerations = HashMap<Int, Int>()

    /** Ids that have had at least one render requested, so [viewTabs]'s change listener (first-activation render,
     *  below) never fires a redundant second request for a tab [addExtraTab] already triggered. */
    private val extraRequested = HashSet<Int>()

    /** The entry the panel is currently showing. Tracked so ＋ Add view / a copy-tab Properties re-render know
     *  what to render, and so [show] can tell a genuinely new selection (clear the extras — PG6-4/V3) from
     *  another state update for the same entry (e.g. RENDERING -> LIVE, or a picker-triggered re-render), which
     *  must leave them alone. */
    private var currentEntry: PreviewEntry? = null

    // ── PG13-12: the reference-image strip a snapshot row shows instead of a render (spec D7/D8). ──

    /**
     * The strip installed in [renderScroll]'s viewport, or null whenever there is none.
     *
     * It answers "is a strip installed?", which is what the zoom and export actions need — they act on the
     * strip's pixels, so without one there is nothing for them to do — and what [clearReferenceStrip] uses to
     * know it has to put [renderView] back. It is deliberately **not** the test for "is a snapshot showing?":
     * the two diverge in [RenderState.NO_REFERENCE], where a snapshot is selected but no strip exists. That
     * question has its own answer, [showingSnapshot].
     */
    private var referenceStrip: ReferenceStripView? = null

    /** [referenceStrip]'s one shared scale (spec D7: fit and zoom apply to the whole strip, never per image). */
    private var referenceScale: Double = 1.0

    /**
     * Whether [referenceStrip] still owes its first fit because the viewport had no size yet — the strip's half
     * of PG12-4's deferred fit. Settled by the viewport resize listener installed in `init` below, or by any
     * manual zoom (see [setReferenceScale]), exactly as [ZoomableRenderView]'s own `pendingFit` is.
     */
    private var referenceFitPending: Boolean = false

    /** The state [show] last installed a view for. Internal, not private, only so [PreviewGalleryPanel]'s
     *  `renderStateForTest` seam can assert what a selection routed to without a live render — mirroring
     *  [ZoomableRenderView.isFitPending]. */
    internal var activeState: RenderState = RenderState.IDLE
        private set

    /** The Gradle tasks that would generate the missing references, for [noReference]. Owned by the snapshot
     *  path alone: the two-argument [show] clears it, so a live render can never inherit a snapshot's advice. */
    private var referenceTasks: List<String> = emptyList()

    /**
     * Whether the entry on screen is a snapshot (spec D8) — the question every "this control ends in a render"
     * gate has to ask, and which [referenceStrip] cannot answer: [RenderState.NO_REFERENCE] is a snapshot with
     * no strip installed, so testing the strip would leave a snapshot holding controls that can only act on a
     * `@Preview`. Read from [activeState], which [show] assigns before it calls [updateActionsBar].
     */
    private val showingSnapshot: Boolean
        get() = activeState == RenderState.REFERENCE || activeState == RenderState.NO_REFERENCE

    /** The group [updateActionsBar] built last, kept only for [actionTitlesForTest] — the toolbar itself is a
     *  platform component whose buttons a test cannot read back without pumping the event queue. */
    private var lastActionGroup = DefaultActionGroup()

    /** The titles of the controls currently on the actions bar, separators excluded, so a capability gate can be
     *  asserted without clicking anything. */
    @TestOnly
    internal fun actionTitlesForTest(): List<String> =
        lastActionGroup.childActionsOrStubs.mapNotNull { it.templatePresentation.text }

    @TestOnly
    internal fun messageForTest(): String? =
        UIUtil.findComponentOfType(centerPanel, JBLabel::class.java)?.text

    init {
        border = JBUI.Borders.empty(8)
        renderView.onNavigateToSource = { onNavigateToSource(it) }
        actionsBar.isOpaque = false
        add(actionsBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        // PG12-4's deferred first fit, for the reference strip. On the first selection after the tool window
        // opens, add() has not laid the scroll pane out yet, so its viewport still reports a 0x0 extent and
        // there is nothing to fit against. [ZoomableRenderView] records that debt itself and settles it from a
        // listener on this very viewport; the strip is a plain JComponent, so the panel that owns it holds the
        // debt instead — same mechanism, same viewport, same trigger, rather than a second one inside the strip.
        renderScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (referenceFitPending) fitReferenceStrip()
            }
        })

        // First activation of a not-yet-rendered extra tab (brief step 4). addExtraTab already marks its id as
        // requested before this can fire for it, so in practice this is a defensive fallback, not the primary
        // trigger — but it is what makes switching to an unrendered tab always safe.
        viewTabs.addChangeListener {
            val component = viewTabs.selectedComponent
            val id = extraScrolls.entries.firstOrNull { it.value === component }?.key
            if (id != null && extraRequested.add(id)) {
                comparisonViews.views.firstOrNull { it.id == id }?.let { renderInto(it) }
            }
            // PG6-8: a plain tab switch (no model change at all) can still change what Properties should do —
            // Original's AS picker vs. re-rendering a copy — so the actions bar is rebuilt here too, not just
            // from show()/a model change.
            updateActionsBar(currentEntry)
        }
    }

    /** The render pipeline's entry point: shows one published [RenderResultView]. */
    fun show(view: RenderResultView, entry: PreviewEntry?) {
        referenceTasks = emptyList()
        show(view, entry, strip = null)
    }

    /**
     * Shows [entry]'s committed reference PNGs (spec D6/D7) — the snapshot path, which never goes through
     * [com.devomer.previewgallery.render.RenderPipeline] at all (spec D8), so it is its own entry point rather
     * than a state the pipeline publishes. [images] must already be decoded: this runs on the EDT, and reading
     * PNGs off disk does not belong there (the caller decodes off the EDT).
     *
     * [skipped] names the variants whose PNG could not be decoded; they are reported in the strip's tooltip and
     * the variants that did decode still show (spec's error-handling table). An empty [images] is
     * [RenderState.NO_REFERENCE] — whether because nothing is committed yet or because every variant failed.
     */
    fun showReference(
        entry: PreviewEntry,
        images: List<ReferenceStripView.LabelledImage>,
        skipped: List<String>,
        tasks: List<String> = emptyList(),
    ) {
        referenceTasks = tasks
        if (images.isEmpty()) {
            show(RenderResultView(RenderState.NO_REFERENCE, null, entry.moduleName), entry, strip = null)
            return
        }
        val strip = ReferenceStripView(images)
        if (skipped.isNotEmpty()) {
            strip.toolTipText = PreviewGalleryBundle.message("render.referenceUnreadable", skipped.joinToString(", "))
        }
        show(RenderResultView(RenderState.REFERENCE, null, entry.moduleName), entry, strip)
    }

    /** [strip] is non-null only on the [RenderState.REFERENCE] path ([showReference]): [RenderResultView] is the
     *  render pipeline's own type and deliberately carries no Swing, so the strip travels as its own argument
     *  instead of inside it. */
    private fun show(view: RenderResultView, entry: PreviewEntry?, strip: ReferenceStripView?) {
        // PG6-4/V3: a genuinely new selection (including to/from nothing selected) drops every comparison-view
        // extra and frees its image; another state update for the SAME entry (e.g. a retry's RENDERING -> LIVE,
        // or a picker-triggered re-render) must leave them alone. Compared by id, not reference, so a fresh
        // PreviewEntry instance re-resolved for the same preview still counts as "unchanged" for this guard —
        // but currentEntry itself is still refreshed unconditionally below, so a same-id update never leaves it
        // pinned to the first PreviewEntry instance ever seen for that preview.
        if (entry?.id != currentEntry?.id) {
            clearComparisonExtras()
        }
        currentEntry = entry
        activeState = view.state
        centerPanel.removeAll()
        renderView.clearContent()
        // Whatever a previous REFERENCE state put in renderScroll's viewport, renderView goes back in before this
        // state decides what to show — otherwise a live render would draw behind a strip that is still installed.
        // The REFERENCE branch below then replaces it again with its own strip.
        clearReferenceStrip()
        when (view.state) {
            RenderState.IDLE -> center(idle())
            RenderState.RENDERING -> center(JBLabel(PreviewGalleryBundle.message("render.rendering")))
            RenderState.LIVE -> showImage(view.outcome as? RenderOutcome.Success)
            RenderState.NEEDS_BUILD -> center(JBLabel(PreviewGalleryBundle.message("render.building")))
            RenderState.FAILED -> center(failed(view.outcome as? RenderOutcome.Failure, entry))
            RenderState.UNSUPPORTED -> center(unsupported(view.outcome as? RenderOutcome.Unsupported, entry))
            // [showReference] is the only caller that publishes REFERENCE and it always hands over a strip; a
            // missing one would mean there is nothing to show, which is the no-reference state by definition.
            RenderState.REFERENCE -> if (strip != null) showReferenceStrip(strip, entry) else center(noReference(entry))
            RenderState.NO_REFERENCE -> center(noReference(entry))
        }
        updateActionsBar(entry)
        revalidate(); repaint()
    }

    /** Rebuilds the persistent top-right actions bar as a native icon [com.intellij.openapi.actionSystem.ActionToolbar]
     *  (PG5-3, iconified): zoom/fit/hand-tool/export controls appear whenever there is a live render image; Properties
     *  is appended when it has somewhere to go for the *active* tab (PG6-8 — see below). Rebuilt per [show] (so the
     *  Properties anchor tracks the current entry and the toolbar reflects the current live/idle state) and per
     *  [viewTabs] tab change (so a plain switch between Original and a copy — no model change at all — still keeps
     *  Properties' target current). */
    private fun updateActionsBar(entry: PreviewEntry?) {
        actionsBar.removeAll()
        val group = DefaultActionGroup()
        val strip = referenceStrip
        if (strip != null) {
            // PG13-12: a reference strip gets the same zoom ladder and the same export controls as a render, all
            // driving the strip's one shared scale (spec D7). No hand tool — the strip is a plain component whose
            // panning the scroll pane already does — and neither comparison views nor Properties, since both
            // exist to re-render, and a snapshot is never rendered (spec D8).
            group.add(ZoomOutAction())
            group.add(ZoomInAction())
            group.add(FitAction())
            group.add(ActualSizeAction())
            group.addSeparator()
            group.add(SavePngAction())
            group.add(CopyImageAction())
        } else if (renderView.rawImage() != null) {
            group.add(ZoomOutAction())
            group.add(ZoomInAction())
            group.add(FitAction())
            group.add(ActualSizeAction())
            group.addSeparator()
            group.add(HandToolAction())
            group.addSeparator()
            group.add(SavePngAction())
            group.add(CopyImageAction())
            // PG6-4: a comparison view needs a live Original image to duplicate, and the device-override render
            // path itself — probed once, up front, by the owner; never AS-internal in this file.
            if (deviceOverrideAvailable) {
                group.addSeparator()
                group.add(AddViewAction())
            }
        }
        // PG6-8: Properties is context-aware (brief step 3) — with Original active it needs the AS picker
        // ([propertiesAvailable]); with a copy active it re-renders that copy instead (PG6-9), which needs only
        // the view-override render capability, not the picker. Never both, never neither once one of the two is
        // actually available — a build missing the relevant capability simply never gets the control, matching
        // every other capability gate in this class (never a dead control). Never for a snapshot: both branches
        // end in a render, and a `@SnapshotPreviews`-marked function has no `@Preview` entry for the picker to
        // resolve either. Gated on [showingSnapshot], NOT on the strip above: NO_REFERENCE is a snapshot with no
        // strip, and testing the strip there left Properties as the toolbar's only control, bound to a snapshot.
        val activeCopy = activeComparisonView()
        val originalActive = activeCopy == null || activeCopy.id == ComparisonViewList.ORIGINAL_ID
        val propertiesGated = if (originalActive) propertiesAvailable else deviceOverrideAvailable
        if (!showingSnapshot && propertiesGated && entry != null) {
            if (group.childrenCount > 0) group.addSeparator()
            group.add(PropertiesAction(entry))
        }
        lastActionGroup = group
        if (group.childrenCount > 0) {
            val toolbar = ActionManager.getInstance().createActionToolbar(ACTIONS_PLACE, group, true)
            // The target component must always be SHOWING: the platform silently refuses to perform a toolbar
            // action whose target is hidden ("Action is not performed because target component is not showing").
            // Original's [renderView] stops showing the moment a comparison tab is selected — JTabbedPane hides
            // the unselected tabs — which killed every toolbar action on a copy tab. This panel is showing
            // whenever the toolbar is, and these actions read their state from it directly rather than from the
            // action DataContext, so it is the correct target.
            toolbar.targetComponent = this
            toolbar.component.isOpaque = false
            actionsBar.add(toolbar.component)
            actionsBar.isVisible = true
        } else {
            actionsBar.isVisible = false
        }
        // This method swaps the bar's children (removeAll + add), which only marks the container invalid — nothing
        // lays the new toolbar out until someone revalidates it, and an unlaid-out toolbar has zero-sized buttons
        // that swallow every click while the previous toolbar's pixels stay on screen. show() happens to revalidate
        // the whole panel afterwards, but the tab-change path does not, so a rebuild triggered by switching to (or
        // adding) a comparison view produced a dead toolbar. Revalidating here covers every call site.
        actionsBar.revalidate()
        actionsBar.repaint()
    }

    /** The ZoomableRenderView the user is currently looking at: the selected extra tab's view, else Original's
     *  [renderView]. The toolbar's zoom/fit/hand-tool/export actions target THIS so they act on the visible tab
     *  (not always Original). Falls back to [renderView] whenever no extra tab owns the selected component. */
    private fun activeView(): ZoomableRenderView {
        val selected = viewTabs.selectedComponent
        val id = extraScrolls.entries.firstOrNull { it.value === selected }?.key
        return if (id != null) extraViews[id] ?: renderView else renderView
    }

    /** The [ComparisonView] backing the currently visible tab (PG6-8, brief step 3): the same selected-component
     *  -> extra-id lookup [activeView] uses, resolved against the pure model instead of a Swing widget. Null for
     *  Original (which has no entry in [extraScrolls] at all, by design — see the class doc) — callers treat a
     *  null result exactly like an explicit [ComparisonViewList.ORIGINAL_ID], never separately. */
    private fun activeComparisonView(): ComparisonView? {
        val selected = viewTabs.selectedComponent
        val id = extraScrolls.entries.firstOrNull { it.value === selected }?.key ?: return null
        return comparisonViews.views.firstOrNull { it.id == id }
    }

    private inner class ZoomOutAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.zoomOut"), null, AllIcons.General.ZoomOut,
    ) {
        override fun actionPerformed(e: AnActionEvent) { zoomBy(ZoomMath::stepOut) }
    }

    private inner class ZoomInAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.zoomIn"), null, AllIcons.General.ZoomIn,
    ) {
        override fun actionPerformed(e: AnActionEvent) { zoomBy(ZoomMath::stepIn) }
    }

    private inner class FitAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.fit"), null, AllIcons.General.FitContent,
    ) {
        override fun actionPerformed(e: AnActionEvent) { fitActive() }
    }

    private inner class ActualSizeAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.actualSize"), null, AllIcons.General.ActualZoom,
    ) {
        override fun actionPerformed(e: AnActionEvent) { zoomBy { 1.0 } }
    }

    /** Moves whatever is on screen to `next(currentZoom)`: the reference strip's one shared scale (spec D7) while
     *  a snapshot is showing, else the active tab's live render. The same ladder drives both, so a snapshot and a
     *  render zoom by identical steps. */
    private fun zoomBy(next: (Double) -> Double) {
        if (referenceStrip != null) {
            setReferenceScale(next(referenceScale))
        } else {
            activeView().zoomFactor = next(activeView().zoomFactor)
        }
    }

    /** Fit, for whichever of the two is showing — the whole strip, not one image of it (spec D7). */
    private fun fitActive() {
        if (referenceStrip != null) fitReferenceStrip() else activeView().fitToViewport()
    }

    /** Hand-tool as a real toggle so the toolbar shows its pressed state; mirrors [ZoomableRenderView.handToolActive]. */
    private inner class HandToolAction : ToggleAction(
        PreviewGalleryBundle.message("render.handTool"), null, HAND_ICON,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean = activeView().handToolActive
        override fun setSelected(e: AnActionEvent, state: Boolean) { activeView().handToolActive = state }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SavePngAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.savePng"), null, AllIcons.ToolbarDecorator.Export,
    ) {
        override fun actionPerformed(e: AnActionEvent) { savePng() }
    }

    private inner class CopyImageAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.copyImage"), null, AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(e: AnActionEvent) { copyImage() }
    }

    /** ＋ Add view (PG6-8: the copy model): appends an ephemeral, **untouched** copy of Original — a default
     *  [ViewOverride] that therefore renders identically to it, no setting prompt of any kind. The user overrides
     *  it afterwards, per-tab, via Properties ([PropertiesAction]); the `@Preview` source itself is never touched.
     *  Only ever added to the toolbar when [deviceOverrideAvailable] (see [updateActionsBar]), so this action is
     *  never invoked on a build that cannot render an override. */
    private inner class AddViewAction : DumbAwareAction(
        PreviewGalleryBundle.message("render.addView"), null, AllIcons.General.Add,
    ) {
        override fun actionPerformed(e: AnActionEvent) { handleAddView() }
    }

    /** Properties, context-aware by active tab (PG6-8): with Original active, opens Android Studio's picker next
     *  to the clicked button exactly as before (spec P4); with a copy active, opens [onEphemeralProperties] —
     *  Android Studio's OWN picker again, but over that copy's in-memory [ViewOverride] instead of the `@Preview`
     *  source (PG6-10, design D4). The anchor is taken from the toolbar button that fired the action, falling
     *  back to the actions bar when the event carries no component. Only ever wired into a group when
     *  [updateActionsBar]'s gating already matches the active tab, so the branch taken here never disagrees with
     *  why this action was shown in the first place. */
    private inner class PropertiesAction(private val entry: PreviewEntry) : DumbAwareAction(
        PreviewGalleryBundle.message("render.properties"), null, AllIcons.General.Settings,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val anchor = e.inputEvent?.component ?: actionsBar
            val view = activeComparisonView()
            if (view == null || view.id == ComparisonViewList.ORIGINAL_ID) {
                onProperties(entry, RelativePoint(anchor, Point(0, anchor.height)))
            } else {
                onEphemeralProperties(entry, view.override, RelativePoint(anchor, Point(0, anchor.height))) { name, value ->
                    // Re-read the current override from the model on every edit, not the [view] closed over above:
                    // a picker session that edits more than one property must fold each edit onto the LATEST
                    // override, not the one captured when Properties was first clicked (which would silently
                    // discard every edit but the last).
                    val current = comparisonViews.views.firstOrNull { it.id == view.id } ?: return@onEphemeralProperties
                    comparisonViews.setOverride(view.id, current.override.with(name, value))
                    refreshExtraTabTitles()
                    comparisonViews.views.firstOrNull { it.id == view.id }?.let { renderInto(it) }
                }
            }
        }
    }

    private fun showImage(success: RenderOutcome.Success?) {
        val image = success?.image
        if (image == null) { center(JBLabel(PreviewGalleryBundle.message("render.failed"))); return }
        // Feed the hover/click overlay the plugin-owned view tree (empty when Feature B is unavailable — the
        // listeners then stay inert) and reset zoom to fit the current viewport.
        renderView.setContent(image, success.viewTree, success.dpi)
        // PG6-4: renderScroll directly (today's look) with no extras, or as viewTabs' Original tab once one exists.
        refreshLiveContainer()
        // The scroll pane is only now placed in the visible hierarchy, and add() does not lay it out synchronously,
        // so its viewport still reports a 0x0 extent. Validate the subtree first so the viewport has its real size,
        // then fit — otherwise the first render (before any later revalidate) would show at 100% instead of Fit.
        centerPanel.validate()
        renderView.fitToViewport()
    }

    // ── PG13-12: the reference-image strip a snapshot row shows (spec D7/D8) ──────────────────────────────

    /**
     * Installs [strip] as the view of the *existing* [renderScroll] — the same scroll pane a live render uses, so
     * the strip inherits its scrollbars and its panning without a second one — and fits it.
     *
     * [centerPanel] is validated first for exactly the reason [showImage] validates it: `add()` does not lay the
     * scroll pane out synchronously, so its viewport would still report a 0x0 extent and [fitReferenceStrip]
     * would have nothing to fit against. When it still has no size, the fit debt stays standing and the viewport
     * resize listener from `init` settles it (PG12-4).
     *
     * [renderScroll] goes straight into [centerPanel] rather than through [refreshLiveContainer]: a snapshot has
     * no comparison tabs — it is never rendered — and selecting it already cleared any that existed, since its
     * entry id necessarily differs from the preview's.
     *
     * Open file sits under the strip for the same reason [noReference] offers it: the images are the only thing
     * this state shows, so without it the snapshot's own source would be reachable from nowhere at all — a
     * snapshot row is not renderable (spec D8) and therefore has no Render/Properties route to its file either.
     * Navigation is not rendering, so offering it here does not weaken D8.
     */
    private fun showReferenceStrip(strip: ReferenceStripView, entry: PreviewEntry?) {
        referenceStrip = strip
        referenceScale = 1.0
        referenceFitPending = true
        // Before the viewport view is installed, not after: setScale is what assigns the strip its preferredSize,
        // and a scroll pane laid out around a 0x0 view shows nothing. fitReferenceStrip below replaces this
        // placeholder scale the moment the viewport has a size; when it does not, the strip is at least visible
        // at 100% until the resize listener settles the fit debt (referenceFitPending stays standing — this
        // assigns the strip's scale directly rather than going through setReferenceScale, which would clear it).
        strip.setScale(referenceScale)
        renderScroll.setViewportView(strip)
        centerPanel.add(renderScroll, BorderLayout.CENTER)
        if (entry != null) {
            centerPanel.add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
        }
        centerPanel.validate()
        fitReferenceStrip()
    }

    /** Drops the strip and its decoded images and puts [renderView] back in [renderScroll], so the next render
     *  shows there instead of behind a strip that is no longer wanted. A no-op when no strip is installed. */
    private fun clearReferenceStrip() {
        if (referenceStrip == null) return
        referenceStrip = null
        referenceScale = 1.0
        referenceFitPending = false
        renderScroll.setViewportView(renderView)
    }

    /** Fits the whole strip into [renderScroll]'s viewport at one shared scale (spec D7). Leaves
     *  [referenceFitPending] standing when the viewport has no size yet — assigning a scale here would settle the
     *  debt with a meaningless 1.0, which is the same reason [ZoomableRenderView.fitToViewport] returns early. */
    private fun fitReferenceStrip() {
        val strip = referenceStrip ?: return
        val extent = renderScroll.viewport.extentSize
        if (extent.width <= 0 || extent.height <= 0) return
        setReferenceScale(strip.fitScale(extent.width, extent.height))
    }

    /** Applies one shared scale to the whole strip, bounded like a render's zoom factor is, and settles the fit
     *  debt: either this IS the fit, or the user picked a zoom themselves and a later resize must not overwrite
     *  it — the same contract [ZoomableRenderView.zoomFactor]'s setter has (PG12-4). */
    private fun setReferenceScale(scale: Double) {
        val strip = referenceStrip ?: return
        referenceScale = scale.coerceIn(ZoomMath.MIN, ZoomMath.MAX)
        referenceFitPending = false
        strip.setScale(referenceScale)
    }

    // ── PG6-4: comparison-view tab strip ──────────────────────────────────────────────────────────────────

    /** Chooses what [centerPanel] should show for a live Original render: the raw [renderScroll] (today's look,
     *  byte-for-byte unchanged) when [comparisonViews] has no extras, or [viewTabs] — with [renderScroll]
     *  reparented in as its Original tab — once at least one exists. Does not revalidate/repaint; callers follow
     *  up as their own context needs (an immediate synchronous fit in [showImage], or a plain async repaint for
     *  an add/close that did not otherwise touch the Original render). */
    private fun refreshLiveContainer() {
        val target: JComponent = if (comparisonViews.views.size > 1) {
            ensureOriginalTabPresent()
            viewTabs
        } else {
            // Back down to Original-only: detach it from viewTabs so it is free to be re-added directly below.
            if (renderScroll.parent === viewTabs) viewTabs.remove(renderScroll)
            renderScroll
        }
        centerPanel.removeAll()
        centerPanel.add(target, BorderLayout.CENTER)
    }

    private fun ensureOriginalTabPresent() {
        if (viewTabs.indexOfComponent(renderScroll) < 0) {
            viewTabs.insertTab(PreviewGalleryBundle.message("render.originalView"), null, renderScroll, null, 0)
        }
    }

    /** Drops every comparison-view extra (PG6-4/V3): the pure model, every per-tab staleness token, and every
     *  Swing widget/image. Removing the extras from [viewTabs] (not just clearing this panel's own maps) is what
     *  actually releases the memory — a [ZoomableRenderView] still parented inside [viewTabs] would keep holding
     *  its image regardless of what these maps say. Safe to call when there is nothing to clear (a no-op).
     *  Bookkeeping is cleared before the Swing teardown so a change event the teardown itself provokes sees empty
     *  maps rather than dangling entries. */
    private fun clearComparisonExtras() {
        comparisonViews.clearExtras()
        extraGenerations.clear()
        extraRequested.clear()
        extraViews.clear()
        extraScrolls.clear()
        extraTabLabels.clear()
        for (i in viewTabs.tabCount - 1 downTo 0) {
            if (viewTabs.getComponentAt(i) !== renderScroll) viewTabs.removeTabAt(i)
        }
        if (renderScroll.parent === viewTabs) viewTabs.remove(renderScroll)
    }

    /** ＋ Add view (PG6-8, brief step 2 — the copy model): appends an **untouched** copy of Original (a default
     *  [ViewOverride], so it renders identically until the user overrides it) and requests its render
     *  immediately. A silent no-op once [comparisonViews] is at its cap ([ComparisonViewList.add] returns null)
     *  or if there is somehow no current entry — matching [ComparisonViewList]'s own "cap reached -> no-op"
     *  contract rather than surfacing an error for a control the user cannot actually misuse this way (the action
     *  is only ever on the toolbar when a live Original image already exists). */
    private fun handleAddView() {
        if (currentEntry == null) return
        val view = comparisonViews.add(ViewOverride()) ?: return
        addExtraTab(view)
        renderInto(view)
    }

    /** Builds one extra tab's widgets — a [ZoomableRenderView] + [JBScrollPane], a title header (see
     *  [extraTabHeader]) — wires click-to-source, and switches to it. Does not itself render; [handleAddView]
     *  requests that right after, matching the brief's wiring exactly. Marks [view]'s id as requested before
     *  switching tabs so [viewTabs]'s change listener does not also fire a redundant render for it. */
    private fun addExtraTab(view: ComparisonView) {
        // NOT `.apply { onNavigateToSource = { onNavigateToSource(it) } }`: inside that apply block the implicit
        // receiver is the new ZoomableRenderView itself, which also has an `onNavigateToSource` property, so the
        // inner call would shadow-resolve to itself (infinite recursion) instead of this panel's own callback.
        // Plain dot-assignment keeps the only implicit receiver in scope this@PreviewRenderPanel, exactly like
        // the Original renderView's wiring in init{} above.
        val extraView = ZoomableRenderView()
        extraView.onNavigateToSource = { onNavigateToSource(it) }
        val scroll = JBScrollPane(extraView)
        extraViews[view.id] = extraView
        extraScrolls[view.id] = scroll

        refreshLiveContainer() // ensure viewTabs exists (Original already its tab 0) before indexing into it below
        // view was just appended, so its ordinal is simply its position in the current list; looked up rather
        // than assumed (e.g. views.size - 1) so this stays correct even if that ever stops being true.
        val ordinal = comparisonViews.views.indexOfFirst { it.id == view.id }
        viewTabs.addTab(ViewTitle.of(view, ordinal), scroll)
        val index = viewTabs.indexOfComponent(scroll)
        viewTabs.setTabComponentAt(index, extraTabHeader(view, ordinal))
        extraRequested.add(view.id)
        viewTabs.selectedIndex = index

        // Same rationale as showImage's PG5-6 fix: the new tab's viewport is only laid out once this subtree is
        // validated, and its render (async, via renderInto) reads that viewport's real size the moment content
        // arrives. Validating now — while this tab is the selected one, so the tabbed pane's UI delegate actually
        // sizes it — means that read is never racing an as-yet-uncommitted layout.
        centerPanel.validate()
        centerPanel.repaint()
    }

    /** The small header a [viewTabs] extra tab carries in place of a plain title (PG6-8, brief step 2): just the
     *  tab's own title (from [ViewTitle], tracked live in [extraTabLabels] — see [refreshExtraTabTitles]) and a
     *  close button. No per-tab device selector any more — a copy's override comes from Properties, driven by
     *  Android Studio's own picker over an in-memory model (PG6-10; see [onEphemeralProperties]). */
    private fun extraTabHeader(view: ComparisonView, ordinal: Int): JBPanel<*> {
        val label = JBLabel(ViewTitle.of(view, ordinal))
        extraTabLabels[view.id] = label
        val close = InplaceButton("", AllIcons.Actions.Close) { closeExtraTab(view.id) }
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(label)
            add(close)
        }
    }

    /** Re-derives every extra tab's visible title from the current model (PG6-8, brief steps 2/3): an override
     *  change can turn an untouched copy's "View N" into a summary of its overrides (or back to it, if reset to
     *  default), and closing a tab shifts every later tab's ordinal, which the "View N" fallback depends on
     *  ([ViewTitle.of]) — so every mutation that could affect a title re-derives all of them, from
     *  [comparisonViews] itself, rather than trying to track which single one actually changed. Cheap: at most
     *  [ComparisonViewList.DEFAULT_MAX_EXTRAS] label-text assignments. A no-op for any id with no header built
     *  yet (there is none, in practice — every extra gets one in [addExtraTab] before it can be selected). */
    private fun refreshExtraTabTitles() {
        comparisonViews.views.forEachIndexed { ordinal, view ->
            if (view.id == ComparisonViewList.ORIGINAL_ID) return@forEachIndexed
            extraTabLabels[view.id]?.text = ViewTitle.of(view, ordinal)
        }
    }

    /** Close (brief step 3): drops the model entry, its staleness token, and its widgets/image, then removes the
     *  tab itself — freeing the [ZoomableRenderView]'s image (AC4) — and collapses back to the plain
     *  [renderScroll] if that was the last extra. */
    private fun closeExtraTab(id: Int) {
        comparisonViews.close(id)
        extraGenerations.remove(id)
        extraRequested.remove(id)
        extraViews.remove(id)
        extraTabLabels.remove(id)
        val scroll = extraScrolls.remove(id)
        if (scroll != null) {
            val index = viewTabs.indexOfComponent(scroll)
            if (index >= 0) viewTabs.removeTabAt(index)
        }
        // PG6-8: closing a middle tab shifts every later tab's ordinal, which an untouched neighbour's
        // "View N" title depends on — refresh every survivor's title, not just drop this one's own bookkeeping.
        refreshExtraTabTitles()
        refreshLiveContainer()
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    /**
     * Renders [view] with its own [ViewOverride] via [onRequestVariant] (brief step 4) and delivers the result to
     * its tab, off the EDT throughout: [onRequestVariant] is wired to
     * [com.devomer.previewgallery.render.RenderPipeline.renderVariant], which runs on a background executor and
     * only ever calls back on the EDT. [view]'s override may be entirely default (PG6-8: a freshly-added,
     * untouched copy) — that is not a reason to skip rendering, it is exactly the "renders identically to
     * Original" case (AC1); a default [ViewOverride] renders exactly like Original's own values, and a
     * non-default one is applied onto the render by `RenderModelResolver.applyOverride` (PG6-10). Guards
     * staleness with a fresh per-id generation token — if [view]'s id has moved on to a newer token by the time
     * the result arrives (another [renderInto] call for the same id, e.g. Properties re-rendering it again, or
     * the tab/entry was cleared) the result is dropped rather than applied. A no-op if there is no current entry
     * or the tab was already closed before this call (`view.id !in extraViews` — true for Original, which never
     * has an entry there, and for any id [closeExtraTab]/[clearComparisonExtras] already tore down) — neither of
     * which should ever actually reach here.
     */
    private fun renderInto(view: ComparisonView) {
        val entry = currentEntry ?: return
        if (view.id !in extraViews) return
        val generation = (extraGenerations[view.id] ?: 0) + 1
        extraGenerations[view.id] = generation
        onRequestVariant(entry, view.override) { outcome ->
            if (extraGenerations[view.id] != generation) return@onRequestVariant
            val extraView = extraViews[view.id] ?: return@onRequestVariant
            val scroll = extraScrolls[view.id] ?: return@onRequestVariant
            when (outcome) {
                is RenderOutcome.Success -> {
                    scroll.setViewportView(extraView)
                    // PG12-3: per view, not once for the panel — a comparison tab may render a different device,
                    // and therefore a different density, than Original.
                    extraView.setContent(outcome.image, outcome.viewTree, outcome.dpi)
                }
                else -> showExtraFailed(view, scroll)
            }
        }
    }

    /** Failure/retry affordance inside one extra tab (brief step 4), reusing the same `render.failed` wording as
     *  Original's own [failed] and `render.render` for the retry link. Temporarily swaps the scroll pane's
     *  viewport view away from the [ZoomableRenderView]; a successful retry in [renderInto] swaps it back. */
    private fun showExtraFailed(view: ComparisonView, scroll: JBScrollPane) {
        scroll.setViewportView(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBLabel(PreviewGalleryBundle.message("render.failed")), BorderLayout.NORTH)
                add(ActionLink(PreviewGalleryBundle.message("render.render")) { renderInto(view) }, BorderLayout.CENTER)
            },
        )
    }

    /**
     * What Save PNG / Copy act on: the reference strip while a snapshot is showing, else the active tab's raw
     * render (PG5-3/V2, PG6-4/D10 — the *active* tab, never always Original). Null when there is nothing to
     * export, which both callers treat as a no-op.
     *
     * Deliberately not symmetric with a render's `rawImage()`, because the two are not symmetric: a render's raw
     * image exists at native resolution independently of the zoom, while the strip's pixels only exist as drawn —
     * so what is exported is the strip exactly as it is on screen, labels and all, which is also the artefact
     * worth sharing (`phone` next to `small`). Actual size (100%) exports the references at native resolution.
     */
    private fun exportImage(): BufferedImage? {
        val strip = referenceStrip ?: return activeView().rawImage()
        // JComponent.paint is a no-op for a zero-sized component, so a strip not laid out yet has nothing to
        // export — the same no-op posture as a missing render image.
        if (strip.width <= 0 || strip.height <= 0) return null
        val image = BufferedImage(strip.width, strip.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            strip.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** Writes what [exportImage] resolves to — the raw render (no overlay), or the reference strip — to a
     *  user-chosen PNG file (PG5-3/V2). No-op if there is nothing to export, or if the user cancels the dialog. */
    private fun savePng() {
        val image = exportImage() ?: return
        val descriptor = FileSaverDescriptor(PreviewGalleryBundle.message("render.savePng"), "", "png")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as VirtualFile?, "preview.png") ?: return
        if (!RenderImageExporter.savePng(image, wrapper.file)) {
            notify(PreviewGalleryBundle.message("render.saveFailed"))
        }
    }

    /** Copies what [exportImage] resolves to — the raw render (no overlay), or the reference strip — to the system
     *  clipboard (PG5-3/V2). No-op if there is nothing to export. */
    private fun copyImage() {
        val image = exportImage() ?: return
        try {
            RenderImageExporter.copyToClipboard(image)
        } catch (e: Exception) {
            notify(PreviewGalleryBundle.message("render.copyFailed"))
        }
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Compose Preview Gallery")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    /** Nothing selected. Quiet by design: not an error, so no styling and no action. */
    private fun idle(): JBLabel = JBLabel(PreviewGalleryBundle.message("render.idle")).apply {
        foreground = UIUtil.getInactiveTextColor()
    }

    /** The Render button now appears only here, as a retry — selecting a stale module builds it automatically
     *  (D3/B3), so there is nothing left for the button to do on the automatic [RenderState.NEEDS_BUILD] path. */
    private fun failed(outcome: RenderOutcome.Failure?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel("${PreviewGalleryBundle.message("render.failed")}: ${outcome?.message ?: ""}"), BorderLayout.NORTH)
        if (entry != null) {
            add(ActionLink(PreviewGalleryBundle.message("render.render")) { onRender(entry) }, BorderLayout.CENTER)
            add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
        }
    }

    private fun unsupported(outcome: RenderOutcome.Unsupported?, entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JBLabel(outcome?.reason ?: PreviewGalleryBundle.message("render.unsupported")), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }

    /**
     * A snapshot with nothing committed to show (spec D10): the message names the Gradle task that generates the
     * references, since "no images" without the fix is not actionable — and names the task of the variants that
     * were actually found, since a flavoured module does not have `updateDebugScreenshotTest`. Offers Open file,
     * and deliberately **not** Render the way [failed] does — a snapshot is never rendered at all (spec D8), so a
     * Render button here would be a control that cannot do what it says.
     */
    private fun noReference(entry: PreviewEntry?): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val message = if (referenceTasks.isEmpty()) {
            PreviewGalleryBundle.message("render.noReference")
        } else {
            PreviewGalleryBundle.message("render.noReference.task", referenceTasks.joinToString(", "))
        }
        add(JBLabel(message), BorderLayout.NORTH)
        if (entry != null) add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { onOpenFile(entry) }, BorderLayout.SOUTH)
    }

    private fun center(component: javax.swing.JComponent) {
        centerPanel.add(JBPanel<JBPanel<*>>(BorderLayout()).apply { add(component, BorderLayout.CENTER) }, BorderLayout.CENTER)
    }

    private companion object {
        private const val ACTIONS_PLACE = "PreviewGalleryRenderToolbar"
        private val HAND_ICON: Icon = IconLoader.getIcon("/icons/hand.svg", PreviewRenderPanel::class.java)
    }
}
