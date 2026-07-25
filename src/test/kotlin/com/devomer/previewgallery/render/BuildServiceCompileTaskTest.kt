package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [BuildService.chooseCompileTaskName], the pure half of the build-fix that picks a module's actual
 * Kotlin compile task instead of always assuming `compileDebugKotlin` (PG4-BUILDFIX). A standard AGP Android
 * module has `compileDebugKotlin`; a Kotlin-Multiplatform module's Android target has `compileDebugKotlinAndroid`
 * instead; a plain Kotlin/JVM module only has `compileKotlin`. The IDE-integration half — reading a module's
 * actually-available Gradle task names via `GradleModuleData.findAll` inside `BuildService.resolveCompileTarget`
 * — needs a real Gradle-linked project and so is not covered here.
 */
class BuildServiceCompileTaskTest {

    @Test
    fun `AGP Android module with compileDebugKotlin returns compileDebugKotlin`() {
        val available = listOf("compileDebugKotlin", "compileDebugJavaWithJavac", "assembleDebug")

        assertEquals("compileDebugKotlin", BuildService.chooseCompileTaskName(available))
    }

    @Test
    fun `KMP Android target without compileDebugKotlin returns compileDebugKotlinAndroid`() {
        val available = listOf("compileDebugKotlinAndroid", "compileKotlinMetadata")

        assertEquals("compileDebugKotlinAndroid", BuildService.chooseCompileTaskName(available))
    }

    @Test
    fun `plain Kotlin-JVM module with only compileKotlin returns compileKotlin`() {
        val available = listOf("compileKotlin", "classes")

        assertEquals("compileKotlin", BuildService.chooseCompileTaskName(available))
    }

    @Test
    fun `compileDebugKotlin takes priority over compileKotlin when both are present`() {
        val available = listOf("compileDebugKotlin", "compileKotlin")

        assertEquals("compileDebugKotlin", BuildService.chooseCompileTaskName(available))
    }

    @Test
    fun `no known candidate present returns null`() {
        val available = listOf("assembleDebug", "compileDebugJavaWithJavac")

        assertNull(BuildService.chooseCompileTaskName(available))
    }
}
