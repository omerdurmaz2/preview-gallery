# Preview catalogue, search and editor navigation

**Status:** shipped · **Phases:** `PG-1`–`PG-12` (bootstrap), `PG8-1`–`PG8-8`, `PG9-1`–`PG9-5`, `PG10-1`, `PG24-3` (gutter half only) · **Last change:** f22d222 [PG24-3] 2026-08-20

This is the plugin's foundation: a persistent, incremental index of every Compose `@Preview` (and `@PreviewTest`
snapshot) function, a searchable and filterable module → package → preview tree, and the entry points — the tool
window, Search Everywhere, an editor gutter icon and a button on Android Studio's own Compose-preview toolbar — that
get a developer from a component's name to its declaration. It shipped first, with zero rendering dependency
(Phase 1, 2026-07-23), and every later feature area (live rendering, comparison views, snapshot coverage and
verification, the MCP server) is layered on top of what this document covers.

## What the user gets

- A **Compose Gallery** tool window on the right edge of the IDE. It opens during indexing ("Waiting for indexing
  to finish…") and reloads once the project is smart again.
- A search field above the tree: case-insensitive substring match over preview name, function name and package,
  debounced 150 ms. Matching branches auto-expand; an empty query restores whatever was expanded before.
- A tree grouped **module → package → preview**, with IntelliJ Project-view-style compaction (a chain of segments
  that never forks and holds nothing of its own is joined into one label, e.g. `com.trendyol.buy`). Every branch
  shows a live count of the previews beneath it. A leaf shows its display name, a dimmed function name when the
  two differ, `private` / `@PreviewParameter` badges, and a coverage suffix (semantics: snapshot-testing doc).
- Toolbar: Refresh, "Show only the active editor's module" (persisted per project), a coverage-only toggle
  (snapshot-testing doc), and Expand All / Collapse All — the platform's own actions, same icons and shortcuts as
  the Project view.
- In-place states instead of the tree: `INDEXING`, "No @Preview functions found in this project", "No preview
  matches '\<query\>'", "The module filter is on and the active editor's module has no previews" (a fifth state,
  for the coverage filter, belongs to the snapshot-testing doc).
- Double-click or **Enter** on a preview row opens its declaration in the editor.
- **Search Everywhere** (Shift Shift) lists previews as `<name> — <module> · <package>` alongside classes and
  files; picking one navigates to the source and activates the gallery with that row selected.
- Two hand-offs from the editor, both doing the same thing: collapse the open Compose split-preview to code-only
  and open the gallery already showing the preview at that position —
  - a **Show all previews** button Android Studio's own Compose-preview toolbar (top right), and
  - a **Show all previews** gutter icon beside every `@Preview` function (and beside a bare `@PreviewTest`
    function), to the left of Android Studio's own run-preview icon.

## How it works

**Indexing.** `PreviewPsiScanner` walks the PSI of every Kotlin file whose raw text contains the substring
`"Preview"` (a cheap gate that skips parsing most files) and, for every top-level or top-level-`object`-member
function, uses `PreviewAnnotationMatcher` to classify its annotations from the file's own import list alone — a
`FileBasedIndex` indexer must never resolve references outside the file it indexes. `JvmFqnResolver` derives the
same JVM facade class name the Kotlin compiler would; `TargetExtractor` additionally records which composables the
body itself calls (consumed later by the snapshot-coverage matcher — see that doc). One `IndexedPreview` per
matching function is grouped by `composableFqn` and stored in `PreviewIndex`, a persistent `FileBasedIndexExtension`
serialized field-by-field by `PreviewValueExternalizer`.

**Query time.** `PreviewIndexService`, a project service, reads `PreviewIndex` via `FileBasedIndex.processAllKeys` /
`processValues`, resolves each hit's module (`ProjectFileIndex`) and `VirtualFile`, folds in snapshot rows the
snapshot-testing doc's `SnapshotSourceScanner` finds outside the index, and caches the joined, sorted
`List<PreviewEntry>` through `CachedValuesManager` against `PsiModificationTracker.MODIFICATION_COUNT` plus its own
manual-refresh tracker. Everything here runs off the EDT under a read action; it answers empty while the project is
dumb.

**Presentation.** `PreviewGalleryPanel` loads `findAll()` / `findOrphanSnapshots()` on open and on every
Refresh / index-completion / active-module-change signal, applies `PreviewModuleFilter` and the coverage filter
(snapshot doc), then `PreviewSearchFilter`, and hands the survivors to `PreviewTreeModelBuilder`. That delegates
module-name nesting to `ModuleTreeBuilder` and, one level down inside each module, package nesting to
`PackageTreeBuilder` — both compact single-child chains, sort case-insensitively, and roll counts up the tree. The
result (`PreviewNode`, a Swing-free sealed hierarchy) becomes `DefaultMutableTreeNode`s rendered by
`PreviewTreeCellRenderer`, and the panel reapplies whichever expansion policy fits: the module level by default,
full expansion of survivors while a query is active, or the user's own remembered expand/collapse state (captured
as node **label paths**, since a rebuild replaces every `TreePath`'s underlying node instances) — then restores the
previous selection without silently re-opening a branch the user had collapsed.

**Navigation in.** `PreviewSearchEverywhereContributor` runs the same `PreviewSearchFilter` over
`PreviewIndexService.findAll()` and, on selection, opens the source and activates the tool window with the row
selected (`PreviewGalleryPanel.selectEntry`). From the editor, `ShowAllPreviewsAction` (the toolbar button, injected
into Android Studio's own Compose-preview toolbar by `PreviewToolbarInjector` + `ToolbarLocator` +
`ActionGroupInjector`) and `ShowAllPreviewsLineMarkerProvider` (the gutter icon, gated by the same
`PreviewPsiScanner.isPreviewFunction` check the indexer itself uses) both resolve "the preview at this position" via
`CaretPreviewResolver` and hand off to one shared `PreviewGalleryNavigator`, which collapses the split editor
(`SplitEditorSwitcher`) and reveals the entry (`PreviewGalleryPanel.revealEntry`) — so the two entry points cannot
drift into different behaviour.

### Index (`index/`)

| Class / file | Responsibility |
|---|---|
| [PreviewIndex](../../../src/main/kotlin/com/devomer/previewgallery/index/PreviewIndex.kt) | `FileBasedIndexExtension<String, List<IndexedPreview>>`; key = composable FQN, `.kt`-only input filter with a cheap text pre-gate, version constant |
| [PreviewPsiScanner](../../../src/main/kotlin/com/devomer/previewgallery/index/PreviewPsiScanner.kt) | PSI → `IndexedPreview` for one `KtFile`; also exposes `isPreviewFunction`, shared with the gutter marker |
| [PreviewAnnotationMatcher](../../../src/main/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcher.kt) | Import-list-only classification of `@Preview` / `@PreviewParameter` / `@PreviewTest` |
| [TargetExtractor](../../../src/main/kotlin/com/devomer/previewgallery/index/TargetExtractor.kt) | The composables a preview body calls, by trailing-lambda descent; feeds the snapshot-coverage matcher (snapshot doc) |
| [JvmFqnResolver](../../../src/main/kotlin/com/devomer/previewgallery/index/JvmFqnResolver.kt) | Pure string derivation of the JVM facade class name and `composableFqn` |
| [ImportInfo](../../../src/main/kotlin/com/devomer/previewgallery/index/ImportInfo.kt) | One import statement, flattened to what the matcher needs |
| [PreviewValueExternalizer](../../../src/main/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizer.kt) | Field-by-field (de)serialization of `List<IndexedPreview>`; must move in lockstep with `PreviewIndex.VERSION` |

### Model (`model/`)

| Class / file | Responsibility |
|---|---|
| [IndexedPreview](../../../src/main/kotlin/com/devomer/previewgallery/model/IndexedPreview.kt) | File-local facts only — everything serialized into the index |
| [PreviewEntry](../../../src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt) | `IndexedPreview` + module + `VirtualFile`, resolved at query time; owns the stable `id` (`composableFqn#displayName`) |
| [PreviewRow](../../../src/main/kotlin/com/devomer/previewgallery/model/PreviewRow.kt) | `VirtualFile`-free view of a row, so search/tree code is unit-testable with no IDE fixture |
| [AnnotationKind](../../../src/main/kotlin/com/devomer/previewgallery/model/AnnotationKind.kt) | `ANDROIDX` / `JETBRAINS` / `UNKNOWN` |

### Query service

| Class / file | Responsibility |
|---|---|
| [PreviewIndexService](../../../src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt) | Project service: reads `PreviewIndex`, resolves module/file, joins snapshot rows, caches, exposes `findAll` / `findOrphanSnapshots` / `refresh` |

### Search and filters (`search/`)

| Class / file | Responsibility |
|---|---|
| [PreviewSearchFilter](../../../src/main/kotlin/com/devomer/previewgallery/search/PreviewSearchFilter.kt) | Case-insensitive substring match over display name, function name, package |
| [PreviewModuleFilter](../../../src/main/kotlin/com/devomer/previewgallery/search/PreviewModuleFilter.kt) | Restricts rows to the active editor's module |
| [PreviewCoverageFilter](../../../src/main/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilter.kt) | Snapshot-coverage filter — see the snapshot-testing doc; noted here only because it lives in this package and composes with the two filters above in the panel's toolbar |

### Search Everywhere (`searcheverywhere/`)

| Class / file | Responsibility |
|---|---|
| [PreviewSearchEverywhereContributor](../../../src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributor.kt) | `SearchEverywhereContributor<PreviewEntry>`; dumb-gated; navigates + activates the tool window on selection |
| [PreviewSearchEverywhereContributorFactory](../../../src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorFactory.kt) | Platform factory required by the extension point |

### Editor entry points (`editor/`)

| Class / file | Responsibility |
|---|---|
| [ShowAllPreviewsAction](../../../src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsAction.kt) | The toolbar-button / Find-Action entry point; resolves the clicked editor, delegates to the navigator |
| [ShowAllPreviewsLineMarkerProvider](../../../src/main/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsLineMarkerProvider.kt) | The gutter-icon entry point (PG24-3), anchored on a function's name identifier |
| [PreviewGalleryNavigator](../../../src/main/kotlin/com/devomer/previewgallery/editor/PreviewGalleryNavigator.kt) | Shared behaviour for both entry points: collapse editor, activate tool window, reveal the resolved entry |
| [CaretPreviewResolver](../../../src/main/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolver.kt) | Pure: `(entries, file, offset) -> PreviewEntry?`, "last preview declared at or before this position" |
| [PreviewToolbarInjector](../../../src/main/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjector.kt) | Project service; bounded-retry runtime injection of the action into Android Studio's Compose-preview toolbar |
| [ActionGroupInjector](../../../src/main/kotlin/com/devomer/previewgallery/editor/ActionGroupInjector.kt) | Pure: adds an action to a `DefaultActionGroup` at most once |
| [ToolbarLocator](../../../src/main/kotlin/com/devomer/previewgallery/editor/ToolbarLocator.kt) | Pure: finds `ActionToolbar`s in a Swing subtree by their `place` string |
| [SplitEditorSwitcher](../../../src/main/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcher.kt) | Collapses a Compose split editor (or a plain platform one) to code-only |

### Gallery tree and panel (`ui/`)

| Class / file | Responsibility |
|---|---|
| [PreviewGalleryToolWindowFactory](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryToolWindowFactory.kt) | Registers the `Compose Gallery` tool window; `DumbAware` |
| [PreviewGalleryPanel](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt) | The panel — covered here only for search field, tree build/expansion/selection and toolbar wiring; live rendering, comparison tabs and snapshot verification in the same 1700-line file belong to the rendering and snapshot docs |
| [PreviewTreeModelBuilder](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt) | Applies the query, then delegates module and package nesting |
| [ModuleTreeBuilder](../../../src/main/kotlin/com/devomer/previewgallery/ui/ModuleTreeBuilder.kt) | Nests rows by module-name path segments; compacts, sorts, strips a shared project-name root |
| [PackageTreeBuilder](../../../src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt) | Nests one module's rows by package segments; compacts, sorts |
| [PreviewNode](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt) | Swing-free sealed tree shape (`ModuleNode`, `PackageBranch`, `PreviewLeaf`, `SnapshotLeaf`, `OrphanSnapshotBranch`) |
| [PreviewTreeCellRenderer](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt) | Row rendering (icon, text, badges); covered here for module/package/preview rows — failure badges are a snapshot-doc concern |
| [ActiveModuleTracker](../../../src/main/kotlin/com/devomer/previewgallery/ui/ActiveModuleTracker.kt) | The active editor's module, change-detected so a rebuild fires only on a real change |
| [IndexingCompletionTracker](../../../src/main/kotlin/com/devomer/previewgallery/ui/IndexingCompletionTracker.kt) | Reloads on every indexing pass that actually moved `PreviewIndex`, not just the first |
| [IndexStampGate](../../../src/main/kotlin/com/devomer/previewgallery/ui/IndexStampGate.kt) | Pure: "did the index actually move since the last accepted signal?", fails open |
| [PersistentToggleAction](../../../src/main/kotlin/com/devomer/previewgallery/ui/PersistentToggleAction.kt) | Base for a `PropertiesComponent`-backed toolbar toggle |
| [ModuleFilterToggleAction](../../../src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt) | The "active module only" toggle |
| [RefreshAction](../../../src/main/kotlin/com/devomer/previewgallery/ui/RefreshAction.kt) | Invalidates `PreviewIndexService`'s cache and reloads |

### Extension points this area registers

| Point | Implementation | Since |
|---|---|---|
| `com.intellij.fileBasedIndex` | `PreviewIndex` | PG-5 |
| `com.intellij.toolWindow` (`Compose Gallery`) | `PreviewGalleryToolWindowFactory` | PG-9 |
| `com.intellij.searchEverywhereContributor` | `PreviewSearchEverywhereContributorFactory` | PG-11 |
| `com.intellij.codeInsight.lineMarkerProvider` (kotlin) | `ShowAllPreviewsLineMarkerProvider` | PG24-3 |
| `com.intellij.projectListeners` (`FileEditorManagerListener`) | `PreviewToolbarInjector.Listener` | PG8-6 |
| `<actions>` (id `PreviewGallery.ShowAllPreviews`) | `ShowAllPreviewsAction` | PG8-6 |
| `org.jetbrains.kotlin.supportsKotlinPluginMode` | declares `supportsK2="true"` (not an EP this area owns behaviour through, but required for every EP above to load under K2) | PG-12 |

See [plugin.xml](../../../src/main/resources/META-INF/plugin.xml). The `notificationGroup` and `postStartupActivity`
(MCP server) entries in the same file belong to the snapshot-testing doc.

## Key decisions

- Multipreview annotations are **not resolved** — only a direct `@Preview`, or a bare `@PreviewTest` (needed
  because the reference project's snapshots are marked through a custom multipreview no file-local indexer can
  see), is indexed. Still true in `PreviewPsiScanner.scan` today. — spec D4, [2026-07-23-preview-gallery-phase1-design.md](../../superpowers/specs/2026-07-23-preview-gallery-phase1-design.md)
- A function carrying several direct `@Preview` annotations collapses to **one** tree entry, labelled by the
  function name rather than any single config's `name`. — D4a; c6827cd / 15bb556 `[PG-4]`
- Module name, file and line are resolved **at query time**, never stored in the persistent index, so a Gradle
  sync cannot silently invalidate index correctness. This supersedes the parent spec's §8 data model, which bakes
  `moduleName` / `filePath` / `lineNumber` into one flat `PreviewEntry` — the code's split `IndexedPreview` /
  `PreviewEntry` is what shipped. — spec §3.2, [2026-07-23-preview-gallery-phase1-design.md](../../superpowers/specs/2026-07-23-preview-gallery-phase1-design.md)
- Annotation identification is import-list text matching only, never resolution — the rule a `FileBasedIndex`
  indexer must follow. — spec §3.3; `PreviewAnnotationMatcher`/`TargetExtractor` KDoc
- `SearchEverywhere` and the active-editor module filter shipped in v1; a coverage report and pinned modules were
  explicitly deferred. Coverage shipped later (snapshot doc, PG16); pinned modules has never shipped. — spec D5
- Search is a plain case-insensitive linear scan, "affordable" only under roughly 10k entries — no secondary
  index, no fuzzy matching. — spec §7.2 / D5; `PreviewSearchFilter` KDoc
- PG8: the toolbar button is injected at runtime by walking the Swing tree for the internal place string
  `"NlRhsConfigToolbar"`, because Android Studio builds that toolbar's `DefaultActionGroup` programmatically with
  no extension point to add to. Injection fails **silently** on a bounded retry timeout; the action stays
  reachable by id / Find Action. — PG8 design F1/D1/D2, [2026-07-28-show-all-previews-button-design.md](../../superpowers/specs/2026-07-28-show-all-previews-button-design.md)
- PG8: the caret resolves to a preview by "the entry with the greatest `offset <= caretOffset` in this file, else
  the file's first entry" — a heuristic over data the index already has, not a PSI walk. — D4
- PG8: the hand-off's three effects (collapse to code-only, activate the gallery, select an entry) are
  independent, so a file with no previews indexed yet still gets a code-only editor and an open, unselected
  gallery. — D7
- PG24-3: the gutter marker is an ordinary `LineMarkerProvider`, not a `RunLineMarkerContributor` — the latter
  would merge into Android Studio's run-gutter popup beside its own preview icon and need that popup opened
  before either icon could be clicked. — `ShowAllPreviewsLineMarkerProvider` KDoc, f22d222 `[PG24-3]`
- PG24-3: the gutter marker and the toolbar button now funnel through one `PreviewGalleryNavigator` and gate on
  the same `PreviewPsiScanner.isPreviewFunction` the indexer itself uses, so the two entry points and the tree
  can never disagree about which functions count as previews. This supersedes the PG8 design's architecture
  table, where `ShowAllPreviewsAction` performed the caret-resolve/switch/activate steps directly. — f22d222 `[PG24-3]`
- PG9: the tree is a Swing-free `ModuleNode -> PackageBranch -> PreviewLeaf` shape built by pure functions, kept
  that way specifically so grouping, compaction and sorting stay unit-testable without a `JTree`. — D1, D3, [2026-07-29-nested-package-tree-design.md](../../superpowers/specs/2026-07-29-nested-package-tree-design.md)
- PG9: a chain of segments that neither forks nor holds rows of its own is compacted into one joined label,
  matching the IDE's "compact middle packages"; a segment holding rows of its own (or, for a module, orphan
  snapshots) is never compacted away. — D2
- PG9: default expansion is the module level only; a live query force-expands every surviving row; clearing the
  query restores whatever was open before, captured as node **label paths** immediately before each non-query
  rebuild (a `TreePath` cannot survive a rebuild that replaces every node instance). — D5, D6, D10
- PG9: Collapse All suppresses the platform's own intermediate selection-change events and replays the original
  leaf selection afterwards, so tidying the tree cannot be read as "nothing selected" and tear down an in-flight
  or already-rendered comparison. — D11; `SelectionPreservingTreeExpander`
- PG9: the active-module tracker rebuilds the tree only when the resolved module name actually changes, not on
  every editor tab switch. — D12
- PG10: a module name is itself a dotted-or-colon path (`features.buy.basket`, `features:buy:checkout`);
  `ModuleTreeBuilder` nests it exactly like `PackageTreeBuilder` nests packages, one level up, and strips a
  single shared project-name root once the project has two or more real modules. No design doc exists for this
  phase — source is the commit and the class's own KDoc. — 7405d4f `[PG10-1]`
- K2 compatibility must be declared explicitly, or the Kotlin plugin treats this plugin as K2-incompatible and
  silently skips loading it — including the file-based index. — `<supportsKotlinPluginMode supportsK2="true"/>`, 557d5b0 `[PG-12]`

## Android Studio and platform internals relied on

- **File-based index.** `PreviewIndex extends FileBasedIndexExtension<String, List<IndexedPreview>>`, key =
  composable FQN via `EnumeratorStringDescriptor`, value externalized field-by-field by `PreviewValueExternalizer`.
  `dependsOnFileContent()` returns `true` because `com.intellij.util.indexing.PsiDependentIndex` does not exist in
  this SDK (`PreviewIndex` KDoc). `getVersion()` is a manual constant (currently `2`) that must be bumped on any
  layout change so a stale on-disk index is never read with the new externalizer — the bump from `1` to `2`
  (adding `isSnapshotTest`/`targets`) happened outside this area's phases, in `[PG13-5]` (snapshot doc), but the
  mechanism itself belongs here. The `InputFilter` gates on the `.kt` extension; the indexer itself then skips any
  file whose raw text does not contain the substring `"Preview"`, before PSI is ever built.
- **Smart / dumb mode.** `PreviewIndexService` answers empty while `DumbService.isDumb`; `PreviewGalleryPanel.reload()`
  defers via `DumbService.runWhenSmart`; `PreviewSearchEverywhereContributor.fetchElements` and the `isPreviewFunction`-gated
  gutter marker are likewise dumb-safe. On a large project the IDE leaves dumb mode several times while the index
  fills (`3,918 → 115,805 → 1,581 → 4` files across four passes, observed on `hepsi-android`), which a naive single
  `runWhenSmart` rides only once — `IndexingCompletionTracker` keeps listening on `DumbService.DUMB_MODE`, and
  `IndexStampGate` (comparing `FileBasedIndex.getIndexModificationStamp`, failing open on an unreadable stamp)
  filters that down to only the exits that actually moved `PreviewIndex`.
- **K2 support.** `plugin.xml` declares `<supportsKotlinPluginMode supportsK2="true"/>`, required because the
  plugin depends on `org.jetbrains.kotlin`; without it the Kotlin plugin drops the whole plugin at load time under
  K2. Accurate here because nothing in this area calls the Kotlin Analysis API — only front-end-agnostic PSI
  (`KtFile`, `KtNamedFunction`, import directives, annotation entries), per `plugin.xml`'s own comment.
- **PSI / read-action rules.** A `FileBasedIndex` indexer must not resolve references outside the file it indexes,
  which is why `PreviewAnnotationMatcher` and `TargetExtractor` work from import-list and callee-name **text**
  only. `PreviewIndexService.findAll`/`findOrphanSnapshots` must run under a read action, off the EDT (class KDoc);
  `PreviewGalleryPanel.reload()` and `PreviewGalleryNavigator.revealCaretPreview` both use
  `ReadAction.nonBlocking(...).finishOnUiThread(...)` for that reason.
- **`LineMarkerProvider` contract.** The platform calls `getLineMarkerInfo` for every visible PSI leaf, and an
  info anchored on a composite element is reported as an error by `LineMarkersPass`. `ShowAllPreviewsLineMarkerProvider`
  therefore rejects everything but a function's own name identifier — three field reads — before paying for
  `PreviewPsiScanner.isPreviewFunction`'s import-list walk; anchoring on the name identifier also matches
  `IndexedPreview.offset` exactly, so `CaretPreviewResolver` resolves the marker to the right function.
- **Editor-toolbar / split-editor internals.** `PreviewToolbarInjector` locates Android Studio's Compose-preview
  top-right toolbar by the undocumented place string `"NlRhsConfigToolbar"` (`ActionsToolbar.updateActionGroups`),
  found by a plain `UIUtil.uiTraverser` walk (`ToolbarLocator`) since no `<add-to-group>` or extension point
  reaches it. `SplitEditorSwitcher` casts the platform's `TextEditorWithPreview` to
  `com.android.tools.idea.common.editor.SplitEditor` to call `selectTextMode(true)` — guarded by `catch (LinkageError)`
  even though the dependency is mandatory, as cheap insurance against a future rename. `ShowAllPreviewsAction`
  reads `PlatformCoreDataKeys.FILE_EDITOR` rather than `CommonDataKeys.EDITOR`, because the design-surface
  toolbar's data context carries no plain editor key.
- **Tree / Swing internals.** Expand All / Collapse All are the platform's own `CommonActionsManager` actions over
  a `DefaultTreeExpander`. `SelectionPreservingTreeExpander` relies on a bytecode-verified call chain
  (`TreeUtil.collapseAll` → `Tree$ExpandImpl.collapsePath` → `internalSelect`) to know exactly which selection
  events the platform's own Collapse All fires. `PreviewGalleryPanel.capturedExpansion()` also relies on
  `com.intellij.ui.treeStructure.Tree`'s `ExpandImpl.getExpandedDescendants` excluding the invisible root by
  **value** equality — a bare `javax.swing.JTree` compares by reference instead, which the code defends against
  but does not depend on for this tree.
- **Compile target.** The project compiles against a local Android Studio install (`platformLocalPath`), currently
  2026.1.3 / platform 261, having moved there from Panda 4 / platform 253 in `[PG24-10]` (8667333, 2026-09-14).
  That move touched only `render/`; none of the AS-internal points this area depends on
  (`NlRhsConfigToolbar`, `SplitEditor`) have been re-verified against the new jars — see Open items.

## Tests

- [JvmFqnResolverTest](../../../src/test/kotlin/com/devomer/previewgallery/index/JvmFqnResolverTest.kt) — every facade-name derivation rule (capitalization, `@file:JvmName`, object container, default package, invalid/leading-digit characters)
- [PreviewAnnotationMatcherTest](../../../src/test/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcherTest.kt) — every import-matching case for `@Preview` / `@PreviewParameter` / `@PreviewTest` (FQN, aliased, star, ambiguous-star, unrelated)
- [PreviewPsiScannerTest](../../../src/test/kotlin/com/devomer/previewgallery/index/PreviewPsiScannerTest.kt) — end-to-end PSI scan: androidx/JetBrains, private, name/group/positional/non-literal arguments, `@file:JvmName`, object member, class member (unsupported), `@PreviewParameter` flag, aliased/star imports, `UNKNOWN` ambiguity, no-preview function ignored, multipreview wrapper **not** resolved (D4), repeated `@Preview` collapsing to one entry (D4a), offset = name identifier, `@PreviewTest` flagging + target extraction
- [PreviewIndexTest](../../../src/test/kotlin/com/devomer/previewgallery/index/PreviewIndexTest.kt) — the `FileBasedIndex` wiring itself: a preview file is indexed, a file without one contributes nothing, the key is the composable FQN, two previews in one file both appear
- [PreviewValueExternalizerTest](../../../src/test/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizerTest.kt) — round-trips every field, including nullables, an empty list, non-ASCII text, and the `isSnapshotTest`/`targets` fields `[PG13-5]` added
- [TargetExtractorTest](../../../src/test/kotlin/com/devomer/previewgallery/index/TargetExtractorTest.kt) — trailing-lambda descent, block bodies, argument lists excluded, several calls in the innermost lambda, non-PascalCase callees ignored, no-call body, de-duplication
- [PreviewSearchFilterTest](../../../src/test/kotlin/com/devomer/previewgallery/search/PreviewSearchFilterTest.kt) — case-insensitivity, substring vs. prefix, matches on function/package, query trimming; also covers `PreviewModuleFilter` (disabled no-op, active-module-only, no-active-module ⇒ empty) in the same file
- [PreviewCoverageFilterTest](../../../src/test/kotlin/com/devomer/previewgallery/search/PreviewCoverageFilterTest.kt) — snapshot doc's territory; noted only for completeness
- [PreviewSearchEverywhereContributorTest](../../../src/test/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorTest.kt) — matching / non-matching / empty pattern through `fetchElements`
- [CaretPreviewResolverTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/CaretPreviewResolverTest.kt) — caret inside/above/exactly-on a preview, other-file entries ignored, empty index
- [ActionGroupInjectorTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/ActionGroupInjectorTest.kt) — adds once, a second injection is a no-op, existing children survive
- [ToolbarLocatorTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/ToolbarLocatorTest.kt) — finds a nested toolbar by place, ignores a different place, null root yields nothing
- [SplitEditorSwitcherTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/SplitEditorSwitcherTest.kt) — a plain text editor is left alone rather than failing; a closed file is a no-op
- [PreviewToolbarInjectorTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/PreviewToolbarInjectorTest.kt) — only the pure `handlesFile` gate (Kotlin yes, XML/Java no); see Open items for what this does not cover
- [ShowAllPreviewsLineMarkerProviderTest](../../../src/test/kotlin/com/devomer/previewgallery/editor/ShowAllPreviewsLineMarkerProviderTest.kt) — a preview function is marked on its own name identifier, a non-preview function is not, a `@PreviewTest`-only function is still marked, exactly one leaf per file carries the marker, the marked offset matches the indexed offset
- [ActiveModuleTrackerTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ActiveModuleTrackerTest.kt) — no open file ⇒ no module; an open file reports its module; a same-module tab switch does not re-invoke the callback
- [IndexStampGateTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/IndexStampGateTest.kt) — moved/unmoved/unreadable stamps, first-signal-always-accepted, baseline bookkeeping, a full multi-pass indexing round
- [IndexingCompletionTrackerTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/IndexingCompletionTrackerTest.kt) — construction fires no reload; an unmoved index on dumb-mode-exit fires no reload; a moved index fires exactly one; a burst of exits collapses into one
- [ModuleTreeBuilderTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/ModuleTreeBuilderTest.kt) — dotted/colon nesting, single-child compaction, a module with its own rows (or only orphans) is never compacted away, counts sum the subtree, case-differing segments stay separate, shared-root stripping (dropped at 2+ children, kept when it only holds orphans), case-insensitive sort at every level
- [PackageTreeBuilderTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilderTest.kt) — chain collapse, compaction stops at the fork, a branch with its own previews is never compacted, counts, case handling, default-package previews hang off the root
- [PreviewTreeModelBuilderTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt) — module/package grouping, counts, sorting, a query pruning branches with no surviving leaves, a preview carrying its snapshot children, orphans under their own module, the query filtering previews and orphans independently
- [PersistentToggleActionTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PersistentToggleActionTest.kt) — defaults off, state round-trips through its own `isEnabled`, two toggles do not see each other's key, toggling invokes the callback
- [PreviewTreeCellRendererTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt) — module/branch/preview row icon+text+badges, unsupported styling, FQN tooltips, orphan-branch row; the failure-badge tests in the same file are snapshot doc's concern
- [PreviewIndexServiceTest](../../../src/test/kotlin/com/devomer/previewgallery/service/PreviewIndexServiceTest.kt) — entries carry the resolved module/file, sort order (module → package → display name, case-insensitive), an empty project yields nothing; the coverage-join tests in the same file are snapshot doc's concern
- [PreviewGalleryPanelTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt) — a single large fixture shared with the rendering and snapshot docs; the slice covered here: `NO_PREVIEWS`/`LOADED`/`NO_MATCH` state, selection surviving a rebuild, `revealEntry` (including a pending id and an unreachable one), expansion policy (module-only default, query expansion, collapsing back, Collapse All keeping the selection, a rebuild neither re-opening user-collapsed branches nor discarding user-expanded ones), a snapshot leaf hanging under its preview without being revealed by a plain load, Enter navigating (or not) from a module vs. a snapshot row, and the module/coverage filters composing with the state text

**Gaps:** `PreviewSearchEverywhereContributorTest` never exercises `processSelectedItem` (the tool-window
activation + `selectEntry` path) or the renderer's label text. `PreviewToolbarInjectorTest` covers only the pure
file-type gate — the actual injection/retry loop (`inject`, `scheduleAttempt`, the `Alarm` backoff, multi-editor
injection) has no automated test; the PG8 design's own testing table names this a manual `runIde` gate only.

## Open items

- [gap] Multipreview annotations (a custom annotation itself meta-annotated with `@Preview`) are still not
  resolved — a component whose only preview is such a wrapper never appears in the gallery. — spec D4/G2, [2026-07-23-preview-gallery-phase1-design.md](../../superpowers/specs/2026-07-23-preview-gallery-phase1-design.md); confirmed unchanged in `PreviewPsiScanner.scan`
- [idea] Pinned modules (a configured module list surfaced at the top of the tree) was explicitly deferred at v1
  and has not been picked up since — no such action or model exists anywhere under `ui/`. — spec D5; parent spec §13 Q4
- [limitation] A `@Preview` function declared inside a class is indexed and searchable but always carries
  `unsupportedReason` and can never render — a permanent v1 scope cut, not something a later phase attempted to
  lift. — spec §3.4/§8.1
- [limitation] `IndexedPreview.offset` is a declaration offset, not a body range, so `CaretPreviewResolver` (and
  both editor entry points) approximate "the preview at this position" as "the last one declared at or before
  it" — a caret or click far below the last preview in a long file still resolves to that last preview. — PG8 design R3, [2026-07-28-show-all-previews-button-design.md](../../superpowers/specs/2026-07-28-show-all-previews-button-design.md)
- [debt] `PreviewSearchEverywhereContributor.processSelectedItem` has no test coverage; only `fetchElements` is
  tested. — verified against `PreviewSearchEverywhereContributorTest.kt`
- [debt] `PreviewToolbarInjector`'s injection/retry logic is exercised only by manual `runIde` verification, never
  by an automated test. — PG8 design Testing table; verified against `PreviewToolbarInjectorTest.kt`
- [idea] Re-verify the Android-Studio-internal points this area depends on — `PreviewToolbarInjector`'s
  `"NlRhsConfigToolbar"` place string and `SplitEditorSwitcher`'s `SplitEditor` cast — against Android Studio
  2026.1.3 / platform 261. `[PG24-10]` (8667333, 2026-09-14) moved the compile target from 253 to 261 but touched
  only `render/`; nothing under `editor/` has been re-checked since (unverified either way — no breakage has been
  reported, but none has been looked for either).
- [limitation] Search and the module/coverage filters are a plain in-memory linear scan, an explicit "fine below
  roughly 10k entries" design assumption with no test or guard at scale. — spec §7.2; `PreviewSearchFilter` KDoc

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| `PG-1`–`PG-12` (bootstrap) | 2026-07-23 | bcc407c .. c6827cd (~35, incl. `PG-0` plan amendments) | Repo bootstrap; `PreviewIndex`/`PreviewPsiScanner`/`PreviewAnnotationMatcher`/`JvmFqnResolver`; `PreviewIndexService`; `PreviewSearchFilter`/`PreviewModuleFilter`; a first flat tree + cell renderer; the tool window; module filter toggle + refresh action; the SearchEverywhere contributor; the Phase 1 test suite |
| `PG8-1`–`PG8-8` (Show all previews button) | 2026-07-28 | aa09a49 .. 6b68fa8 | `CaretPreviewResolver`, `ActionGroupInjector`, `ToolbarLocator`, `SplitEditorSwitcher`; the button injected into Android Studio's own Compose-preview toolbar; `PreviewGalleryPanel.revealEntry` + pending-selection |
| `PG9-1`–`PG9-5` (nested package tree) | 2026-07-29 | 08ac7d5 .. 67febf4 | `PackageTreeBuilder` + recursive `PackageBranch`; module-only default expansion vs. query-driven full expansion; Expand All / Collapse All via `CommonActionsManager`; expansion state preserved across rebuilds (label-path memory); Collapse All keeps the selected preview/render alive |
| `PG10-1` (module rows nested by path) | 2026-07-29 | 7405d4f | `ModuleTreeBuilder`: dotted/colon module names nested one level above the package tree, same compaction rule, shared-project-root stripping |
| `PG24-3` (gutter half) | 2026-08-20 | f22d222 | `ShowAllPreviewsLineMarkerProvider` + `PreviewGalleryNavigator`: a gutter icon beside every indexed preview function, sharing the toolbar button's resolve/collapse/reveal behaviour through one navigator. (The same commit's default-render-device fix belongs to the rendering doc.) |

## References

- [Phase 1 design](../../superpowers/specs/2026-07-23-preview-gallery-phase1-design.md) and [plan](../../superpowers/plans/2026-07-23-preview-gallery-phase1.md)
- [Show all previews button design](../../superpowers/specs/2026-07-28-show-all-previews-button-design.md) and [plan](../../superpowers/plans/2026-07-28-show-all-previews-button.md)
- [Nested package tree design](../../superpowers/specs/2026-07-29-nested-package-tree-design.md) and [plan](../../superpowers/plans/2026-07-29-nested-package-tree.md)
- [compose-preview-gallery-plugin-spec.md](../../../compose-preview-gallery-plugin-spec.md) — §5 (UX), §6–7 (architecture, index, tree/search), §8 (parent data model, superseded — see Key decisions), §13 (open questions Q2/Q4)
- [Snapshot testing roadmap](../../snapshot-testing-roadmap.md) — "Existing plugin infrastructure" table lists exactly which classes in this area later phases build on
- [Feature overview](../../feature-overview.md), [README](../../../README.md), [CHANGELOG](../../../CHANGELOG.md)
