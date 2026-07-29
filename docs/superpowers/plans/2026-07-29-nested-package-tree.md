# Nested Package Tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the gallery's flat `module -> full package name -> preview` list with a tree that branches at each package segment (single-child chains compacted), starting with only modules expanded, plus Project-view-style Expand All / Collapse All actions.

**Architecture:** A new pure `PackageTreeBuilder` turns one module's rows into a recursive `PreviewNode.PackageBranch` tree and compacts single-child chains; `PreviewTreeModelBuilder` keeps only filtering and module grouping. `PreviewGalleryPanel` builds `DefaultMutableTreeNode`s recursively, expands the module level (or, with a query active, everything that survived filtering), and hosts the platform's own expand/collapse actions over a `DefaultTreeExpander`.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-29-nested-package-tree-design.md](../specs/2026-07-29-nested-package-tree-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, or an explicit null check.
- **All source, comments, docs and test names in English.**
- Commit message pattern: `[PG9-N] - Task name` (this feature's task ids are `PG9-1` … `PG9-4`).
- Sorting is **case-insensitive at every level** and must compare *sorted lists*, never comparator-keyed maps: a `TreeMap` under `CASE_INSENSITIVE_ORDER` treats `Buy` and `buy` as one key and would silently drop a whole subtree.
- Grouping, sorting and compaction live in the Swing-free model (`PreviewNode`, `PackageTreeBuilder`, `PreviewTreeModelBuilder`). `PreviewTreeCellRenderer` is presentation only. `PreviewGalleryPanel` owns only what genuinely needs a `JTree`.
- Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`). Tests needing a project use `BasePlatformTestCase` with backticked names starting `test `.
- **Build/test command:** `./gradlew test`. Do **not** run any `./gradlew` task while a `runIde` sandbox is running — kill the sandbox first. Do not run `./gradlew runIde` (the human runs that gate).

---

## File Structure

| Path | New? | Responsibility |
|---|---|---|
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt` | modify | `PackageNode` → recursive `PackageBranch`; `ModuleNode` carries branches + default-package leaves |
| `src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt` | new | Pure: one module's rows → sorted, compacted branches |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt` | modify | Filter + module grouping only; delegates packages |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt` | modify | Render a `PackageBranch` row (icon, label, count) |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` | modify | Recursive node build + `findPath`; expansion policy; expand/collapse actions |
| `src/test/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilderTest.kt` | new | Task 1 tests |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt` | modify | Task 2 tests |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt` | modify | Task 2 tests |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | modify | Task 3 + Task 4 tests |

---

### Task 1: PackageBranch model and PackageTreeBuilder

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilderTest.kt`

**Interfaces:**
- Consumes: `com.devomer.previewgallery.model.PreviewRow` (`indexed: IndexedPreview`, `moduleName: String`), `IndexedPreview.packageName: String`, `IndexedPreview.displayName: String`.
- Produces:
  - `PreviewNode.PackageBranch(segment: String, branches: List<PackageBranch>, previews: List<PreviewLeaf>, count: Int)`
  - `PreviewNode.ModuleNode(moduleName: String, count: Int, branches: List<PackageBranch>, previews: List<PreviewLeaf>)`
  - `PackageTreeBuilder.build(rows: List<T>): PackageTreeBuilder.PackageTree` where `PackageTree(branches: List<PreviewNode.PackageBranch>, previews: List<PreviewNode.PreviewLeaf>)`

This task keeps `PreviewNode.PackageNode` in place so the existing consumers still compile; Task 2 removes it.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilderTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageTreeBuilderTest {

    @Test fun `a single chain collapses into one branch`() {
        val tree = PackageTreeBuilder.build(listOf(testRow(packageName = "com.trendyol.buy.basket")))

        val branch = tree.branches.single()
        assertEquals("com.trendyol.buy.basket", branch.segment)
        assertTrue(branch.branches.isEmpty())
        assertEquals(1, branch.previews.size)
    }

    @Test fun `compaction stops at the forking segment`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.trendyol.buy.basket"),
                testRow(displayName = "B", packageName = "com.trendyol.buy.checkout"),
            ),
        )

        val buy = tree.branches.single()
        assertEquals("com.trendyol.buy", buy.segment)
        assertEquals(listOf("basket", "checkout"), buy.branches.map { it.segment })
    }

    @Test fun `a branch holding its own previews is never compacted away`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.buy"),
                testRow(displayName = "B", packageName = "com.buy.basket"),
            ),
        )

        val buy = tree.branches.single()
        assertEquals("com.buy", buy.segment)
        assertEquals(listOf("A"), buy.previews.map { it.row.indexed.displayName })
        assertEquals(listOf("basket"), buy.branches.map { it.segment })
    }

    @Test fun `counts sum the whole subtree`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.buy"),
                testRow(displayName = "B", packageName = "com.buy.basket"),
                testRow(displayName = "C", packageName = "com.buy.checkout"),
            ),
        )

        assertEquals(3, tree.branches.single().count)
    }

    @Test fun `segments differing only in case stay separate`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "A", packageName = "com.Buy"),
                testRow(displayName = "B", packageName = "com.buy"),
            ),
        )

        assertEquals(listOf("com.Buy", "com.buy"), tree.branches.map { it.segment }.sorted())
        assertEquals(2, tree.branches.size)
    }

    @Test fun `branches and previews are sorted case-insensitively`() {
        val tree = PackageTreeBuilder.build(
            listOf(
                testRow(displayName = "zebra", packageName = "com.root.zeta"),
                testRow(displayName = "Apple", packageName = "com.root.alpha"),
                testRow(displayName = "banana", packageName = "com.root.alpha"),
            ),
        )

        val root = tree.branches.single()
        assertEquals(listOf("alpha", "zeta"), root.branches.map { it.segment })
        assertEquals(
            listOf("Apple", "banana"),
            root.branches.first().previews.map { it.row.indexed.displayName },
        )
    }

    @Test fun `previews in the default package hang off the tree root`() {
        val tree = PackageTreeBuilder.build(listOf(testRow(displayName = "A", packageName = "")))

        assertTrue(tree.branches.isEmpty())
        assertEquals(listOf("A"), tree.previews.map { it.row.indexed.displayName })
    }

    @Test fun `no rows yields an empty tree`() {
        val tree = PackageTreeBuilder.build(emptyList<com.devomer.previewgallery.model.PreviewRow>())

        assertTrue(tree.branches.isEmpty())
        assertTrue(tree.previews.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PackageTreeBuilderTest"`
Expected: compilation failure — `Unresolved reference: PackageTreeBuilder`.

- [ ] **Step 3: Write minimal implementation**

Replace the contents of `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/** A Swing-free tree shape, so grouping can be tested without a `JTree`. */
sealed interface PreviewNode {

    data class ModuleNode(
        val moduleName: String,
        val count: Int,
        val branches: List<PackageBranch>,
        /** Previews declared in the default (empty) package: they hang directly off the module row. */
        val previews: List<PreviewLeaf> = emptyList(),
    ) : PreviewNode

    /**
     * One row of the package tree. [segment] is the label as shown — after compaction it can carry several
     * joined package segments (`com.trendyol`), which is why it is not called `name`. [count] is the number of
     * previews in the whole subtree, so a collapsed row still says how much it holds.
     */
    data class PackageBranch(
        val segment: String,
        val branches: List<PackageBranch>,
        val previews: List<PreviewLeaf>,
        val count: Int,
    ) : PreviewNode

    data class PackageNode(
        val packageName: String,
        val previews: List<PreviewLeaf>,
    ) : PreviewNode

    data class PreviewLeaf(val row: PreviewRow) : PreviewNode
}
```

Create `src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/**
 * Turns one module's rows into a nested package tree.
 *
 * Chains that neither fork nor hold previews of their own are compacted into a single row
 * (`com.trendyol.buy` rather than `com` > `trendyol` > `buy`), matching the IDE's own "compact middle packages"
 * behaviour: branching should start where the packages actually diverge. A branch that holds previews is never
 * compacted away — its leaves need a row to hang from.
 *
 * Segments are collected in exact-match maps and only the *output* is sorted case-insensitively: a map ordered
 * by `CASE_INSENSITIVE_ORDER` would treat `Buy` and `buy` as one key and silently drop a subtree.
 */
object PackageTreeBuilder {

    data class PackageTree(
        val branches: List<PreviewNode.PackageBranch>,
        /** Leaves with no package at all; the caller hangs them off its own row. */
        val previews: List<PreviewNode.PreviewLeaf>,
    )

    fun <T : PreviewRow> build(rows: List<T>): PackageTree {
        val roots = LinkedHashMap<String, MutableBranch>()
        val rootPreviews = mutableListOf<PreviewNode.PreviewLeaf>()

        for (row in rows) {
            val leaf = PreviewNode.PreviewLeaf(row)
            val segments = row.indexed.packageName.split('.').filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                rootPreviews += leaf
                continue
            }
            var level = roots
            var branch: MutableBranch? = null
            for (segment in segments) {
                val child = level.getOrPut(segment) { MutableBranch(segment) }
                branch = child
                level = child.children
            }
            branch?.previews?.add(leaf)
        }

        return PackageTree(freezeAll(roots), sortLeaves(rootPreviews))
    }

    private fun freezeAll(level: Map<String, MutableBranch>): List<PreviewNode.PackageBranch> =
        level.values
            .map { freeze(it, "") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.segment })

    /** [prefix] carries the segments already compacted into this row, empty at a fresh branching point. */
    private fun freeze(branch: MutableBranch, prefix: String): PreviewNode.PackageBranch {
        val label = if (prefix.isEmpty()) branch.segment else "$prefix.${branch.segment}"
        val onlyChild = branch.children.values.singleOrNull()
        if (branch.previews.isEmpty() && onlyChild != null) return freeze(onlyChild, label)

        val branches = freezeAll(branch.children)
        val previews = sortLeaves(branch.previews)
        return PreviewNode.PackageBranch(
            segment = label,
            branches = branches,
            previews = previews,
            count = previews.size + branches.sumOf { it.count },
        )
    }

    private fun sortLeaves(leaves: List<PreviewNode.PreviewLeaf>): List<PreviewNode.PreviewLeaf> =
        leaves.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.row.indexed.displayName })

    private class MutableBranch(val segment: String) {
        val children = LinkedHashMap<String, MutableBranch>()
        val previews = mutableListOf<PreviewNode.PreviewLeaf>()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PackageTreeBuilderTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt src/test/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilderTest.kt
git commit -m "[PG9-1] - Build a compacted package branch tree"
```

---

### Task 2: Switch the gallery onto the nested model

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt` (the `PackageNode` branch of the `when`)
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt` (delete `PackageNode`)
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (node construction inside `applyFilter`, and `findPath`)
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt`, `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt`

**Interfaces:**
- Consumes: `PackageTreeBuilder.build(rows): PackageTree` with `branches` / `previews` (Task 1), `PreviewNode.ModuleNode(moduleName, count, branches, previews)`, `PreviewNode.PackageBranch(segment, branches, previews, count)`.
- Produces: `PreviewTreeModelBuilder.build(rows, query): List<PreviewNode.ModuleNode>` — same signature as today, nested content.

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt`, replace the two tests that reach into `.packages` — `groups by module then package` and `everything is sorted alphabetically` — with:

```kotlin
    @Test
    fun `groups by module then package branch`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.b", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.c", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("app", "design"), modules.map { it.moduleName })
        // com forks into a and b, so the shared prefix becomes one row with two children.
        val app = modules.first().branches.single()
        assertEquals("com", app.segment)
        assertEquals(listOf("a", "b"), app.branches.map { it.segment })
        // design has a single chain, so it compacts to one row.
        assertEquals(listOf("com.c"), modules.last().branches.map { it.segment })
    }

    @Test
    fun `everything is sorted alphabetically`() {
        val rows = listOf(
            testRow(displayName = "Zebra", packageName = "com.z", moduleName = "zeta"),
            testRow(displayName = "Apple", packageName = "com.a", moduleName = "alpha"),
            testRow(displayName = "Banana", packageName = "com.a", moduleName = "alpha"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("alpha", "zeta"), modules.map { it.moduleName })
        assertEquals(
            listOf("Apple", "Banana"),
            modules.first().branches.single().previews.map { it.row.indexed.displayName },
        )
    }

    @Test
    fun `previews in the default package hang off the module row`() {
        val rows = listOf(testRow(displayName = "A", packageName = "", moduleName = "app"))

        val module = PreviewTreeModelBuilder.build(rows, "").single()

        assertTrue(module.branches.isEmpty())
        assertEquals(listOf("A"), module.previews.map { it.row.indexed.displayName })
    }
```

In `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt`, replace the test `a package row uses the package icon and grey text` with:

```kotlin
    @Test
    fun `a branch row uses the package icon, grey label, and a small grey count`() {
        val renderer = render(PreviewNode.PackageBranch("com.example", emptyList(), emptyList(), 2))

        assertEquals(AllIcons.Nodes.Package, renderer.icon)
        assertEquals(
            listOf(
                "com.example" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
                "  (2)" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
        assertNull(renderer.toolTipText)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewTreeModelBuilderTest" --tests "com.devomer.previewgallery.ui.PreviewTreeCellRendererTest"`
Expected: compilation failure — `Unresolved reference: branches` on `ModuleNode`, and `PackageBranch` not accepted by the renderer's `when`.

- [ ] **Step 3: Write the implementation**

Replace the body of `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/**
 * Builds the module -> package branch -> preview tree. Module counts reflect the filtered result, not the
 * whole project.
 *
 * Only the filtering and the module level live here; [PackageTreeBuilder] owns the nesting and compaction of
 * package segments below each module.
 *
 * Modules sort case-insensitively, matching the search filter, so a freeform `@Preview(name = ...)` does not
 * sort away from the PascalCase names around it. The level sorts a list rather than building a comparator-keyed
 * map: a `TreeMap` ordered by `CASE_INSENSITIVE_ORDER` treats names differing only in case as one key, which
 * would silently drop a whole module.
 */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, query: String): List<PreviewNode.ModuleNode> =
        PreviewSearchFilter.filter(rows, query)
            .groupBy { it.moduleName }
            .entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .map { (moduleName, moduleRows) ->
                val tree = PackageTreeBuilder.build(moduleRows)
                PreviewNode.ModuleNode(
                    moduleName = moduleName,
                    count = moduleRows.size,
                    branches = tree.branches,
                    previews = tree.previews,
                )
            }
}
```

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`, delete the whole `PackageNode` data class (Task 1 left it only so the old consumers kept compiling).

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt`, replace the `is PreviewNode.PackageNode ->` branch with:

```kotlin
            is PreviewNode.PackageBranch -> {
                icon = AllIcons.Nodes.Package
                append(node.segment, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
```

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, replace the node-construction block inside `applyFilter`:

```kotlin
            treeRoot.removeAllChildren()
            modules.forEach { module ->
                val moduleNode = DefaultMutableTreeNode(module)
                module.packages.forEach { pkg ->
                    val packageNode = DefaultMutableTreeNode(pkg)
                    pkg.previews.forEach { packageNode.add(DefaultMutableTreeNode(it)) }
                    moduleNode.add(packageNode)
                }
                treeRoot.add(moduleNode)
            }
```

with:

```kotlin
            treeRoot.removeAllChildren()
            modules.forEach { module ->
                val moduleNode = DefaultMutableTreeNode(module)
                // Branches before leaves at every level: the leaves of a row are its own previews, and burying
                // them above the sub-packages would make a deep tree read as if the packages belonged to them.
                module.branches.forEach { addBranch(moduleNode, it) }
                module.previews.forEach { moduleNode.add(DefaultMutableTreeNode(it)) }
                treeRoot.add(moduleNode)
            }
```

and add these two private helpers next to `findPath` (replacing the existing `findPath` entirely):

```kotlin
    private fun addBranch(parent: DefaultMutableTreeNode, branch: PreviewNode.PackageBranch) {
        val node = DefaultMutableTreeNode(branch)
        branch.branches.forEach { addBranch(node, it) }
        branch.previews.forEach { node.add(DefaultMutableTreeNode(it)) }
        parent.add(node)
    }

    /**
     * Depth-first search for the leaf carrying [entryId]. Runs on every rebuild (selection restore), so it walks
     * children by index rather than materialising a list per level.
     */
    private fun findPath(entryId: String): TreePath? = findPath(treeRoot, entryId)

    private fun findPath(node: DefaultMutableTreeNode, entryId: String): TreePath? {
        val entry = (node.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
        if (entry?.id == entryId) return TreePath(node.path)
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            findPath(child, entryId)?.let { return it }
        }
        return null
    }
```

The now-unused `java.util.Collections` import goes with the old `findPath`; remove it if nothing else uses it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.*"`
Expected: PASS — `PackageTreeBuilderTest`, `PreviewTreeModelBuilderTest`, `PreviewTreeCellRendererTest` and `PreviewGalleryPanelTest` all green. `PreviewGalleryPanelTest` is unchanged in this task and must stay green: selection and reveal still work, only the shape below a module changed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt
git commit -m "[PG9-2] - Render the gallery as a nested package tree"
```

---

### Task 3: Expansion policy

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (`expandAll`, its call site in `applyFilter`, `selectEntry`)
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: `PreviewNode.ModuleNode.moduleName`, `PreviewNode.PackageBranch.segment`, `PreviewNode.PreviewLeaf.row.indexed.displayName` (Task 1), the recursive `findPath` (Task 2).
- Produces: `@TestOnly fun visibleRowLabelsForTest(): List<String>` on `PreviewGalleryPanel` — the label of every currently visible tree row, top to bottom.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`, inside the class:

```kotlin
    private fun twoDomainProject() {
        myFixture.addFileToProject(
            "Basket.kt",
            """
            package com.example.buy.basket

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BasketPreview() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Checkout.kt",
            """
            package com.example.buy.checkout

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun CheckoutPreview() {}
            """.trimIndent(),
        )
    }

    fun `test only the module level is expanded on load`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        // The module row and its single top branch are visible; basket and checkout are still collapsed.
        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("com.example.buy"))
        assertFalse(labels.toString(), labels.contains("basket"))
        assertFalse(labels.toString(), labels.contains("BasketPreview"))
    }

    fun `test a query expands the branches that survived filtering`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        panel.applyQueryForTest("basket")

        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("BasketPreview"))
        assertFalse(labels.toString(), labels.contains("CheckoutPreview"))
    }

    fun `test clearing the query collapses back to the module level`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.applyQueryForTest("basket")

        panel.applyQueryForTest("")

        assertFalse(panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }

    fun `test revealing a deep entry expands its path and selects it`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "CheckoutPreview" }

        panel.revealEntry(entry.id)

        assertEquals(entry.id, panel.selectedEntryIdForTest())
        assertTrue(panel.visibleRowLabelsForTest().contains("CheckoutPreview"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: compilation failure — `Unresolved reference: visibleRowLabelsForTest`.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, replace `expandAll` with:

```kotlin
    /**
     * A query has already pruned the tree to the matching rows, so opening everything shows exactly the
     * results; with no query the tree is the whole project and only the module level opens — a deep tree
     * expanded on every keystroke is unreadable.
     */
    private fun applyExpansionPolicy() {
        if (searchField.text.isNotEmpty()) {
            var row = 0
            while (row < tree.rowCount) {
                tree.expandRow(row)
                row++
            }
            return
        }
        for (index in 0 until treeRoot.childCount) {
            val moduleNode = treeRoot.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            tree.expandPath(TreePath(moduleNode.path))
        }
    }
```

Replace the `expandAll()` call inside `applyFilter` with `applyExpansionPolicy()`.

Replace `selectEntry` so the path is opened before the selection lands (with the module level as the only expanded state, a revealed entry is otherwise inside a collapsed branch):

```kotlin
    fun selectEntry(entryId: String) {
        val path = findPath(entryId) ?: return
        path.parentPath?.let { tree.expandPath(it) }
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }
```

Add the test hook next to `selectedEntryIdForTest`:

```kotlin
    /** The label of every visible row, top to bottom — expansion state made assertable without a renderer. */
    @TestOnly
    fun visibleRowLabelsForTest(): List<String> = (0 until tree.rowCount).mapNotNull { row ->
        when (val node = (tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is PreviewNode.ModuleNode -> node.moduleName
            is PreviewNode.PackageBranch -> node.segment
            is PreviewNode.PreviewLeaf -> node.row.indexed.displayName
            else -> null
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: PASS — the four new tests plus the eight that were already there.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -m "[PG9-3] - Expand modules by default and open query matches"
```

---

### Task 4: Expand All / Collapse All actions

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (the toolbar `DefaultActionGroup` in `init`)
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: `visibleRowLabelsForTest()` (Task 3), the tree built in Task 2.
- Produces: `@TestOnly fun treeExpanderForTest(): TreeExpander` on `PreviewGalleryPanel` — the same `DefaultTreeExpander` instance the toolbar actions drive.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`, inside the class:

```kotlin
    fun `test the tree expander opens every row`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        panel.treeExpanderForTest().expandAll()

        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("BasketPreview"))
        assertTrue(labels.toString(), labels.contains("CheckoutPreview"))
    }

    fun `test the tree expander collapses back to the modules`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.treeExpanderForTest().expandAll()

        panel.treeExpanderForTest().collapseAll()

        assertFalse(panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest"`
Expected: compilation failure — `Unresolved reference: treeExpanderForTest`.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`, add the field next to the other tree fields (near `private val tree = Tree(treeModel)`):

```kotlin
    private val treeExpander = DefaultTreeExpander(tree)
```

Replace the toolbar action group in `init` with:

```kotlin
        // The platform's own expand/collapse actions: same icons, tooltips and shortcuts as the Project view,
        // which is the behaviour a user coming from that tree expects.
        val commonActions = CommonActionsManager.getInstance()
        val actionGroup = DefaultActionGroup(
            RefreshAction(project) { reload() },
            ModuleFilterToggleAction(project) { applyFilter() },
            commonActions.createExpandAllAction(treeExpander, this),
            commonActions.createCollapseAllAction(treeExpander, this),
        )
```

Add the test hook next to `visibleRowLabelsForTest`:

```kotlin
    /** The expander the toolbar's expand/collapse actions drive. */
    @TestOnly
    fun treeExpanderForTest(): TreeExpander = treeExpander
```

Add the imports:

```kotlin
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.TreeExpander
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew build test`
Expected: BUILD SUCCESSFUL, every test green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -m "[PG9-4] - Add Expand All and Collapse All to the gallery toolbar"
```

---

## Manual gate (the human runs this)

`./gradlew runIde`, then in the sandbox project:

1. Open the Compose Gallery. Confirm each module row is open and its sub-branches are closed.
2. Confirm the package rows branch at the forking segment, with the shared prefix compacted into one row (`com.example.buy` → `basket`, `checkout`).
3. Confirm every branch row shows a preview count, and that a module's branch counts add up to its own count (a module whose previews sit in the default package legitimately counts more than its branches do).
4. Type a query; confirm the matching branches open by themselves and the preview is visible without clicking. Clear it; confirm the tree returns to what you had open before typing (the module level on a freshly opened gallery).
5. Click Expand All, then Collapse All in the gallery toolbar; confirm they behave like the Project view's.
6. Select a preview so it renders, add a comparison view tab, then click Collapse All: confirm the preview stays selected, the render stays on screen and the comparison tabs survive.
7. Expand a branch by hand, then switch editor tabs a few times: confirm it stays open. Collapse All, switch tabs again: confirm it stays closed.
8. Use the editor's "Show all previews" button on a deep package; confirm the gallery opens that preview's path and selects it.
9. Close the sandbox before running any further Gradle task.

## Verification checklist

- [ ] `./gradlew build test` is green with no sandbox running.
- [ ] No `!!` anywhere in the new code.
- [ ] `PreviewNode.PackageNode` is gone; nothing references `.packages`.
- [ ] Compaction, nesting and sorting live in `PackageTreeBuilder`, not in the panel or the renderer.
- [ ] The manual gate's steps 1, 2, 4 and 5 all passed.
