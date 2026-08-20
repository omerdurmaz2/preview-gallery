package com.devomer.previewgallery.render

import java.io.File

/**
 * The compiled `screenshotTest` classes of one module and build variant, and whether they still describe the
 * source on disk.
 *
 * The path is AGP's, confirmed against the reference project:
 * `build/intermediates/built_in_kotlinc/<variant>ScreenshotTest/compile<Variant>ScreenshotTestKotlin/classes`.
 * The variant appears twice in two different casings — lower-camel in the source-set directory, upper-camel inside
 * the task name — which is why [directoryFor] takes the upper-camel form the rest of this plugin already carries
 * ([com.devomer.previewgallery.service.ReferenceRoots.Root.buildVariant], the same value
 * [com.devomer.previewgallery.render.SnapshotVerifyRunner.validateTask] builds its task name from) and lowers the
 * first character itself.
 *
 * **Newest `.class` file, not the directory's own mtime** ([newestClassMtime]). A directory's timestamp moves when
 * an entry is added or removed and stays put when a file is overwritten in place, which is precisely what an
 * incremental recompile does — the same trap [ModuleFreshness.newestMtimeBounded] documents from the other side.
 *
 * A null source clock reads [State.Stale], not [State.Ready]: "nothing could be read" and "nothing has changed"
 * are different facts, and this feature exists to produce a trustworthy number (spec D5). The same direction
 * [com.devomer.previewgallery.service.SnapshotVerifyStore.isStale] already takes for the same reason.
 */
internal object ScreenshotTestClasses {

    sealed interface State {
        data class Ready(val directory: File) : State
        object Missing : State
        data class Stale(val directory: File) : State
    }

    fun directoryFor(moduleDirectory: File, buildVariant: String): File {
        val sourceSet = buildVariant.replaceFirstChar { it.lowercaseChar() } + "ScreenshotTest"
        val task = "compile${buildVariant}ScreenshotTestKotlin"
        return File(moduleDirectory, "build/intermediates/built_in_kotlinc/$sourceSet/$task/classes")
    }

    /**
     * PG22-19: whether [selectedVariant] — the variant the IDE resolves a module's *main* classes against
     * ([AndroidModuleResolver.selectedVariantName]) — names the same variant as [referenceVariant], the one a
     * reference root's directory carries and [directoryFor] takes its compiled test classes from.
     *
     * The injection only ever *adds* a directory to the render classpath, so on a flavoured module a
     * `googleDebug` `screenshotTest` tree can end up linking against `huaweiDebug`'s `main`. The comparison is
     * then between two programs rather than two renders, and no percentage over it means anything.
     *
     * Case-insensitive, because the two values arrive in different casings by construction: the source-set
     * directory is `screenshotTestGoogleDebug`, so [referenceVariant] is upper-camel, while the build model names
     * the same variant `googleDebug`. Nothing else about them differs — both are the plain variant name AGP uses
     * in its own task names.
     *
     * A `null` [selectedVariant] matches. The IDE could not be asked, and refusing every comparison on a build
     * that cannot answer would remove the measurement instead of protecting it — the same direction
     * [AndroidModuleResolver.selectedVariantName] already takes by degrading to `null` rather than to a guess.
     */
    fun variantMatches(referenceVariant: String, selectedVariant: String?): Boolean =
        selectedVariant == null || referenceVariant.equals(selectedVariant, ignoreCase = true)

    fun stateOf(directory: File, newestSourceMillis: Long?): State {
        val newestClass = newestClassMtime(directory)
        if (newestClass <= 0L) return State.Missing
        if (newestSourceMillis == null || newestSourceMillis > newestClass) return State.Stale(directory)
        return State.Ready(directory)
    }

    /** 0 when the directory holds no class file at all, which callers must read as "not compiled" rather than as
     *  "compiled long ago". */
    fun newestClassMtime(directory: File): Long {
        if (!directory.isDirectory) return 0L
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .maxOfOrNull { it.lastModified() }
            ?: 0L
    }
}
