package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.DeviceOption

/**
 * One comparison tab. [device] == null is the **Original** view — the preview at its own `@Preview` config, which
 * never changes device. A non-null [device] is an ephemeral override: the same preview re-rendered on that device.
 */
data class ComparisonView(val id: Int, val device: DeviceOption?)

/**
 * The ephemeral tab state for the render pane: always an Original view at index 0, plus up to [maxExtras] override
 * views. Pure — no Swing, no AS, no rendered images (the panel owns those). Ids are monotonic and never reused, so a
 * closed-then-added tab is a distinct identity. [clearExtras] is called on every preview switch to free the extras.
 */
class ComparisonViewList(private val maxExtras: Int = DEFAULT_MAX_EXTRAS) {

    private val items = mutableListOf(ComparisonView(ORIGINAL_ID, null))
    private var nextId = ORIGINAL_ID + 1

    /** Original first, then the extras in add order. A defensive copy — callers cannot mutate the backing list. */
    val views: List<ComparisonView> get() = items.toList()

    /** Append an override view for [device]; null when the extras cap is already reached. */
    fun add(device: DeviceOption?): ComparisonView? {
        if (items.size - 1 >= maxExtras) return null
        val view = ComparisonView(nextId++, device)
        items.add(view)
        return view
    }

    /** Remove the extra with [id]; a no-op for [ORIGINAL_ID] (Original is never closable). */
    fun close(id: Int) {
        if (id == ORIGINAL_ID) return
        items.removeAll { it.id == id }
    }

    /** Set an extra view's device. Ignores [ORIGINAL_ID] and unknown ids (Original never changes device). */
    fun setDevice(id: Int, device: DeviceOption) {
        val index = items.indexOfFirst { it.id == id }
        if (index <= 0) return
        items[index] = items[index].copy(device = device)
    }

    /** Drop every extra view, returning to Original only (called on a preview selection change). */
    fun clearExtras() {
        val original = items.first()
        items.clear()
        items.add(original)
    }

    companion object {
        const val ORIGINAL_ID = 0
        const val DEFAULT_MAX_EXTRAS = 5
    }
}
