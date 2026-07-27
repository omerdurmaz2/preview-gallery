package com.devomer.previewgallery.render

import com.intellij.openapi.diagnostic.thisLogger

/**
 * Reflectively verifies the Android Studio render API this plugin depends on is present with the expected
 * signatures on the running IDE build. Everything here is internal AS API, so a newer build can change it; the
 * probe turns that from a crash into a clean "renderer unavailable" (spec §5.3).
 */
object RenderApiProbe {

    private val required = listOf(
        "com.android.tools.idea.rendering.StudioRenderService" to listOf("getInstance"),
        "com.android.tools.rendering.RenderService" to listOf("taskBuilder"),
        // executeCallbacks is what lets the Compose runtime run its frame callbacks between the two render
        // passes; without it the captured frame is the uncomposed one (see LiveRenderer's class doc).
        "com.android.tools.rendering.RenderTask" to listOf("inflate", "render", "executeCallbacks", "dispose"),
        "com.android.tools.rendering.ExecuteCallbacksResult" to listOf("hasMoreCallbacks"),
        "com.android.tools.rendering.RenderResult" to listOf("processImageIfNotDisposed", "getRootViews", "dispose"),
        "com.android.tools.idea.rendering.AndroidFacetRenderModelModule" to emptyList(),
        "com.android.tools.preview.SingleComposePreviewElementInstance" to emptyList(),
    )

    private val pickerRequired = listOf(
        "com.android.tools.idea.compose.pickers.PsiPickerManager" to listOf("show"),
        "com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel" to emptyList(),
        "com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel\$Companion" to listOf("fromPreviewElement"),
        "com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker" to emptyList(),
    )

    // findPreviewElements is reflected by name only: it is a `suspend` function, so its real JVM signature carries
    // a trailing kotlin.coroutines.Continuation parameter this name-only check does not distinguish. A shape
    // change there still throws at the call site, which RenderModelResolver guards separately (PG4-2).
    private val configAwareRequired = listOf(
        "com.android.tools.idea.compose.preview.AnnotationFilePreviewElementFinder" to listOf("findPreviewElements"),
    )

    // PG4-3: the ViewInfo -> source-located ComposeViewInfo parser LiveRenderer uses to build the plugin-owned
    // PreviewViewNode tree. parseViewInfo is a Kotlin top-level function, so reflectively it lives as a static
    // method on the file's synthetic ComposeViewInfoParserKt class (Kotlin's standard file-facade convention).
    private val viewTreeRequired = listOf(
        "com.android.tools.idea.compose.preview.ComposeViewInfoParserKt" to listOf("parseViewInfo"),
        "com.android.tools.idea.compose.preview.ComposeViewInfo" to listOf("getSourceLocation", "getBounds", "getChildren"),
        "com.android.tools.idea.compose.preview.PxBounds" to listOf("getLeft", "getTop", "getRight", "getBottom"),
        "com.android.tools.idea.compose.preview.SourceLocation" to listOf("getFileName", "getLineNumber"),
    )

    // PG6-7 (widened from PG6-3's device-only probe): the calls RenderModelResolver's view-override block makes
    // to map a plugin-owned ViewConfig's three axes onto the render Configuration — device (task-3 report, V1 —
    // confirmed by javap on android.jar), night mode and font scale (task-7 report). Device itself is included as
    // a bare class-presence check since it is the type getDeviceById/setDevice exchange, even though its own
    // getId() is not reflected directly (AS's getDeviceById does that internally).
    private val viewOverrideRequired = listOf(
        "com.android.tools.idea.configurations.ConfigurationManager" to listOf("getDeviceById"),
        "com.android.tools.configurations.Configuration" to listOf("setDevice", "setNightMode", "setFontScale"),
        "com.android.sdklib.devices.Device" to emptyList(),
    )

    fun isAvailable(): Boolean = allPresent(required)

    /** Whether Android Studio's own @Preview property picker can be driven on this build (spec §5). */
    fun isPickerAvailable(): Boolean = allPresent(pickerRequired)

    /** Whether [RenderModelResolver] can ask Android Studio's own finder for each preview's real `@Preview`
     *  config (device/api/size/showSystemUi) instead of always using the default (PG4-2, spec V1). Independent of
     *  [isAvailable]: this path is a bonus on top of plain rendering, never a requirement for it — a build missing
     *  just this finder still renders every preview at the default configuration. */
    fun isConfigAwareAvailable(): Boolean = allPresent(configAwareRequired)

    /** Whether Android Studio's `ViewInfo` -> source-located Compose node parser is present on this build (PG4-3),
     *  checked by [LiveRenderer] before it attempts the raw-tree -> `PreviewViewNode` conversion. Independent of
     *  [isAvailable]: a build missing just this parser still renders images; only the view-tree (bounds +
     *  source-location) overlay for a later hit-testing task degrades to an empty list. */
    fun isViewTreeAvailable(): Boolean = allPresent(viewTreeRequired)

    /** Whether [RenderModelResolver] can map a plugin-owned `ViewConfig` (device, theme, font scale) onto an
     *  Android Studio `Device`/`Configuration` (PG6-7, widened from PG6-3's device-only probe; comparison views).
     *  Independent of [isAvailable]: a build missing just this capability still renders every preview at its
     *  config-aware values; a `viewConfig` passed anyway simply degrades, axis by axis, to those same values
     *  (guarded in [RenderModelResolver]'s view-override block). This probe is what the comparison-view UI gates
     *  its view-settings controls on. */
    fun isViewOverrideAvailable(): Boolean = allPresent(viewOverrideRequired)

    private fun allPresent(required: List<Pair<String, List<String>>>): Boolean = runCatching {
        required.all { (className, methods) ->
            val clazz = Class.forName(className, false, javaClass.classLoader)
            methods.all { name -> clazz.methods.any { it.name == name } }
        }
    }.onFailure { thisLogger().info("Render API unavailable on this IDE build: ${it.message}") }
        .getOrDefault(false)
}
