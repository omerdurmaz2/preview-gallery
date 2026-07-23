package com.devomer.previewgallery.index

import org.junit.Assert.assertEquals
import org.junit.Test

class JvmFqnResolverTest {

    @Test
    fun `top-level function uses the file facade class`() {
        val jvmClass = JvmFqnResolver.jvmClassName(
            packageName = "com.example",
            fileName = "Foo.kt",
            jvmNameOverride = null,
            containerObjectName = null,
        )
        assertEquals("com.example.FooKt", jvmClass)
        assertEquals("com.example.FooKt.BarPreview", JvmFqnResolver.composableFqn(jvmClass, "BarPreview"))
    }

    @Test
    fun `JvmName annotation replaces the facade name`() {
        assertEquals(
            "com.example.Custom",
            JvmFqnResolver.jvmClassName("com.example", "Foo.kt", "Custom", null),
        )
    }

    @Test
    fun `object member uses the object class and ignores JvmName`() {
        assertEquals(
            "com.example.Previews",
            JvmFqnResolver.jvmClassName("com.example", "Foo.kt", "Custom", "Previews"),
        )
    }

    @Test
    fun `default package has no prefix`() {
        assertEquals("FooKt", JvmFqnResolver.jvmClassName("", "Foo.kt", null, null))
    }

    @Test
    fun `file name is capitalized`() {
        assertEquals("FooKt", JvmFqnResolver.facadeClassName("foo.kt"))
    }

    @Test
    fun `invalid identifier characters become underscores`() {
        assertEquals("Foo_barKt", JvmFqnResolver.facadeClassName("foo-bar.kt"))
    }

    @Test
    fun `a leading digit is prefixed with an underscore`() {
        assertEquals("_1fooKt", JvmFqnResolver.facadeClassName("1foo.kt"))
    }

    @Test
    fun `a name with no valid characters still yields a class name`() {
        assertEquals("_Kt", JvmFqnResolver.facadeClassName(".kt"))
    }
}
