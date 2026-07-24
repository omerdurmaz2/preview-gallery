package com.devomer.previewgallery.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [ModuleFreshness.isModuleFresh] and [ModuleFreshness.invalidate] against a real
 * [com.intellij.openapi.module.Module] — the project-model half PG3-5 moved behind its own short read action.
 *
 * [module] here is not linked to a Gradle project, so every call lands on the "not a Gradle module" branch
 * (conservatively stale). The point of these tests is not the Gradle-derivation logic — that needs a real
 * synced Android project and is verified manually instead (see the PG3-5 report) — it is proving
 * [ModuleFreshness.isModuleFresh] and [ModuleFreshness.invalidate] run cleanly called bare, with no
 * `ReadAction.compute` wrapper, the way [RenderPipeline.dispatch] now calls them.
 */
class ModuleFreshnessModuleTest : BasePlatformTestCase() {

    fun `test a module with no linked Gradle project is conservatively stale`() {
        assertFalse(ModuleFreshness.isModuleFresh(module))
    }

    fun `test repeated calls agree with each other`() {
        val first = ModuleFreshness.isModuleFresh(module)
        val second = ModuleFreshness.isModuleFresh(module)

        assertEquals(first, second)
    }

    fun `test invalidate does not throw whether or not a result is cached`() {
        ModuleFreshness.invalidate(module) // nothing cached yet
        ModuleFreshness.isModuleFresh(module) // now something is
        ModuleFreshness.invalidate(module) // and it is cleared
    }
}
