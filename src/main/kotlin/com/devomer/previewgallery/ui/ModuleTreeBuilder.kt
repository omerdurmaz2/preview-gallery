package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/**
 * Turns the already-filtered rows into a nested module tree, one level above [PackageTreeBuilder]'s package
 * nesting: a Gradle/IntelliJ module name is itself a path (`features.buy.basket`, `features:buy:checkout`), and
 * a flat list of those names reads as a wall of near-identical long labels. Splitting on both `.` and `:` covers
 * either separator style IntelliJ can report a module name in.
 *
 * The same compaction PackageTreeBuilder uses applies here: a module segment that holds no rows of its own and
 * has exactly one child module is merged with that child, its label becoming the joined segments — branching
 * should start only where module paths actually diverge, matching the IDE's own Project view. A module holding
 * rows of its own is never compacted away, even with a single child, since those rows need a row to hang from —
 * nor is a module holding only orphan snapshots (see [build]'s `orphans` parameter), for the same reason.
 *
 * Segments are collected in exact-match maps and only the *output* is sorted case-insensitively, for the same
 * reason [PackageTreeBuilder] does it that way: a map keyed by `CASE_INSENSITIVE_ORDER` would treat `Buy` and
 * `buy` as one key and silently drop a whole subtree. The same rule applies to a module's orphan snapshots: they
 * are sorted as a plain list, never collected into a case-insensitively-keyed map.
 *
 * IntelliJ prefixes every module name with the project name, so a real multi-module project's forest is often a
 * single artificial root ("MyApp") holding nothing but the real modules as children. That root is dropped when it
 * holds no previews, package branches or orphans of its own and has two or more children — a single-module
 * project is unaffected, since in that case the "one child" branch of the compaction rule above already joins
 * the project name with the module into one label.
 */
object ModuleTreeBuilder {

    fun <T : PreviewRow> build(rows: List<T>, orphans: List<T> = emptyList()): List<PreviewNode.ModuleNode> {
        val roots = LinkedHashMap<String, MutableModule<T>>()

        for (row in rows) {
            moduleFor(roots, row.moduleName)?.rows?.add(row)
        }
        for (orphan in orphans) {
            moduleFor(roots, orphan.moduleName)?.orphans?.add(orphan)
        }

        return stripSharedRoot(freezeAll(roots))
    }

    /** Walks (creating as needed) the segment path of [moduleName]; null when the name has no segments. */
    private fun <T : PreviewRow> moduleFor(
        roots: LinkedHashMap<String, MutableModule<T>>,
        moduleName: String,
    ): MutableModule<T>? {
        val segments = moduleName.split('.', ':').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null // Defensive: a blank module name has no path to nest under.
        var level = roots
        var module: MutableModule<T>? = null
        for (segment in segments) {
            val child = level.getOrPut(segment) { MutableModule(segment) }
            module = child
            level = child.children
        }
        return module
    }

    private fun <T : PreviewRow> freezeAll(level: Map<String, MutableModule<T>>): List<PreviewNode.ModuleNode> =
        level.values
            .map { freeze(it, "") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.segment })

    /** [prefix] carries the segments already compacted into this row, empty at a fresh branching point. */
    private fun <T : PreviewRow> freeze(module: MutableModule<T>, prefix: String): PreviewNode.ModuleNode {
        val label = if (prefix.isEmpty()) module.segment else "$prefix.${module.segment}"
        if (module.rows.isEmpty() && module.orphans.isEmpty() && module.children.size == 1) {
            return freeze(module.children.values.single(), label)
        }

        val modules = freezeAll(module.children)
        val tree = PackageTreeBuilder.build(module.rows)
        val orphanLeaves = module.orphans
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName })
            .map { PreviewNode.SnapshotLeaf(it) }
        return PreviewNode.ModuleNode(
            segment = label,
            count = module.rows.size + modules.sumOf { it.count },
            modules = modules,
            branches = tree.branches,
            previews = tree.previews,
            orphans = orphanLeaves.takeIf { it.isNotEmpty() }
                ?.let { PreviewNode.OrphanSnapshotBranch(it, it.size) },
        )
    }

    /**
     * Drops a single artificial project-name root so it does not show up as one noisy row above every real
     * module. Only when that root itself carries nothing (no previews, no package branches, no orphans) and
     * forks into two or more modules — a root with its own rows, or with a single child, is left alone (the
     * latter is already handled by [freeze]'s own compaction, joining the two into one label).
     */
    private fun stripSharedRoot(forest: List<PreviewNode.ModuleNode>): List<PreviewNode.ModuleNode> {
        val root = forest.singleOrNull() ?: return forest
        if (root.previews.isNotEmpty() || root.branches.isNotEmpty()) return forest
        if (root.orphans != null) return forest
        if (root.modules.size < 2) return forest
        return root.modules
    }

    private class MutableModule<T : PreviewRow>(val segment: String) {
        val children = LinkedHashMap<String, MutableModule<T>>()
        val rows = mutableListOf<T>()
        val orphans = mutableListOf<T>()
    }
}
