package com.devomer.previewgallery.render

import com.intellij.openapi.diagnostic.thisLogger

/**
 * Reflectively verifies the Android Studio render API this plugin depends on is present with the expected
 * signatures on the running IDE build. Everything here is internal AS API, so a newer build can change it; the
 * probe turns that from a crash into a clean "renderer unavailable" (spec §5.3).
 */
object RenderApiProbe {

    private val required = listOf(
        "com.android.tools.idea.rendering.StudioRenderService" to listOf("getInstance"),
        "com.android.tools.rendering.RenderService" to listOf("taskBuilder"),
        "com.android.tools.rendering.RenderTask" to listOf("inflate", "render", "dispose"),
        "com.android.tools.rendering.RenderResult" to listOf("processImageIfNotDisposed"),
        "com.android.tools.idea.rendering.AndroidFacetRenderModelModule" to emptyList(),
        "com.android.tools.preview.SingleComposePreviewElementInstance" to emptyList(),
    )

    fun isAvailable(): Boolean = runCatching {
        required.all { (className, methods) ->
            val clazz = Class.forName(className, false, javaClass.classLoader)
            methods.all { name -> clazz.methods.any { it.name == name } }
        }
    }.onFailure { thisLogger().info("Render API unavailable on this IDE build: ${it.message}") }
        .getOrDefault(false)
}
