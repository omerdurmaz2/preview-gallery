package com.devomer.previewgallery.render

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/**
 * Covers the two pure pieces of compile-target resolution.
 *
 * [BuildService.compileTargetOf] converts Android Studio's own answer — `GradleTaskFinder`'s tasks keyed by the
 * root directory Gradle runs from — into the `externalProjectPath` + `taskNames` split the external-system
 * request needs. That is the primary path now; asking AS replaced guessing a task name, because AS never
 * populates the task list its predecessor matched against (see `BuildService.studioCompileTarget`).
 *
 * [BuildService.chooseCompileTaskName] is that predecessor, kept as the fallback for an IDE build without the AS
 * API — IntelliJ IDEA does populate the task list, so matching a candidate against it is correct there. A
 * standard AGP Android module has `compileDebugKotlin`; a Kotlin-Multiplatform module's Android target has
 * `compileDebugKotlinAndroid` instead; a plain Kotlin/JVM module only has `compileKotlin`.
 *
 * The IDE-integration halves of both — a real Gradle-linked project, a real AGP/KMP variant model — are covered
 * as far as a light fixture can in [BuildServiceCompileTargetTest] and otherwise at the `runIde` gate.
 */
class BuildServiceCompileTaskTest {

    @Test
    fun `one root with one task becomes that root and task`() {
        val target = BuildService.compileTargetOf(tasks("/repo" to listOf(":app:compileDebugKotlin")))

        assertEquals("/repo", target?.projectPath)
        assertEquals(listOf(":app:compileDebugKotlin"), target?.taskPaths)
    }

    @Test
    fun `every task a module needs is kept, not just the first`() {
        val target = BuildService.compileTargetOf(
            tasks("/repo" to listOf(":primus:ui:compileDebugKotlinAndroid", ":primus:ui:processDebugResources")),
        )

        assertEquals(
            listOf(":primus:ui:compileDebugKotlinAndroid", ":primus:ui:processDebugResources"),
            target?.taskPaths,
        )
    }

    @Test
    fun `an empty result is no target, so the caller can fall back`() {
        assertNull(BuildService.compileTargetOf(tasks()))
    }

    @Test
    fun `a root that carries no task is skipped for one that does`() {
        val target = BuildService.compileTargetOf(
            tasks("/empty" to emptyList(), "/repo" to listOf(":app:compileDebugKotlin")),
        )

        assertEquals("/repo", target?.projectPath)
    }

    private fun tasks(vararg roots: Pair<String, List<String>>): ListMultimap<Path, String> {
        val multimap = ArrayListMultimap.create<Path, String>()
        roots.forEach { (root, taskPaths) -> multimap.putAll(Path.of(root), taskPaths) }
        return multimap
    }

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
