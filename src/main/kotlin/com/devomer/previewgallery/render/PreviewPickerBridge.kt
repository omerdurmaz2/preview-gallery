// PsiPickerManager and PreviewPickerPropertiesModel are `internal` in Kotlin visibility terms inside their own
// module (compose-designer), even though they are `public` at the JVM bytecode level (verified with javap — see
// the Task 2 report). Kotlin enforces the *source* visibility across module boundaries regardless of the
// bytecode modifier, so calling them from this plugin's own Kotlin module needs this suppression. Nothing about
// the runtime call is affected — every use is still inside the try/catch guarding Exception and LinkageError
// (design §5), so a future AS build that actually removes or renames these members degrades to a logged `false`
// exactly as it would if the compiler had never let us reference them by name.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil

// ── Android Studio internal picker API. Isolated here + in GalleryPickerTracker only (design §3), alongside
//    the existing LiveRenderer/RenderModelResolver render pair. ──
import com.android.tools.idea.compose.pickers.PsiPickerManager
import com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel
import com.intellij.openapi.ui.popup.Balloon

/**
 * Opens Android Studio's own `@Preview` property picker for a [PreviewEntry], pre-filled with that preview's
 * current `@Preview` arguments (design §2, §4). Together with [GalleryPickerTracker] this is the ONLY home for
 * the picker's AS-internal API coupling.
 *
 * Every AS-internal call is guarded against [Exception] and [LinkageError] (design §5): a failure is logged once
 * and nothing throws into the UI — the button simply does nothing that time instead of crashing the gallery.
 *
 * ## PG3-7 — off the EDT
 *
 * [showPicker] used to do everything synchronously on the EDT (the button's own `ActionListener`): resolving the
 * annotation, and — the expensive part — `PreviewPickerPropertiesModel.fromPreviewElement(...)`, which builds the
 * whole property model including the Device / apiLevel / locale enumerations. That is what made this feel far
 * slower than the editor's own gutter picker, which does the equivalent work off the EDT.
 *
 * Resolving and building the model now run in [buildModelAndShow], off the EDT under a read action; only
 * [PsiPickerManager.show] itself — [showPopup] — still runs on the EDT, once that model is ready. Since the popup
 * anchor ([at]) is a live Swing component and the async hop means real time passes before it is used, [showPopup]
 * re-checks [java.awt.Component.isShowing] first: the tool window can close, or the panel can rebuild around a
 * different selection, while resolution/model-building is in flight, and anchoring a popup to a hidden or
 * removed component would be wrong (or could throw).
 *
 * One consequence: [showPicker] can no longer return, synchronously, whether the picker was actually shown — see
 * its own doc.
 *
 * ## Spec P1 — which PSI element the pointer must hold
 *
 * Settled by disassembling `PreviewPickerPropertiesModel$Companion.fromPreviewElement` against the Android
 * Studio 253 `design-tools.jar` (javap -c), not just inferred from the constructor hint. The bytecode does
 * exactly this, in order:
 * 1. `val annotationEntry = pointer?.element as? KtAnnotationEntry` — any other element type (e.g. the annotated
 *    `KtNamedFunction`) makes this null.
 * 2. If null: `Logger.getInstance(PsiCallPropertiesModel::class).error("Non-null value is expected for
 *    annotation entry")`, immediately followed by an unconditional `Intrinsics.checkNotNull(null)`, i.e.
 *    `throw IllegalStateException("Required value was null.")` — before a `PreviewPropertiesProvider` (whose own
 *    constructor takes a non-null `KtAnnotationEntry`) is ever built.
 *
 * So the pointer must hold the `@Preview` [org.jetbrains.kotlin.psi.KtAnnotationEntry] itself. Passing anything
 * else does not degrade gracefully inside AS's own code — it logs an IDE-level error and throws — which is
 * exactly why every call in this class is wrapped (design §5); such a throw is caught below and turned into a
 * quiet no-op, never a crash.
 */
class PreviewPickerBridge(private val project: Project) {

    fun isAvailable(): Boolean = RenderApiProbe.isPickerAvailable()

    /**
     * Shows the picker for [entry], anchored at [at] (spec P4). [onModification] is forwarded from
     * [GalleryPickerTracker.registerModification]; it fires on the EDT each time the user changes a value in the
     * picker (Task 3 wires this to a re-render).
     *
     * Returns `false` immediately — showing nothing — only when the picker API is unavailable ([isAvailable]);
     * that synchronous short-circuit is unchanged by PG3-7. Otherwise this returns `true` once resolving the
     * `@Preview` annotation and building the picker's property model have been dispatched to run off the EDT
     * ([buildModelAndShow]) — not that the picker was actually shown. That can still silently not happen
     * afterwards (the annotation no longer resolves, the anchor stopped showing, or any AS-internal call
     * failed); every such case is logged, never thrown into the UI, matching the guarantee [showPicker] made
     * before this fix, just no longer observable through this method's return value now that the work it
     * describes happens asynchronously.
     */
    fun showPicker(entry: PreviewEntry, at: RelativePoint, onModification: () -> Unit): Boolean {
        if (!isAvailable()) return false
        // Captured here, on the EDT (the button's own ActionListener), not on the background thread below —
        // ModalityState.defaultModalityState() reflects "current modality," which is only meaningful read from
        // the EDT (the same reason PG3-6 captures it in RenderPipeline.render before hopping off the EDT).
        val modality = ModalityState.defaultModalityState()
        AppExecutorUtil.getAppExecutorService().execute { buildModelAndShow(entry, at, modality, onModification) }
        return true
    }

    private class Resolved(val pointer: SmartPsiElementPointer<PsiElement>, val module: Module)

    /**
     * Off the EDT: resolves the `@Preview` annotation and builds the property model in one read action, then
     * hops to the EDT — at [modality], captured before this background hop — to show the popup ([showPopup]).
     *
     * Every AS-internal call anywhere in this chain is guarded against [Exception] and [LinkageError]
     * (design §5); nothing here can throw back into the button's `ActionListener`, which already returned by the
     * time this runs on the background executor.
     */
    private fun buildModelAndShow(
        entry: PreviewEntry,
        at: RelativePoint,
        modality: ModalityState,
        onModification: () -> Unit,
    ) {
        try {
            val model = ReadAction.compute<PreviewPickerPropertiesModel?, RuntimeException> {
                val resolved = resolve(entry) ?: return@compute null
                val tracker = GalleryPickerTracker(onModification)
                PreviewPickerPropertiesModel.fromPreviewElement(project, resolved.module, resolved.pointer, tracker)
            }
            if (model == null) {
                thisLogger().info(
                    "Could not resolve the @Preview annotation or module for " +
                        "${entry.indexed.composableFqn}; picker not shown",
                )
                return
            }
            ApplicationManager.getApplication().invokeLater({ showPopup(entry, at, model) }, modality)
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not open the preview picker for ${entry.indexed.composableFqn}", e)
        } catch (e: LinkageError) {
            thisLogger().warn("Preview picker API is incompatible with this IDE build", e)
        }
    }

    /**
     * Re-resolves the `@Preview` annotation (via [PreviewAnnotationLocator], which requires a read action) and
     * the owning [Module] (the same lookup [RenderModelResolver] uses), and wraps the annotation in a
     * [SmartPsiElementPointer] so it survives past the read action (design P1: the pointer's element type
     * parameter is pinned to [PsiElement] — matching `fromPreviewElement`'s `SmartPsiElementPointer<PsiElement>`
     * parameter exactly — even though the underlying element is the more specific `KtAnnotationEntry`).
     *
     * Must be called from within a read action — unlike before PG3-7, this no longer takes its own: the caller
     * ([buildModelAndShow]) now shares one read action across resolving *and* building the property model, so
     * a PSI change landing between the two steps cannot resolve the annotation and then build the model against
     * a since-changed file.
     */
    private fun resolve(entry: PreviewEntry): Resolved? {
        val annotation = PreviewAnnotationLocator.findPreviewAnnotation(project, entry) ?: return null
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file) ?: return null
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer<PsiElement>(annotation)
        return Resolved(pointer, module)
    }

    /**
     * On the EDT, once [model] is ready. [at]'s anchor component may have stopped showing while
     * [buildModelAndShow] was resolving/building the model off the EDT — the tool window could have closed, or
     * the panel could have rebuilt around a different selection — so this is skipped rather than anchoring a
     * popup to a hidden or removed component (PG3-7).
     */
    private fun showPopup(entry: PreviewEntry, at: RelativePoint, model: PreviewPickerPropertiesModel) {
        if (!at.component.isShowing) return
        try {
            PsiPickerManager.show(at.screenPoint, entry.indexed.displayName, model, Balloon.Position.below)
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not open the preview picker for ${entry.indexed.composableFqn}", e)
        } catch (e: LinkageError) {
            thisLogger().warn("Preview picker API is incompatible with this IDE build", e)
        }
    }
}
