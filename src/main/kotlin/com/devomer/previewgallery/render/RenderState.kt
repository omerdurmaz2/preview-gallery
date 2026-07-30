package com.devomer.previewgallery.render

/**
 * What the render panel is currently showing.
 *
 * Every value but the last two is published by [RenderPipeline]. [REFERENCE] and [NO_REFERENCE] belong to a
 * snapshot row instead, which is never rendered at all (spec D8): the panel enters them directly from the
 * committed reference PNGs, and the pipeline neither produces nor consumes them.
 */
enum class RenderState { IDLE, RENDERING, LIVE, NEEDS_BUILD, FAILED, UNSUPPORTED, REFERENCE, NO_REFERENCE }
