package com.devomer.previewgallery.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers the IDE-integration half of compile-target resolution, as far as a light fixture reaches — split from
 * [BuildServiceCompileTaskTest] (pure) the same way [AndroidModuleResolverTest] splits its own halves.
 *
 * - The **contract**: the Android Studio API the fix rests on (`GradleTaskFinder` + `BuildMode.COMPILE_JAVA`) must
 *   exist on the IDE this plugin is compiled against. Without it every module silently falls back to the task-name
 *   derivation, which is exactly what named `:primus:ui:compileDebugKotlin` on a Kotlin Multiplatform module and
 *   failed the build.
 * - The **degrade**: [BuildService.build] on a module that belongs to no Gradle project must report failure
 *   through its callback instead of throwing. [module] from [BasePlatformTestCase] is such a module — plain,
 *   non-Gradle, non-Android — so it exercises both resolution halves returning nothing.
 *
 * A real KMP module resolving to its Android target's compile task needs a synced Gradle project (the variant
 * model comes from sync, which no light fixture produces); that is verified at the `runIde` gate against
 * `hepsi-android/primus`, the project whose `:primus:ui` build produced the original failure.
 */
class BuildServiceCompileTargetTest : BasePlatformTestCase() {

    fun `test the Android Studio compile-task API exists on this IDE build`() {
        assertTrue(RenderApiProbe.isCompileTaskFinderAvailable())
    }

    fun `test a module that is not part of a Gradle project reports a failed build`() {
        val outcomes = mutableListOf<Boolean>()

        BuildService.getInstance(project).build(module) { outcomes += it }

        assertEquals(listOf(false), outcomes)
    }
}
