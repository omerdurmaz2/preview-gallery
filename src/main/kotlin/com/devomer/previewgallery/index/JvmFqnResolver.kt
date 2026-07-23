package com.devomer.previewgallery.index

/**
 * Derives the JVM class that hosts a Kotlin function, following the rules the Kotlin compiler uses for file
 * facades. Pure string logic on purpose — the PSI extraction lives in [PreviewPsiScanner].
 */
object JvmFqnResolver {

    /** `foo.kt` -> `FooKt`, `foo-bar.kt` -> `Foo_barKt`. */
    fun facadeClassName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val sanitized = buildString {
            base.forEachIndexed { index, char ->
                val valid =
                    if (index == 0) Character.isJavaIdentifierStart(char) else Character.isJavaIdentifierPart(char)
                append(if (valid) char else '_')
            }
        }
        return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
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
