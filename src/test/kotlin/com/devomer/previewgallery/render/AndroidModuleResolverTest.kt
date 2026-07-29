package com.devomer.previewgallery.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers [AndroidModuleResolver] — the Kotlin-Multiplatform-aware `Module -> AndroidFacet` step that
 * [RenderModelResolver] now uses instead of a bare `AndroidFacet.getInstance(module)` (PG11-1).
 *
 * Two halves, split the same way [ModuleFreshnessModuleTest] splits its own:
 *
 * - The **contract** half is fully covered here: the Android Studio API the whole fix rests on
 *   (`com.android.tools.idea.util.ModuleExtensionsKt.findAndroidModule`) must exist on the IDE this plugin is
 *   compiled against, and a module that is neither an Android module nor a common source set of one must
 *   resolve to nothing rather than throwing. [module] from [BasePlatformTestCase] is exactly such a module: a
 *   plain, non-Gradle, non-Android, non-multiplatform fixture module.
 * - The **KMP walk** half — `commonMain` resolving to the `androidMain` module that carries the facet — needs a
 *   real synced Kotlin-Multiplatform Gradle project (the walk reads Kotlin facet settings written by Gradle
 *   sync, which no light fixture produces). It is verified manually at the `runIde` gate against
 *   `hepsi-android/primus`, the project that produced the original
 *   `Module 'hepsi-android.primus.ui.commonMain' has no Android facet` failure.
 */
class AndroidModuleResolverTest : BasePlatformTestCase() {

    fun `test the Android Studio facet walk exists on this IDE build`() {
        assertTrue(RenderApiProbe.isAndroidModuleWalkAvailable())
    }

    fun `test a module that is not an Android module resolves to no Android module`() {
        assertNull(AndroidModuleResolver.androidModule(module))
    }

    fun `test a module that is not an Android module resolves to no facet`() {
        assertNull(AndroidModuleResolver.androidFacet(module))
    }

    fun `test repeated resolutions agree with each other`() {
        val first = AndroidModuleResolver.androidModule(module)
        val second = AndroidModuleResolver.androidModule(module)

        assertEquals(first, second)
    }

    fun `test resolution runs cleanly inside a read action`() {
        val resolved = com.intellij.openapi.application.ReadAction.compute<Any?, RuntimeException> {
            AndroidModuleResolver.androidFacet(module)
        }

        assertNull(resolved)
    }
}
