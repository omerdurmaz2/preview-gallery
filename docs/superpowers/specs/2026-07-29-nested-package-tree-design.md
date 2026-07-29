# Nested Package Tree — Branch by Package Segment, Expand/Collapse All

| | |
|---|---|
| **Scope** | Phase 9 — replace the gallery's flat `module -> full package name -> preview` list with a real nested tree that branches at each package segment, and add Project-view-style Expand All / Collapse All actions. |
| **Builds on** | [Phase 8](2026-07-28-show-all-previews-button-design.md) — the gallery is now also reachable from the editor's preview toolbar, which makes finding a preview in a long flat list the next bottleneck. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG9-N]` |

## Goal

In a multi-module codebase every module contributes a handful of long, similar package names (`com.trendyol.buy.basket.ui`, `com.trendyol.buy.checkout.ui`, …). Rendered as one flat row each, they read as noise: the shared prefix dominates and the part that actually distinguishes them sits at the end. Branching at the package segments turns that into structure the user already thinks in — under `buy` sit `basket` and `checkout`, and each of those holds its own domains. Expand All / Collapse All then make a deep tree navigable the same way the Project view is.

## Non-Goals

- **Grouping by anything other than module + package** (by preview group, by file, by annotation kind). The tree stays derived from what the index already holds.
- **Drag/drop, renaming, or any tree editing.** The gallery is read-only.
- **A flat/tree view toggle.** The nested tree replaces the flat list outright; a toggle is a second code path to keep working for no stated need.
- **Persisting expansion state across IDE restarts.** The user chose "modules open, everything below closed" as the fixed starting point.
- **Changing what a leaf shows or does.** Leaf rendering, selection, render pipeline and navigation stay exactly as they are.

## Current state (what this builds on)

`PreviewTreeModelBuilder.build(rows, query)` filters through `PreviewSearchFilter`, groups by `moduleName`, then by the full `indexed.packageName`, and returns `List<PreviewNode.ModuleNode>` — a two-level shape (`ModuleNode(moduleName, count, packages)` → `PackageNode(packageName, previews)` → `PreviewLeaf`). `PreviewGalleryPanel.applyFilter` rebuilds `DefaultMutableTreeNode`s from that shape on every keystroke and reload, calls `expandAll()` (a loop over `tree.rowCount`), then restores the previously selected entry — or a pending `revealEntry` id — via `findPath(entryId)`, which walks exactly three hard-coded levels. `PreviewTreeCellRenderer` switches on the sealed `PreviewNode` type for icon and text attributes. The toolbar holds `RefreshAction` and `ModuleFilterToggleAction`.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Replace `PackageNode` with a recursive `PackageBranch(segment, branches, previews, count)`. `segment` is the label shown on the row, `branches` its child packages, `previews` the leaves declared directly in that package, `count` the total number of previews beneath it. | A recursive node is the shape the data actually has. Keeping the model Swing-free preserves the existing property that grouping is testable without a `JTree`. |
| D2 | **Compact single-child chains.** A branch that holds no previews of its own and exactly one child branch is merged with that child, and its label becomes the joined segments (`com.trendyol`, then `buy`, then `basket`). | The user chose IntelliJ's "compact middle packages" behaviour. Branching starts where the packages actually fork, so `com` / `trendyol` never appear as their own rows. A branch with its own previews is never merged away — its leaves need a row to hang from. |
| D3 | Build the branch tree in a dedicated pure unit, `PackageTreeBuilder`, that takes the rows of one module and returns its top-level `PackageBranch`es. `PreviewTreeModelBuilder` keeps only the filter + module grouping and delegates the rest. | One responsibility per unit, and the compaction rules get their own test surface. `PreviewTreeModelBuilder`'s existing contract (filtered, case-insensitively sorted, counts reflect the filtered result) is unchanged at the module level. |
| D4 | Sorting stays case-insensitive at every level, comparing sorted lists rather than comparator-keyed maps. | Unchanged from today, and for the same reason the current KDoc records: a `TreeMap` under `CASE_INSENSITIVE_ORDER` would collapse two segments differing only in case into one and silently drop a subtree. |
| D5 | **Default expansion: module rows visible, everything below collapsed.** `expandAll()` on every rebuild is replaced by expanding only the module level. | A deep tree auto-expanded on every keystroke is unreadable; the user picked this explicitly. |
| D6 | **A non-blank search query auto-expands the matching branches** so results are visible without clicking; clearing the query returns to whatever the user had open before it (D10), or to D5's module level if they had opened nothing. Blank-but-not-empty text is not a query — the filter trims it, so treating it as one would force-expand an unfiltered tree. | Filtering already narrows the tree to matches — leaving them collapsed would hide the very rows the query selected. |
| D7 | Add Expand All / Collapse All to the panel toolbar using the platform's own `CommonActionsManager.createExpandAllAction` / `createCollapseAllAction` over a `DefaultTreeExpander(tree)`. | Same icons, same tooltips, same keyboard shortcuts as the Project view — the comparison the user drew. No hand-rolled traversal to maintain. |
| D8 | `findPath(entryId)` becomes recursive, and revealing an entry expands the branches along its path. | `revealEntry` (PG8) and the selection-restore in `applyFilter` both depend on `findPath`; with D5 the target is now usually inside a collapsed branch. |
| D9 | Every branch row shows its subtree preview count, grayed, the way module rows already do. A module's own count includes its default-package previews, so it can exceed the sum of its branch counts. | With chains compacted, a branch label alone does not say how much is under it; the count is what makes a collapsed row informative. |
| D10 | **A rebuild preserves what the user opened or closed.** Before each non-query rebuild the expanded rows are captured as label paths and re-expanded afterwards; D5's module-level default applies only to the very first build. Whether the outgoing tree was query-driven is recorded when that tree is built, not read from the incoming query text. A remembered path that no longer resolves is dropped. | `applyFilter` rebuilds the whole tree on every keystroke, on Refresh, and on every editor selection change, so an unconditional policy would undo the user's Collapse All (or their hand-opened branch) on an action that has nothing to do with the tree. Reading the incoming query would capture the query's own machine-made full expansion and replay it after the query is cleared. |
| D11 | **Collapse All keeps the current preview selected and rendered.** The platform's collapse re-anchors the tree selection to an ancestor, which would otherwise reach the render pipeline as "nothing selected" and tear down the render (and its comparison tabs). The panel suppresses selection notifications for the duration of the collapse and re-selects the preview leaf without expanding. | Tidying the tree must not discard a render that may have cost a Gradle build. The Project view has no render pane bound to its selection, so parity with it stops here. |
| D12 | The active-module tracker fires a rebuild only when the module actually changes, not on every editor selection change. | Most tab switches leave the tree byte-for-byte identical; rebuilding anyway is the churn that D10 would otherwise have to absorb. |

## Architecture

| Unit | Change | Responsibility |
|---|---|---|
| `ui/PreviewNode.kt` | modify | `PackageNode` → recursive `PackageBranch(segment, branches, previews, count)`; `ModuleNode.packages` becomes `ModuleNode.branches` |
| `ui/PackageTreeBuilder.kt` | **new** | Pure: rows of one module → sorted, compacted `List<PackageBranch>` |
| `ui/PreviewTreeModelBuilder.kt` | modify | Filter + module grouping only; delegates each module's packages to `PackageTreeBuilder` |
| `ui/PreviewTreeCellRenderer.kt` | modify | Render `PackageBranch` (package icon, grayed label, grayed count). Module and leaf rendering unchanged |
| `ui/PreviewGalleryPanel.kt` | modify | Recursive node construction and `findPath`; module-level default expansion; query-driven expansion; the two new toolbar actions |

The Swing-free model plus a pure builder keeps every grouping and compaction rule testable without a fixture; the panel keeps only what genuinely needs a `JTree`.

## Data flow

```
entries (PreviewIndexService)
  -> PreviewSearchFilter.filter(rows, query)          // unchanged
  -> group by moduleName, sort                        // PreviewTreeModelBuilder
       -> PackageTreeBuilder.build(moduleRows)        // split packageName into segments,
            -> segment tree                           //   nest, then compact single-child chains,
            -> compacted, sorted List<PackageBranch>  //   summing counts bottom-up
  -> ModuleNode(moduleName, count, branches)
  -> PreviewGalleryPanel.applyFilter
       -> recursive DefaultMutableTreeNode build
       -> expand module level (or matching branches when a query is active)
       -> restore selection / apply pending reveal via recursive findPath, expanding its path
```

## Error handling

| Situation | Behaviour |
|---|---|
| A preview in the default (empty) package | Its leaves hang directly off the module row — no empty-labelled branch is created. |
| Two segments differing only in case (`Buy` / `buy`) | Kept as separate branches, sorted case-insensitively next to each other (D4). |
| A branch that holds both previews and sub-packages | Never compacted (D2); its own leaves are listed after its child branches. |
| Query matches nothing | Unchanged: `State.NO_MATCH`, empty tree. |
| A revealed entry sits inside collapsed branches | D8 expands the path before selecting; if the id is unreachable the pending-reveal window closes as it does today. |

## Testing

| Test | Kind | Covers |
|---|---|---|
| `PackageTreeBuilderTest` | pure JUnit | forking point (`buy` → `basket`/`checkout`); a single-child chain compacts into one joined label; a branch with its own previews is not compacted; case-differing segments stay separate; empty package name; counts sum bottom-up |
| `PreviewTreeModelBuilderTest` (extended) | pure JUnit | module grouping and ordering unchanged; module count still reflects the filtered rows; each module's branches come from the builder |
| `PreviewGalleryPanelTest` (extended) | `BasePlatformTestCase` | modules expanded and sub-branches collapsed on load; a query expands the matching branch; `revealEntry` on a deep entry expands its path and selects it; selection still survives a rebuild |
| `PreviewTreeCellRendererTest` (extended) | `BasePlatformTestCase` | a branch row renders its label and count |

Manual gate (runIde, the project's usual verification for UI work): open the gallery in a multi-module project, confirm the tree branches at the forking package segment with compacted prefixes, that only modules are open initially, that typing a query opens the matching branches, and that the toolbar's Expand All / Collapse All behave like the Project view's.

## Risks

- **R1 — Compaction can surprise on a shallow project.** Where every module has exactly one package, each module shows a single joined row and the tree looks flatter than before. That is the correct outcome of D2, and the counts (D9) keep it informative.
- **R2 — `findPath` is on the selection-restore path**, which runs on every keystroke; a recursive walk over a deep tree runs per rebuild. Preview counts are in the hundreds, not millions, so the walk stays trivial — but it must stay allocation-light, not rebuild intermediate lists per level.
