package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project

// ── All imports below are Android Studio internal API (org.jetbrains.android / bundled render libs). ──
// This class, together with LiveRenderer, is the ONLY place that touches them (design §3.1).
import com.android.tools.configurations.Configuration
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.rendering.AndroidBuildTargetReference
import com.android.tools.idea.rendering.AndroidFacetRenderModelModule
import com.android.tools.idea.rendering.StudioRenderService
import com.android.tools.preview.DisplayPositioning
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.XmlSerializable
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.api.RenderModelModule
import org.jetbrains.android.facet.AndroidFacet

/**
 * Turns a [PreviewEntry] (its module + file) into everything [LiveRenderer] needs to build a layoutlib
 * `RenderTask`: the `(RenderModelModule, Configuration, RenderLogger)` triple plus the compose preview element
 * (an [XmlSerializable] that emits the `<ComposeViewAdapter tools:composableName=...>` bridge XML).
 *
 * Resolves design unknowns U1 (build-target reference) and U5 (default `PreviewConfiguration` /
 * `PreviewDisplaySettings`). Every AS-internal call is guarded against [Exception] and [LinkageError]; a signature
 * change on a newer IDE degrades to [RenderModelResult.Failed] instead of throwing out of the render.
 */
class RenderModelResolver {

    /** The pieces `RenderService.taskBuilder(...)` needs, plus the element that produces the layout XML. */
    class Resolved(
        val renderModule: RenderModelModule,
        val configuration: Configuration,
        val logger: RenderLogger,
        val element: XmlSerializable,
    )

    sealed interface RenderModelResult {
        class Resolved(val model: RenderModelResolver.Resolved) : RenderModelResult

        /** The file's module has no Android facet — the caller maps this to `Unsupported`. */
        object NoFacet : RenderModelResult

        /** An AS-internal call failed — the caller maps this to `Failure`. */
        class Failed(val message: String, val detail: String?) : RenderModelResult
    }

    fun resolve(entry: PreviewEntry, project: Project): RenderModelResult =
        try {
            // Project-model / PSI access (module lookup, facet, configuration) must run under a read action.
            ReadAction.compute<RenderModelResult, RuntimeException> { resolveUnderReadAction(entry, project) }
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Render model resolution failed for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Could not prepare the render model", e.stackTraceToString())
        } catch (e: LinkageError) {
            thisLogger().warn("Render model API mismatch for ${entry.indexed.composableFqn}", e)
            RenderModelResult.Failed("Render API is incompatible with this IDE build", e.stackTraceToString())
        }

    private fun resolveUnderReadAction(entry: PreviewEntry, project: Project): RenderModelResult {
        // U1: module → AndroidFacet → AndroidBuildTargetReference → AndroidFacetRenderModelModule.
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(entry.file)
            ?: return RenderModelResult.Failed("File is not part of any module", entry.file.path)
        val facet = AndroidFacet.getInstance(module)
            ?: return RenderModelResult.NoFacet
        val buildTarget = AndroidBuildTargetReference.from(facet, entry.file)
        val renderModule = AndroidFacetRenderModelModule(buildTarget)

        // The Configuration (device, theme, locale, target SDK) derived from the composable's own source file.
        val configuration = ConfigurationManager.getOrCreateInstance(module).getConfiguration(entry.file)

        // A logger scoped to this project; layoutlib records missing/broken classes and render problems on it.
        val logger = StudioRenderService.getInstance(project).createLogger(project)

        // U5 + the compose element whose toPreviewXml() drives the ComposeViewAdapter bridge (U2, consumed later).
        val element = buildPreviewElement(entry)

        return RenderModelResult.Resolved(Resolved(renderModule, configuration, logger, element))
    }

    /**
     * U5: builds a [SingleComposePreviewElementInstance] with a default configuration. `cleanAndGet(null…)` fills
     * every field (api level, size, locale, ui-mode, device) with layoutlib's defaults — the "no `@Preview`
     * arguments given" case. Display settings carry only naming metadata; `previewWrapperProviderFqn` is null
     * because this path is for plain (non-`@PreviewParameter`) composables.
     */
    private fun buildPreviewElement(entry: PreviewEntry): XmlSerializable {
        val previewConfiguration = PreviewConfiguration.cleanAndGet(
            /* apiLevel = */ null,
            /* width = */ null,
            /* height = */ null,
            /* locale = */ null,
            /* fontScale = */ null,
            /* uiMode = */ null,
            /* deviceSpec = */ null,
            /* wallpaper = */ null,
            /* colorBlindImageTransformation = */ null,
        )

        val displaySettings = PreviewDisplaySettings(
            /* name = */ entry.indexed.displayName,
            /* baseName = */ entry.indexed.functionName,
            /* parameterName = */ null,
            /* group = */ entry.indexed.previewGroup,
            /* showDecoration = */ false,
            /* background = */ PreviewDisplaySettings.Background.Default,
            /* displayPositioning = */ DisplayPositioning.NORMAL,
            /* organizationGroup = */ "",
            /* organizationName = */ null,
        )

        return SingleComposePreviewElementInstance<Any?>(
            /* methodFqn = */ entry.indexed.composableFqn,
            /* displaySettings = */ displaySettings,
            /* previewElementDefinition = */ null,
            /* previewBody = */ null,
            /* configuration = */ previewConfiguration,
            /* previewWrapperProviderFqn = */ null,
        )
    }
}
