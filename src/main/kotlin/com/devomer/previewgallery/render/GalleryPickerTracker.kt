// ComposePickerTracker is `internal` in Kotlin visibility terms inside its own module (compose-designer), even
// though it is `public` at the JVM bytecode level (verified with javap — see the Task 2 report). Kotlin's
// compiler enforces the *source* visibility across module boundaries regardless of the bytecode-level modifier,
// so implementing the interface from this plugin's own Kotlin module needs this suppression; nothing about the
// runtime call is affected; every AS-internal call this class participates in is still guarded by
// PreviewPickerBridge's Exception/LinkageError catch (design §5).
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.devomer.previewgallery.render

import com.android.sdklib.devices.Device
import com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker
import com.google.wireless.android.sdk.stats.EditorPickerEvent

/**
 * Satisfies [ComposePickerTracker] — the picker's usage-analytics collaborator (design §2) — without reporting
 * anything to Google. [pickerShown], [pickerClosed] and [logUsageData] are pure no-ops; [registerModification]
 * is the one member with any effect, forwarding "a value changed" to [onModification] so the gallery can
 * re-render (spec P3's primary signal — Task 3 wires this to [RenderPipeline]).
 *
 * The proto value and [Device] parameters exist purely for Google's analytics pipeline; this tracker
 * deliberately ignores both instead of interpreting them.
 *
 * Spec P2: `EditorPickerEvent...PreviewPickerValue` — the analytics proto type named in
 * [ComposePickerTracker.registerModification]'s signature — resolved and compiled cleanly. It lives in
 * `libstudio.proto.jar` under the already-declared `org.jetbrains.android` bundled-plugin dependency, so no
 * reflection workaround was needed (see the Task 2 report for the exact classpath verification).
 */
class GalleryPickerTracker(private val onModification: () -> Unit) : ComposePickerTracker {

    override fun pickerShown() = Unit

    override fun pickerClosed() = Unit

    override fun registerModification(
        name: String,
        value: EditorPickerEvent.EditorPickerAction.PreviewPickerModification.PreviewPickerValue,
        device: Device?,
    ) {
        onModification()
    }

    override fun logUsageData() = Unit
}
