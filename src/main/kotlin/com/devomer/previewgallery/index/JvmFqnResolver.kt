package com.devomer.previewgallery.index

/**
 * Derives the JVM class that hosts a Kotlin function, following the rules the Kotlin compiler uses for file
 * facades. Pure string logic on purpose — the PSI extraction lives in [PreviewPsiScanner].
 */
object JvmFqnResolver {

    /**
     * `foo.kt` -> `FooKt`, `foo-bar.kt` -> `Foo_barKt`, `1foo.kt` -> `_1fooKt`.
     *
     * Mirrors the compiler's own rule: characters that are not valid Java identifier *parts* become `_`, and a
     * name that does not *start* with a valid identifier character is prefixed with `_` instead of being
     * capitalized.
     */
    fun facadeClassName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val sanitized = buildString {
            base.forEach { char -> append(if (Character.isJavaIdentifierPart(char)) char else '_') }
        }
        val first = sanitized.firstOrNull() ?: return "_Kt"
        val className = if (Character.isJavaIdentifierStart(first)) {
            sanitized.replaceFirstChar { it.uppercaseChar() }
        } else {
            "_$sanitized"
        }
        return className + "Kt"
    }

    /**
     * @param containerObjectName the enclosing top-level `object`, or null for a top-level function.
     * @param jvmNameOverride the value of a `@file:JvmName(...)` annotation, if any. Ignored for object members,
     *   because `@file:JvmName` only renames the file facade.
     */
    fun jvmClassName(
        packageName: String,
        fileName: String,
        jvmNameOverride: String?,
        containerObjectName: String?,
    ): String {
        val simpleName = containerObjectName ?: jvmNameOverride ?: facadeClassName(fileName)
        return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
    }

    fun composableFqn(jvmClassName: String, functionName: String): String = "$jvmClassName.$functionName"
}
