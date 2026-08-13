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
