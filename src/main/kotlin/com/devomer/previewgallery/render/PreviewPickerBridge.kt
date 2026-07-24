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
 * and [showPicker] returns false rather than throwing into the UI — the button simply does nothing that time
 * instead of crashing the gallery.
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
 * quiet `false`, never a crash.
 */
class PreviewPickerBridge(private val project: Project) {

    fun isAvailable(): Boolean = RenderApiProbe.isPickerAvailable()

    /**
     * Shows the picker for [entry], anchored at [at] (spec P4). Returns false — and shows nothing — when the
     * picker API is unavailable, the `@Preview` annotation can no longer be resolved (e.g. the file changed
     * underneath the selection), or any AS-internal call fails.
     *
     * [onModification] is forwarded from [GalleryPickerTracker.registerModification]; it fires on the EDT each
     * time the user changes a value in the picker (Task 3 wires this to a re-render).
     */
    fun showPicker(entry: PreviewEntry, at: RelativePoint, onModification: () -> Unit): Boolean {
        if (!isAvailable()) return false
        return try {
            val resolved = resolve(entry)
            if (resolved == null) {
                thisLogger().info(
                    "Could not resolve the @Preview annotation or module for " +
                        "${entry.indexed.composableFqn}; picker not shown",
                )
                return false
            }
            val tracker = GalleryPickerTracker(onModification)
            val model = PreviewPickerPropertiesModel.fromPreviewElement(
                project,
                resolved.module,
                resolved.pointer,
                tracker,
            )
            PsiPickerManager.show(at.screenPoint, entry.indexed.displayName, model, Balloon.Position.below)
            true
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not open the preview picker for ${entry.indexed.composableFqn}", e)
            false
        } catch (e: LinkageError) {
            thisLogger().warn("Preview picker API is incompatible with this IDE build", e)
            false
        }
    }

    private class Resolved(val pointer: SmartPsiElementPointer<PsiElement>, val module: Module)

    /**
     * Re-resolves the `@Preview` annotation (via [PreviewAnnotationLocator], which requires a read action) and
     * the owning [Module] (the same lookup [RenderModelResolver] uses) under a single read action, and wraps the
     * annotation in a [SmartPsiElementPointer] so it survives past this read action (design P1: the pointer's
     * element type parameter is pinned to [PsiElement] — matching `fromPreviewElement`'s
     * `SmartPsiElementPointer<PsiElement>` parameter exactly — even though the underlying element is the more
     * specific `KtAnnotationEntry`).
     */
    private fun resolve(entry: PreviewEntry): Resolved? =
        ReadAction.compute<Resolved?, RuntimeException> {
            val annotation = PreviewAnnotationLocator.findPreviewAnnotation(project, entry) ?: return@compute null
            val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file) ?: return@compute null
            val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer<PsiElement>(annotation)
            Resolved(pointer, module)
        }
}
