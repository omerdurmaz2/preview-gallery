package com.devomer.previewgallery.render

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException

// ── Android Studio internal API (org.jetbrains.android / android-common). Design §3.1 keeps that coupling in
// render/ only; this file joins RenderModelResolver + LiveRenderer + the picker bridges in that set. ──
import com.android.tools.idea.util.findAndroidModule
import org.jetbrains.android.facet.AndroidFacet

/**
 * Resolves the [AndroidFacet] that should drive a render for a file's module — including when that module is a
 * Kotlin Multiplatform **common** source set, which carries no facet of its own (PG11-1).
 *
 * ## The bug this exists for
 *
 * `AndroidFacet.getInstance(module)` alone is correct only for a classic AGP module, where the facet sits on the
 * very source-set module a source file belongs to (`app.main`). In a KMP library built with
 * `com.android.kotlin.multiplatform.library`, Gradle sync creates one IDE module per Kotlin source set and puts
 * the Android facet on the **Android target's** module (`…​.androidMain`). A `@Preview` declared in
 * `…​.commonMain` therefore resolved to a module with no facet at all, and every such preview failed with
 * `Module '…​.commonMain' has no Android facet` — while Android Studio's own editor preview rendered it fine.
 *
 * ## What Android Studio does, and what this mirrors
 *
 * `com.android.tools.idea.preview.SurfacePreviewsUpdateKt.updatePreviewsAndRefresh` — the function behind the
 * editor's own preview tab — does not call `AndroidFacet.getInstance` on the file's module either. Its bytecode
 * (Android Studio Panda 4, `design-tools.jar`) reads:
 *
 * ```
 * psiFile.module → ModuleExtensionsKt.findAndroidModule(Module) → AndroidFacet.getInstance(Module)
 *                → ConfigurationManager.getOrCreateInstance(facet.module)
 *                → AndroidBuildTargetReference.from(facet, psiFile.virtualFile)
 * ```
 *
 * and `findAndroidModule` (`android-common.jar`, `com.android.tools.idea.util.ModuleExtensionsKt`) is
 * `if (isAndroidModule()) this else KotlinFacetUtils.getImplementingModules(this).firstOrNull { it.isAndroidModule() }`
 * — i.e. a common source set hops to the first *implementing* source set that has an Android facet.
 *
 * This object calls that exact Android Studio function rather than reimplementing the walk, for the same reason
 * [RenderModelResolver] uses AS's own preview-element finder and `applyTo`: matching behaviour is the point, and
 * a private reimplementation would drift. Note the facet's own module (`facet.module`), not the file's module,
 * is also what AS hands to `ConfigurationManager` — see [RenderModelResolver.resolveUnderReadAction].
 *
 * ## Degradation
 *
 * Guarded like every other AS-internal call in `render/`: probed first ([RenderApiProbe.isAndroidModuleWalkAvailable])
 * so an IDE build without the API never attempts it, then wrapped against [Exception]/[LinkageError]. Every
 * failure path falls back to the pre-PG11-1 behaviour — a plain `AndroidFacet.getInstance(module)` — so a build
 * that cannot do the KMP walk still renders classic Android modules exactly as before, and only KMP common
 * source sets degrade back to `Unsupported`.
 *
 * Reads the project model, so callers must hold a read action (every one of them already does).
 */
object AndroidModuleResolver {

    /**
     * The facet that should drive the render for [module], or `null` when neither [module] nor any source set
     * implementing it is an Android module. [LiveRenderer] maps that `null` to `Unsupported`.
     */
    fun androidFacet(module: Module): AndroidFacet? {
        val androidModule = androidModule(module) ?: return null
        return AndroidFacet.getInstance(androidModule)
    }

    /**
     * [module] itself when it is an Android module, else the Android source set that implements it (KMP), else
     * `null`. Split out from [androidFacet] because a caller may want the module — not just the facet — and
     * because it is the piece that carries the whole AS-API risk, so it owns the guard.
     */
    fun androidModule(module: Module): Module? {
        if (!RenderApiProbe.isAndroidModuleWalkAvailable()) return moduleWithOwnFacet(module)
        return try {
            module.findAndroidModule()
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().info("The Android-module walk failed for '${module.name}'; using its own facet only", e)
            moduleWithOwnFacet(module)
        } catch (e: LinkageError) {
            thisLogger().info(
                "The Android-module walk API is incompatible with this IDE build; using '${module.name}'s own facet only",
                e,
            )
            moduleWithOwnFacet(module)
        }
    }

    /** The pre-PG11-1 behaviour, kept as the fallback for every guarded failure path above. */
    private fun moduleWithOwnFacet(module: Module): Module? =
        module.takeIf { AndroidFacet.getInstance(it) != null }
}
