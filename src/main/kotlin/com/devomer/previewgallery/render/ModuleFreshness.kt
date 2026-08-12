package com.devomer.previewgallery.render

import com.devomer.previewgallery.service.SnapshotSourceScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.concurrency.AppExecutorUtil
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
        cached(freshnessCache, module.name)?.let { return it.value }

        val roots = ReadAction.compute<ModuleRoots, RuntimeException> { resolveRoots(module) }
        val newestSource = roots.sourceRoots.maxOfOrNull { newestMtimeBounded(it) } ?: 0L
        val newestClass = roots.outputRoot?.let { newestMtimeBounded(it) } ?: 0L
        val fresh = isFresh(newestSource, newestClass)

        freshnessCache[module.name] = CacheEntry(System.currentTimeMillis(), fresh)
        return fresh
    }

    /**
     * The newest mtime among the sources of [module] a human edits, or null when there are none to read — an
     * unknown, which callers must not spell the same way as "nothing has changed".
     *
     * A different question from [isModuleFresh]'s: that one asks whether the compiled output is current, this one
     * asks when the human last changed the code. [com.devomer.previewgallery.service.SnapshotVerifyStore] compares
     * it against when a verify started.
     *
     * Three things this is not, each of which was wrong once:
     *
     * - **Not [ModuleRootManager.getSourceRoots] alone.** AGP registers `build/generated/…` as source roots, so a
     *   build writing `BuildConfig.java` would be indistinguishable from the user typing — the same confusion
     *   between build churn and an edit that made `PsiModificationTracker` unusable for this. Roots under the
     *   module's Gradle build directory are dropped.
     * - **Not the module's source roots only.** The `@PreviewTest` functions a verify measures live under
     *   `src/screenshotTest`, which a project synced without `-Pandroid.experimental.enableScreenshotTest=true`
     *   need not model as a source set at all — so no module has it as a source root and editing the snapshot
     *   test itself would leave a completed run reading fresh. [SnapshotSourceScanner.probe] is reused rather
     *   than reimplemented so the directory is found by exactly the rule that attributes its rows.
     * - **Not bounded**, unlike [isModuleFresh]'s scan. [MAX_SCAN_DEPTH] exists for the enormous class-file tree
     *   a build produces, and a source tree is nothing like that size; a deep-package edit missed by a depth cap
     *   would let a verify result that no longer describes the code claim to be fresh.
     *
     * Cached for [CACHE_TTL_MS], because `PreviewTreeCellRenderer` asks per painted row inside Swing's own paint
     * callback and `runVerify` repaints the tree the moment a run publishes — uncached, a failing module's rows
     * would each walk its whole source tree on the EDT. [isModuleFresh] already accepts exactly this trade on
     * exactly this data: the TTL self-corrects, so an edit is seen a few seconds later at worst.
     *
     * Callable off the EDT and outside a read action, exactly like [isModuleFresh]: the project-model half takes
     * its own short read action and the filesystem walk that follows holds no lock.
     *
     * Two ceilings recorded rather than defended against. The comparison downstream is mtime against a wall
     * clock, where the old one was mtime against mtime, so sources on a network mount served by a skewed clock
     * read permanently stale or permanently fresh. And the build-directory exclusion is lexical: a module with a
     * custom `layout.buildDirectory` defeats it and silently reproduces the original always-stale symptom. The
     * platform's own generated-source flag on the source folder would not care where the build directory is, but
     * it is not a like-for-like swap — a Gradle import does not put `JavaSourceRootProperties` on every root this
     * has to judge — so it is noted here rather than half-built.
     */
    fun newestModuleSourceMtime(module: Module): Long? {
        cached(sourceMtimeCache, module.name)?.let { return it.value }

        val roots = ReadAction.compute<ModuleRoots, RuntimeException> { resolveRoots(module) }
        val newest = newestSourceMtime(
            roots.sourceRoots + listOfNotNull(roots.snapshotSourceRoot),
            roots.outputRoot,
        )

        sourceMtimeCache[module.name] = CacheEntry(System.currentTimeMillis(), newest)
        return newest
    }

    /**
     * [newestModuleSourceMtime]'s answer for a caller that must not wait for it: whatever is cached, expired or
     * not, with a background refresh scheduled whenever the cached value is missing or past [CACHE_TTL_MS], and
     * [onRefreshed] called once that refresh has landed.
     *
     * The paint path is the whole reason this exists. `PreviewTreeCellRenderer` asks whether a failing module's
     * verdict is stale from inside Swing's per-row paint callback, and [newestModuleSourceMtime] walks that
     * module's entire source tree — unbounded on purpose, see its own doc — the first time it is asked after the
     * TTL lapses. On the EDT, once per repaint of a large module, that is a visible hitch.
     *
     * Serving the *expired* value rather than nothing is what keeps the badge steady: returning null every time the
     * TTL lapsed would flicker a fresh verdict to "stale" once every [CACHE_TTL_MS], since
     * [com.devomer.previewgallery.service.SnapshotVerifyStore.isStale] reads an unknown clock as stale. Only a cold
     * cache reads unknown, and that is the safe direction — it overstates staleness for one paint, and
     * [onRefreshed] corrects it.
     *
     * One walk per module at a time ([refreshingSourceMtime]): every painted row of a failing module asks this same
     * question inside the same frame, and they must not each schedule their own walk.
     */
    fun cachedModuleSourceMtime(module: Module, onRefreshed: () -> Unit): Long? {
        val entry = sourceMtimeCache[module.name]
        if (entry == null || System.currentTimeMillis() - entry.computedAtMs > CACHE_TTL_MS) {
            refreshSourceMtime(module, onRefreshed)
        }
        return entry?.value
    }

    /**
     * Recomputes [module]'s source clock on the app executor and calls [onRefreshed] afterwards, at most one walk
     * per module in flight. [newestModuleSourceMtime] does the work and the caching, so the two readers cannot
     * disagree about what the clock means.
     *
     * [onRefreshed] is a `repaint()` in the only production caller, which Swing documents as safe from any thread;
     * anything heavier belongs on the EDT by its own hop, not here. It fires whether or not the value changed —
     * one extra repaint costs nothing, and comparing values here would mean caching them twice.
     */
    private fun refreshSourceMtime(module: Module, onRefreshed: () -> Unit) {
        if (!refreshingSourceMtime.add(module.name)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (!module.isDisposed) newestModuleSourceMtime(module)
            } catch (e: ProcessCanceledException) {
                thisLogger().debug("Source-clock refresh for '${module.name}' was cancelled", e)
            } catch (e: Exception) {
                thisLogger().warn("Failed to refresh the source clock for module '${module.name}'", e)
            } finally {
                refreshingSourceMtime.remove(module.name)
            }
            onRefreshed()
        }
    }

    /** The pure half of [newestModuleSourceMtime] — see its doc for why [buildOutputRoot]'s descendants are
     *  excluded rather than scanned, and why nothing found reads as null rather than as 0. */
    internal fun newestSourceMtime(sourceRoots: List<File>, buildOutputRoot: File?): Long? {
        val excluded = buildOutputRoot?.toPath()
        return sourceRoots
            .filterNot { excluded != null && it.toPath().startsWith(excluded) }
            .maxOfOrNull { newestMtimeBounded(it, Int.MAX_VALUE) }
            ?.takeIf { it > 0L }
    }

    /**
     * Drops the cached verdicts for [module], if any. [RenderPipeline] calls this right after a successful
     * build, so a selection in the same module moments later (still inside [CACHE_TTL_MS]) re-derives
     * freshness from the just-updated output instead of replaying the pre-build "stale" verdict that triggered
     * that very build.
     */
    fun invalidate(module: Module) {
        freshnessCache.remove(module.name)
        sourceMtimeCache.remove(module.name)
    }

    /** A few seconds: long enough that arrow-keying through several previews in one module hits the cache,
     *  short enough that a build finishing just after the TTL still self-corrects on the next selection. */
    private const val CACHE_TTL_MS = 5_000L

    /** How many directory levels [newestMtimeBounded] descends into. Not tuned against a real Gradle build —
     *  see that function's doc for the trade-off this accepts. */
    private const val MAX_SCAN_DEPTH = 8

    private class CacheEntry<T>(val computedAtMs: Long, val value: T)
    private class ModuleRoots(val sourceRoots: List<File>, val outputRoot: File?, val snapshotSourceRoot: File?)

    private val freshnessCache = ConcurrentHashMap<String, CacheEntry<Boolean>>()
    private val sourceMtimeCache = ConcurrentHashMap<String, CacheEntry<Long?>>()
    private val refreshingSourceMtime = ConcurrentHashMap.newKeySet<String>()

    // Keyed by module name (like the rest of this plugin — see PreviewEntry.moduleName), not by the Module
    // instance: ModuleFreshness is an application-wide object, and holding Module references here would pin
    // them past project close.
    //
    // Returns the entry rather than its value so a cached null — "this module has no source clock" — is still a
    // hit, and does not walk the tree again on every painted row.
    private fun <T> cached(cache: ConcurrentHashMap<String, CacheEntry<T>>, moduleName: String): CacheEntry<T>? {
        val entry = cache[moduleName] ?: return null
        if (System.currentTimeMillis() - entry.computedAtMs > CACHE_TTL_MS) {
            cache.remove(moduleName)
            return null
        }
        return entry
    }

    /** Project-model reads only (the module root model, the already-cached Gradle sync data node, and a VFS
     *  lookup of two known child names) — no directory walking, so this is a short, cheap read action, not the
     *  multi-second walk that used to run inside one. */
    private fun resolveRoots(module: Module): ModuleRoots {
        val rootManager = ModuleRootManager.getInstance(module)
        val sourceRoots = rootManager.sourceRoots.map { File(it.path) }
        val snapshotSourceRoot = rootManager.contentRoots
            .firstNotNullOfOrNull { SnapshotSourceScanner.probe(it) }
            ?.let { File(it.path) }
        return ModuleRoots(sourceRoots, gradleBuildOutputDir(module), snapshotSourceRoot)
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
