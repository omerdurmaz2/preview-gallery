package com.devomer.previewgallery.model

/**
 * One comparison copy's ephemeral property overrides, keyed by Android Studio's own `@Preview` picker property
 * names ("device", "apiLevel", "locale", "fontScale", "uiMode", "showSystemUi", "showBackground",
 * "backgroundColor", "widthDp", "heightDp", "wallpaper"). Values are the picker's own strings; `render/` maps
 * them onto AS types, so no AS type ever reaches `model/` or `ui/`. An empty map is an untouched copy of
 * Original. Insertion order is preserved so a tab title reads in the order the user edited.
 */
data class ViewOverride(val values: Map<String, String> = emptyMap()) {

    /** True when nothing is overridden — the copy renders exactly like Original. */
    val isDefault: Boolean get() = values.isEmpty()

    /** This override plus [name] = [value]; replaces an existing entry, keeps the rest, never mutates this one. */
    fun with(name: String, value: String): ViewOverride =
        ViewOverride(LinkedHashMap(values).apply { put(name, value) })
}
