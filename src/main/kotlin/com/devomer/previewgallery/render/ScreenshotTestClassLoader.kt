package com.devomer.previewgallery.render

import com.android.tools.idea.projectsystem.ClassContent
import com.android.tools.idea.rendering.BuildTargetReference
import com.android.tools.idea.rendering.StudioModuleRenderContext
import com.android.tools.idea.rendering.classloading.loaders.ProjectSystemClassLoader
import com.android.tools.idea.rendering.tokens.BuildSystemFilePreviewServices
import com.android.tools.rendering.api.RenderModelModule
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.android.uipreview.StudioModuleClassLoaderManager
import java.io.File

typealias RenderModuleWrapper = (RenderModelModule) -> RenderModelModule

/**
 * Puts a module's compiled `screenshotTest` classes on the render classpath, so a `@PreviewTest` composable can be
 * rendered inside the IDE at all.
 *
 * Two spikes stand behind this. The first
 * (`docs/superpowers/specs/2026-08-10-screenshottest-render-spike.md`) found that everything up to the class load
 * succeeds and only the classpath is missing a directory. The second
 * (`docs/superpowers/specs/2026-08-10-render-classloader-spike.md`) confirmed at runtime that the render asks the
 * plugin's own [RenderModelModule] for its class loader, and by `javap` that every piece below it is public — so
 * this composes rather than reimplements. `ModuleClassLoader` is abstract with about ten abstract members and none
 * of them is implemented here; [StudioModuleClassLoaderManager] builds the real loader.
 *
 * `javap` against this IDE build (task-3-report.md has the full output) confirmed the spike's table for all four
 * load-bearing symbols, but corrected two supporting details the spike could not have known without them:
 *  - [RenderModelModule.getClassLoaderProvider] returns a `ClassLoaderProvider` — a one-method interface whose
 *    `getClassLoader(parent, additionalProjectTransformation, additionalNonProjectTransformation,
 *    onNewModuleClassLoader)` is what actually hands over the (nullable) parent [ClassLoader] and the two
 *    [ClassTransform]s — not a bare [ModuleClassLoaderManager.Reference] as first assumed.
 *    [getClassLoaderProviderFor] therefore returns that interface and forwards every argument it is given straight
 *    into [StudioModuleClassLoaderManager.getPrivate] instead of inventing a parent loader or substituting
 *    [ClassTransform.identity].
 *  - Neither [RenderModelModule] nor `AndroidFacetRenderModelModule` exposes a `buildTargetReference` accessor —
 *    the field exists but has no public getter. [RenderModelModule] does, however, expose `getIdeaModule()`
 *    (`IdeaModuleProvider`, part of its own interface hierarchy), and `BuildTargetReference.gradleOnly(Module)` is
 *    a public static factory that needs nothing else. [injectingContext] uses that rather than the original
 *    `AndroidBuildTargetReference` the caller already held, because [RenderModuleWrapper] only ever receives the
 *    already-built [RenderModelModule] — the exact reference used to construct it is not recoverable from that.
 *    This is not a guess: `StudioModuleRenderContext.Companion.forModule(Module)` — Android Studio's own factory
 *    for the same context type — compiles to exactly `new StudioModuleRenderContext(BuildTargetReference.Companion
 *    .gradleOnly(module))` (confirmed by `javap -c`), the identical derivation used here. And it converges on the
 *    same target as the original per-entry `AndroidBuildTargetReference.from(facet, entry.file)`:
 *    `GradleBuildSystemFilePreviewServices` ignores the file parameter, and `getIdeaModule()` compiles to
 *    `getFacet().getModule()` — same facet, same module, same target either way.
 *
 * A **private** loader per render, released when the render ends: a shared loader carrying `screenshotTest`
 * classes would leak test classes into the class cache every ordinary `@Preview` render then reuses. That is why
 * [getClassLoaderProviderFor] always calls [StudioModuleClassLoaderManager.getPrivate], ignoring the
 * `privateClassLoader` flag the platform passes in (the first spike observed it calling with `private=false`).
 *
 * Returns null rather than throwing when the composition cannot be built on this IDE build. Every caller treats
 * null as "render the ordinary way", which is the degrade-don't-break posture the rest of `render/` holds.
 */
internal object ScreenshotTestClassLoader {

    fun wrapperFor(classesDirectory: File): RenderModuleWrapper? = try {
        buildWrapper(classesDirectory)
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().warn("Could not compose a screenshotTest class loader for $classesDirectory", e)
        null
    } catch (e: LinkageError) {
        thisLogger().warn("The render class-loader API is incompatible with this IDE build", e)
        null
    }

    private fun buildWrapper(classesDirectory: File): RenderModuleWrapper = { module ->
        object : RenderModelModule by module {
            override fun getClassLoaderProvider(privateClassLoader: Boolean): RenderModelModule.ClassLoaderProvider =
                getClassLoaderProviderFor(module, classesDirectory, privateClassLoader)
        }
    }

    /**
     * The `ClassLoaderProvider` [RenderModelModule.getClassLoaderProvider] actually returns (see the class doc for
     * why this is not a bare [ModuleClassLoaderManager.Reference]). Every argument the platform hands to
     * `getClassLoader` — the parent [ClassLoader] and both [ClassTransform]s — is forwarded unchanged into
     * [StudioModuleClassLoaderManager.getPrivate]; only `onNewModuleClassLoader` has nowhere to go, matching
     * `AndroidFacetRenderModelModule`'s own `getClassLoaderProvider` lambda shape confirmed by `javap`.
     *
     * This call happens well after [wrapperFor] returned — Android Studio's own pipeline invokes it, so
     * [wrapperFor]'s guard does not cover it. Guarded here on its own: a failure building the private loader falls
     * back to [module]'s own, real `getClassLoaderProvider(privateClassLoader)`, which is exactly today's
     * behaviour — the render proceeds on the ordinary classpath and fails to find the snapshot class the same way
     * it does now, rather than failing the whole render.
     */
    private fun getClassLoaderProviderFor(
        module: RenderModelModule,
        classesDirectory: File,
        privateClassLoader: Boolean,
    ): RenderModelModule.ClassLoaderProvider = object : RenderModelModule.ClassLoaderProvider {
        override fun getClassLoader(
            parent: ClassLoader?,
            additionalProjectTransformation: ClassTransform,
            additionalNonProjectTransformation: ClassTransform,
            onNewModuleClassLoader: Runnable,
        ): ModuleClassLoaderManager.Reference<*> {
            try {
                return StudioModuleClassLoaderManager.get().getPrivate(
                    parent,
                    injectingContext(module, classesDirectory),
                    additionalProjectTransformation,
                    additionalNonProjectTransformation,
                )
            } catch (e: ProcessCanceledException) {
                throw e // Never swallow cancellation — the platform relies on it propagating.
            } catch (e: Exception) {
                thisLogger().warn(
                    "Could not build a private screenshotTest class loader for $classesDirectory; " +
                        "rendering with the ordinary classpath instead",
                    e,
                )
            } catch (e: LinkageError) {
                thisLogger().warn(
                    "The private class-loader API is incompatible with this IDE build; " +
                        "rendering with the ordinary classpath instead",
                    e,
                )
            }
            return module.getClassLoaderProvider(privateClassLoader).getClassLoader(
                parent,
                additionalProjectTransformation,
                additionalNonProjectTransformation,
                onNewModuleClassLoader,
            )
        }
    }

    /**
     * The injected directory is layered **over** the project's own class-file finder, never in place of it.
     *
     * `createInjectableClassLoaderLoader()` is not one loader among several: `StudioModuleClassLoader` hands
     * whatever it returns to `ModuleClassLoaderImpl` as its single `projectSystemLoader` field, and that field is
     * the only path either `createProjectLoader(...)` call has to project classes — `createNonProjectLoader` wraps
     * the external libraries and layoutlib's parent loader, neither of which carries module output. Returning only
     * the `screenshotTest` directory therefore does not leave the real loader to answer for everything else; it
     * severs it, and the first `main` class the `@PreviewTest` composable touches (the composable under test, the
     * theme, the module's `R`) fails to load.
     *
     * So the lambda tries the directory first and falls back to [projectClassFor], which is byte-for-byte what the
     * stock `createInjectableClassLoaderLoader` lambda does (`javap -c` on `StudioModuleRenderContext`:
     * `BuildSystemFilePreviewServices.Companion.getBuildSystemFilePreviewServices(buildTargetReference)
     * .getRenderingServices(buildTargetReference).classFileFinder?.findClassFile(fqcn)` — the first of those is an
     * *extension* on `BuildTargetReference` declared inside the companion, per its
     * `$this$getBuildSystemFilePreviewServices` receiver slot, hence the `with` in [projectClassFor]). The spike's
     * alternative —
     * `super.createInjectableClassLoaderLoader()` plus `injectClassFile` — is deliberately not used:
     * `ProjectSystemClassLoader` stores injected content in a `SofterReference` (its `classCache` field type), so a
     * cleared entry would silently fall back to the project finder and load the `main` class of the same name, or
     * none at all. Explicit delegation cannot go quiet that way.
     *
     * `buildTargetReference` is the context's own property for the reference this subclass was constructed with
     * (`public final getBuildTargetReference()` on `StudioModuleRenderContext`, per `javap`), so the fallback
     * targets exactly the module the stock lambda would have.
     */
    private fun injectingContext(module: RenderModelModule, classesDirectory: File): StudioModuleRenderContext =
        object : StudioModuleRenderContext(BuildTargetReference.gradleOnly(module.getIdeaModule())) {
            override fun createInjectableClassLoaderLoader(): ProjectSystemClassLoader =
                ProjectSystemClassLoader { fqcn ->
                    classFileFor(fqcn, classesDirectory) ?: projectClassFor(fqcn, buildTargetReference)
                }
        }

    /**
     * What the stock `createInjectableClassLoaderLoader` lambda would have returned for [fqcn] — the project's own
     * class-file finder for [buildTarget] — so a class the injected directory does not hold still loads (see
     * [injectingContext]).
     *
     * Guarded like every other AS-internal call here, and on its own because it runs when Android Studio's loader
     * asks for a class, long after [wrapperFor] returned: on failure it returns null, so that one load misses and
     * layoutlib reports a missing class, rather than an exception taking the whole render down.
     */
    private fun projectClassFor(fqcn: String, buildTarget: BuildTargetReference): ClassContent? = try {
        with(BuildSystemFilePreviewServices) {
            buildTarget.getBuildSystemFilePreviewServices()
                .getRenderingServices(buildTarget)
                .classFileFinder
                ?.findClassFile(fqcn)
        }
    } catch (e: ProcessCanceledException) {
        throw e // Never swallow cancellation — the platform relies on it propagating.
    } catch (e: Exception) {
        thisLogger().warn("The project class-file finder could not answer for $fqcn", e)
        null
    } catch (e: LinkageError) {
        thisLogger().warn(
            "The project class-file finder API is incompatible with this IDE build; $fqcn will not load",
            e,
        )
        null
    }

    /**
     * A fully qualified name that is not in [classesDirectory] returns null, which [injectingContext] turns into a
     * [projectClassFor] delegation — the injected directory holds only the `screenshotTest` classes, and every
     * `main` class the composable touches comes from the project finder instead. Kotlin file facades
     * (`UtilSnapshotsKt`) and `ComposableSingletons$…` classes are ordinary entries here; no special casing.
     *
     * This runs when Android Studio's own loader asks for [fqcn], long after [wrapperFor] returned, so it is
     * guarded on its own too: a read failure returns null exactly like a genuine miss, so the delegation answers
     * for that one class instead of the whole render failing.
     */
    private fun classFileFor(fqcn: String, classesDirectory: File): ClassContent? {
        val file = File(classesDirectory, fqcn.replace('.', '/') + ".class")
        if (!file.isFile) return null
        return try {
            ClassContent.loadFromFile(file)
        } catch (e: ProcessCanceledException) {
            throw e // Never swallow cancellation — the platform relies on it propagating.
        } catch (e: Exception) {
            thisLogger().warn("Could not read $file; the ordinary loader will answer for $fqcn instead", e)
            null
        } catch (e: LinkageError) {
            thisLogger().warn(
                "The class-content read API is incompatible with this IDE build; the ordinary loader will answer for $fqcn instead",
                e,
            )
            null
        }
    }
}
