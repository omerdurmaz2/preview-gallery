package com.devomer.previewgallery.render

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import org.jetbrains.plugins.gradle.util.GradleModuleData
import org.jetbrains.plugins.gradle.util.GradleUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A module's compiled output is "fresh" when no source file is newer than the newest class file. Cheap and
 * adequate (spec §6): a wrong answer costs at most one redundant build or one NEEDS_BUILD prompt.
 *
 * [isModuleFresh] runs on every selection ([RenderPipeline.dispatch]). Two things made that dangerous (PG3-5):
 * - it walked every file under every source root and the whole compiler output directory, so even a
 *   medium-sized module cost real wall-clock time on every single selection;
 * - the caller ran that whole walk inside a read action, so it held the read lock for its entire duration —
 *   every write action in the IDE (typing, PSI updates) queues up behind it. A prime freeze suspect.
 *
 * Both are fixed by moving the split *into* this class: [resolveRoots] takes one short read action for the
 * project-model reads only (source roots, the module's Gradle output directory — no filesystem access), and
 * [newestMtimeBounded] — the actual filesystem walk — runs afterwards with no read action held at all, bounded
 * to [MAX_SCAN_DEPTH] so it is a small, fixed-size scan rather than a walk of every class file.
 *
 * A third, independent bug shared the same "stale" symptom: [com.intellij.openapi.roots.CompilerModuleExtension]
 * (the old source of the output path) is null for a Gradle-imported Android module, since Android delegates
 * compilation to Gradle and never registers a JPS output for it. That made every such module look permanently
 * stale, and since selection auto-builds a stale module (PG2-11), every single selection fired a Gradle build.
 * [gradleBuildOutputDir] fixes this — see its doc for what was verified and why.
 */
object ModuleFreshness {

    /** @return false (stale) when there is no class output at all, i.e. newestClassMtime <= 0. */
    fun isFresh(newestSourceMtime: Long, newestClassMtime: Long): Boolean =
        newestClassMtime > 0 && newestSourceMtime <= newestClassMtime

    /**
     * Whole-module freshness (design §6, PG3-5). Safe — required, even — to call outside any read action and
     * off the EDT: it takes its own short read action internally for the project-model part only, and the
     * filesystem walk that follows is deliberately lock-free. Cached per module for [CACHE_TTL_MS] so stepping
     * through several previews in the same module does not re-derive and re-scan for every one; see
     * [invalidate].
     */
    fun isModuleFresh(module: Module): Boolean {
        cached(module)?.let { return it }

        val roots = ReadAction.compute<ModuleRoots, RuntimeException> { resolveRoots(module) }
        val newestSource = roots.sourceRoots.maxOfOrNull { newestMtimeBounded(it) } ?: 0L
        val newestClass = roots.outputRoot?.let { newestMtimeBounded(it) } ?: 0L
        val fresh = isFresh(newestSource, newestClass)

        cache[module.name] = CacheEntry(System.currentTimeMillis(), fresh)
        return fresh
    }

    /**
     * Drops the cached verdict for [module], if any. [RenderPipeline] calls this right after a successful
     * build, so a selection in the same module moments later (still inside [CACHE_TTL_MS]) re-derives
     * freshness from the just-updated output instead of replaying the pre-build "stale" verdict that triggered
     * that very build.
     */
    fun invalidate(module: Module) {
        cache.remove(module.name)
    }

    /** A few seconds: long enough that arrow-keying through several previews in one module hits the cache,
     *  short enough that a build finishing just after the TTL still self-corrects on the next selection. */
    private const val CACHE_TTL_MS = 5_000L

    /** How many directory levels [newestMtimeBounded] descends into. Not tuned against a real Gradle build —
     *  see that function's doc for the trade-off this accepts. */
    private const val MAX_SCAN_DEPTH = 8

    private class CacheEntry(val computedAtMs: Long, val fresh: Boolean)
    private class ModuleRoots(val sourceRoots: List<File>, val outputRoot: File?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Keyed by module name (like the rest of this plugin — see PreviewEntry.moduleName), not by the Module
    // instance: ModuleFreshness is an application-wide object, and holding Module references here would pin
    // them past project close.
    private fun cached(module: Module): Boolean? {
        val entry = cache[module.name] ?: return null
        if (System.currentTimeMillis() - entry.computedAtMs > CACHE_TTL_MS) {
            cache.remove(module.name)
            return null
        }
        return entry.fresh
    }

    /** Project-model reads only (the module root model, plus the already-cached Gradle sync data node) — no
     *  filesystem access, so this is a short, cheap read action, not the multi-second walk that used to run
     *  inside one. */
    private fun resolveRoots(module: Module): ModuleRoots {
        val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots.map { File(it.path) }
        return ModuleRoots(sourceRoots, gradleBuildOutputDir(module))
    }

    /**
     * The module's Gradle build output directory, or null when [module] is not part of a linked Gradle
     * project.
     *
     * NOT [com.intellij.openapi.roots.CompilerModuleExtension]: its `compilerOutputPath` is null for a
     * Gradle-imported Android module, because Android delegates compilation to Gradle and never gets a JPS
     * output registered for it — confirmed by this fix (that is precisely the bug being fixed here: a null
     * output path made `newestClassMtime` 0, so [isFresh] was always false).
     *
     * Also NOT the AS-internal Android Gradle model
     * (`com.android.tools.idea.gradle.project.model.GradleAndroidModel.get(module)?.mainArtifact
     * ?.classesFolder`). That API exists — verified with `javap` against Android Studio Panda 4's own
     * `android.jar` (`GradleAndroidModel` and `GradleAndroidModel$Companion`) and
     * `android-project-system-gradle-models.jar` (`IdeBaseArtifactCore.getClassesFolder(): Collection<File>`,
     * inherited by the `IdeAndroidArtifactCore` returned from `GradleAndroidModel.getMainArtifact()`) — and it
     * would give the exact, variant-specific classes directory AGP itself reports. It is deliberately not used
     * here: it is Android-Studio-internal API (`com.android.tools.idea.*`), and design §3.1 keeps all such
     * coupling in `LiveRenderer` / `RenderModelResolver` (render) and `PreviewPickerBridge` /
     * `GalleryPickerTracker` (picker) only — `ModuleFreshness` is explicitly listed there as needing no
     * AS-internal API, and this fix keeps it that way rather than adding a fifth place that needs it.
     *
     * Instead: the same *platform* Gradle-plugin lookup [BuildService] already uses to find where to run a
     * build — `GradleUtil.findGradleModuleData(module)` (verified with `javap` against the bundled Gradle
     * plugin's `gradle.jar`: returns `DataNode<ModuleData>?`) wrapped in [GradleModuleData] — but reading
     * [GradleModuleData.getGradleProjectDir] instead of the [GradleModuleData.getDirectoryToRunTask] BuildService
     * uses: `directoryToRunTask` is the directory to invoke the `gradle` process from (the build root, so an
     * absolute task path like `:app:compileDebugKotlin` resolves correctly — see [BuildService]'s doc), whereas
     * `gradleProjectDir` is *this* module's own subproject directory. Using `directoryToRunTask` here would
     * point every module in a multi-module project at the *root* project's build directory instead of its own
     * — wrong for the overwhelmingly common multi-module Android project shape. `build` is Gradle's own
     * conventional output directory (the `Project.layout.buildDirectory` default) directly under a
     * subproject's own directory.
     *
     * Guarded like [BuildService.resolveCompileTarget] guards this exact same lookup: not because this is
     * AS-internal API (it is not), but because it is still an external plugin's integration surface running on
     * every selection, and an uncaught exception here must not break the whole freshness check.
     */
    private fun gradleBuildOutputDir(module: Module): File? = try {
        val dataNode = GradleUtil.findGradleModuleData(module)
        if (dataNode == null) {
            null
        } else {
            File(GradleModuleData(dataNode).gradleProjectDir, "build")
        }
    } catch (e: Exception) {
        thisLogger().warn("Failed to resolve the Gradle build output directory for module '${module.name}'", e)
        null
    } catch (e: LinkageError) {
        thisLogger().warn("The Gradle module-data API is incompatible with this IDE build", e)
        null
    }

    /**
     * The newest mtime found within [maxDepth] directory levels of [root] (both files and directories — a
     * directory's own mtime already advances when a build adds or removes an entry directly inside it, which
     * catches most real builds without needing to reach the specific file that changed).
     *
     * A bounded approximation instead of the previous unbounded, whole-tree walk (PG3-5): cheap — a fixed-size
     * scan instead of one proportional to the module's entire output — and the design already accepts an
     * imprecise answer here: "a wrong answer costs at most one redundant build or one NEEDS_BUILD prompt"
     * (spec §6). The trade-off this accepts: an *incremental* rebuild that only overwrites the content of an
     * already-existing file nested deeper than [maxDepth], without adding or removing any directory entry
     * above it, can be missed. [invalidate] plus this cache's short TTL bound how long that can matter — the
     * next build (or the next cold selection after the TTL) re-derives from disk again — and a missed staleness
     * signal only means a render shows the previous output rather than failing outright, recoverable at any
     * time by reselecting after a real change lands within [maxDepth] (typically true — see [MAX_SCAN_DEPTH]).
     */
    internal fun newestMtimeBounded(root: File, maxDepth: Int = MAX_SCAN_DEPTH): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().maxDepth(maxDepth).maxOfOrNull { it.lastModified() } ?: 0L
    }
}
