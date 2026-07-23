package com.devomer.previewgallery.render

import com.intellij.openapi.project.Project

/**
 * Renders a composable by FQN through Android Studio's layoutlib pipeline. The ONLY home (with
 * [RenderModelResolver]) for AS-internal render API. Real rendering arrives in Task 2.
 */
class LiveRenderer(private val project: Project) {

    fun isAvailable(): Boolean = RenderApiProbe.isAvailable()
}
