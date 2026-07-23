package com.devomer.previewgallery.index

/**
 * One import statement, flattened to the facts the matcher needs.
 *
 * @param importedFqn for `import a.b.C` this is `a.b.C`; for `import a.b.*` it is the package `a.b`.
 * @param isAllUnder true for star imports.
 */
data class ImportInfo(
    val importedFqn: String,
    val alias: String?,
    val isAllUnder: Boolean,
)
