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

    // PG6-10: what RenderModelResolver.applyOverride + EphemeralPickerBridge actually call now — deriving an
    // overridden preview element (createDerivedInstance) and letting AS's own applyTo map it onto the render
    // Configuration (design D5), plus the ephemeral in-memory picker that edits it (design D4). This SUPERSEDES
    // the interim setDevice/setNightMode/setFontScale + getDeviceById probe (PG6-3/PG6-7): that mechanism was
    // replaced, not extended (spec D5), and grep confirms nothing in this plugin calls those four members any
    // more — keeping them here would gate the feature on a capability it no longer exercises.
    // Fix round 1 (review): getProperties (PsiPropertiesModel, inherited from PropertiesModel<P> — reflected the
    // same way as its two directly-declared members) and EditingErrorCategory (the validator lambda's return
    // type, adt-ui.jar — NotifyingItem's constructor call can't build without it) were both actually depended on
    // but missing from this list.
    private val viewOverrideRequired = listOf(
        "com.android.tools.preview.ComposePreviewElementInstance" to listOf("createDerivedInstance"),
        "com.android.tools.idea.compose.pickers.PsiPickerManager" to listOf("show"),
        "com.android.tools.idea.compose.pickers.base.model.PsiPropertiesModel" to listOf("getInspectorBuilder", "getTracker", "getProperties"),
        "com.android.tools.idea.compose.pickers.base.property.MemoryParameterPropertyItem" to listOf("getValue", "setValue"),
        "com.android.tools.idea.compose.pickers.preview.inspector.PreviewPropertiesInspectorBuilder" to emptyList(),
        "com.android.tools.idea.compose.pickers.preview.enumsupport.PreviewPickerValuesProvider" to listOf("createPreviewValuesProvider"),
        "com.android.tools.property.panel.api.PropertiesTable\$Companion" to listOf("create"),
        "com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker" to emptyList(),
        "com.android.tools.adtui.model.stdui.EditingErrorCategory" to emptyList(),
    )

    // PG11-1: the Kotlin-Multiplatform-aware `Module -> Android module` walk [AndroidModuleResolver] delegates
    // to. Android Studio's own editor preview calls exactly this before it asks for an AndroidFacet, which is
    // why a @Preview in a KMP `commonMain` source set renders there and used to fail here. Its own class, not
    // folded into [required]: a build without it must still render classic Android modules (the fallback is the
    // pre-PG11-1 `AndroidFacet.getInstance(module)`), so this can never gate rendering as a whole.
    private val androidModuleWalkRequired = listOf(
        "com.android.tools.idea.util.ModuleExtensionsKt" to listOf("findAndroidModule"),
    )

    // The Gradle tasks that compile a module, per Android Studio's own build system ([BuildService]). Its own
    // class for the same reason as the walk above: a build without it must still build classic AGP modules
    // through BuildService's task-name derivation, so this can never gate building as a whole.
    private val compileTaskFinderRequired = listOf(
        "com.android.tools.idea.gradle.project.build.invoker.GradleTaskFinder" to
            listOf("getInstance", "findTasksToExecute"),
        "com.android.tools.idea.gradle.util.BuildMode" to emptyList(),
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

    /** Whether the AS derive-and-apply + ephemeral-picker APIs a comparison-view override needs are present on
     *  this build (PG6-7 probe; widened in PG6-10 to what `RenderModelResolver.applyOverride` and
     *  `EphemeralPickerBridge` actually call). Independent of [isAvailable]: a build missing just this capability
     *  still renders every preview at its config-aware values. Gates both the comparison-view UI's ＋ Add view
     *  action and a copy tab's Properties action (PG6-8) — one flag for both, since re-rendering a copy with its
     *  override needs the same capability adding one does. */
    fun isViewOverrideAvailable(): Boolean = allPresent(viewOverrideRequired)

    /** Whether Android Studio's own `Module.findAndroidModule()` — the hop from a Kotlin Multiplatform common
     *  source set to the Android source set that implements it — is present on this build (PG11-1). Independent
     *  of [isAvailable]: a build missing it still renders every classic Android module through
     *  [AndroidModuleResolver]'s fallback; only KMP common source sets degrade to `Unsupported`. */
    fun isAndroidModuleWalkAvailable(): Boolean = allPresent(androidModuleWalkRequired)

    /** Whether [BuildService] can ask Android Studio which Gradle tasks compile a module (`GradleTaskFinder` +
     *  `BuildMode`) instead of matching a task name against the module's reported task list — which in Android
     *  Studio is always empty, because its sync skips building the Gradle task list. Independent of
     *  [isAvailable]: a build missing it falls back to that name derivation, which is correct in IntelliJ IDEA and
     *  for a classic AGP module in AS, and only mis-names the task for a KMP module. */
    fun isCompileTaskFinderAvailable(): Boolean = allPresent(compileTaskFinderRequired)

    private fun allPresent(required: List<Pair<String, List<String>>>): Boolean = runCatching {
        required.all { (className, methods) ->
            val clazz = Class.forName(className, false, javaClass.classLoader)
            methods.all { name -> clazz.methods.any { it.name == name } }
        }
    }.onFailure { thisLogger().info("Render API unavailable on this IDE build: ${it.message}") }
        .getOrDefault(false)
}
