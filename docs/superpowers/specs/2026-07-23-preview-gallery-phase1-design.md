# Preview Gallery — Bootstrap + Phase 1 Design

| | |
|---|---|
| **Status** | Approved — ready for implementation planning |
| **Date** | 2026-07-23 |
| **Scope** | Repository bootstrap + Phase 1 of `compose-preview-gallery-plugin-spec.md` |
| **Parent spec** | [compose-preview-gallery-plugin-spec.md](../../../compose-preview-gallery-plugin-spec.md) |
| **Target IDE** | Android Studio Panda 4 — build `AI-253.32098.37.2534.15336583` (platform branch 253) |
| **Plugin id** | `com.devomer.previewgallery` |

---

## 1. Scope

This document covers **only** the repository bootstrap and Phase 1 of the parent spec: a project-wide, searchable index of Jetpack Compose `@Preview` functions, presented in a tool window, with navigation to source.

**Phase 1 carries no rendering risk.** It ships standalone value — project-wide preview discovery and navigation — and remains useful even if every rendering approach in later phases fails.

Phases 0 (rendering spike), 2 (live renderer, caches, build-on-demand) and 3 (CI-populated cache) are out of scope and will each get their own design document.

### 1.1 Decisions taken during design

| # | Question | Decision | Rationale |
|---|---|---|---|
| D1 | First slice to build | Bootstrap + Phase 1 | Zero rendering risk; delivers discovery and navigation independently of the Phase 0 gate |
| D2 | Build target | Local Android Studio install via `local(...)` | Compiled API is exactly the executed API; no version-string guessing; matters most in Phase 2 where AS-internal render APIs are used |
| D3 | Tool window layout | Vertical split; top half split horizontally into tree (left) and detail panel (right); bottom half reserved for the preview render area | Detail panel is useful in Phase 1 on its own; the render area skeleton is in place for Phase 2 without a UI rewrite |
| D4 | Multipreview annotations | **Not resolved in v1** — only functions annotated directly with `@Preview` are indexed | Smallest index and data model. Accepted cost: components that only use custom wrappers (`PreviewComponent`, `PrimusThemePreview`, …) do not appear in the gallery |
| D5 | Optional features | `SearchEverywhere` contributor **in**, active-editor module filter **in**; pinned modules **out**, preview-coverage report **out** | Keeps v1 focused on discovery; the two accepted extras are cheap and directly serve discovery |
| D6 | Indexing strategy | Custom `FileBasedIndex` (parent spec §7.1) | Persistent across restarts, incremental, minimal coupling to Kotlin plugin internals, reused unchanged by Phases 2 and 3 |
| D7 | Group / package name | `com.devomer.previewgallery` | — |

---

## 2. Bootstrap

The repository is currently an unmodified clone of the *IntelliJ Platform Plugin Template* 2.6.0.

### 2.1 Project identity

| Item | Current | Target |
|---|---|---|
| `rootProject.name` | `IntelliJ Platform Plugin Template` | `preview-gallery` |
| `group` | `org.jetbrains.plugins.template` | `com.devomer.previewgallery` |
| `version` | `2.6.0` | `0.1.0` |
| Source package | `org.jetbrains.plugins.template` | `com.devomer.previewgallery` |
| Plugin id | `org.jetbrains.plugins.template` | `com.devomer.previewgallery` |
| Plugin name | `IntelliJ Platform Plugin Template` | `Compose Preview Gallery` |

### 2.2 Platform dependency

```kotlin
// build.gradle.kts
dependencies {
    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugins("org.jetbrains.kotlin", "org.jetbrains.android")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}
```

```properties
# gradle.properties
platformLocalPath = /Users/odurmaz/Applications/Android Studio.app
```

- `org.jetbrains.kotlin` is required for Kotlin PSI at index time.
- `org.jetbrains.android` is required by the parent spec for Phase 2 and is declared now so the plugin only ever loads in an Android-capable IDE.
- `sinceBuild = 253`; `untilBuild` left open, per parent spec §7.7 — an aggressive `untilBuild` converts a soft failure into a plugin that refuses to load.
- Kotlin JVM toolchain 21, matching the JBR bundled with Android Studio 253.

### 2.3 Template cleanup

**Delete:** `MyProjectService`, `MyProjectActivity`, `MyToolWindowFactory`, `MyPluginTest`, `src/test/testData/rename/`.

**Keep and rename:** `MyBundle` → `PreviewGalleryBundle`, `messages/MyBundle.properties` → `messages/PreviewGalleryBundle.properties`.

**Reset:** `CHANGELOG.md` to a single `0.1.0` unreleased section; `README.md` to a short project description replacing the template documentation.

**Repository hygiene:** add `local.properties` to `.gitignore` and remove it from the index — it holds a machine-specific SDK path.

### 2.4 Known gap — CI

Choosing `local(...)` (D2) means the platform is resolved from a path that does not exist on GitHub Actions runners, so `.github/workflows/build.yml` cannot succeed as written.

**Phase 1 resolution:** disable the template's workflows (`build.yml`, `release.yml`, `template-verify.yml`, `template-cleanup.yml`). Verification is local: `./gradlew test` plus a manual `runIde` smoke check.

Re-enabling CI is a Phase 2 decision — it requires either a downloadable Android Studio coordinate or a self-hosted runner, and the answer depends on which platform artifact Phase 0 proves the renderer needs.

### 2.5 Package layout

```
com.devomer.previewgallery
├── PreviewGalleryBundle.kt
├── model/              PreviewEntry, IndexedPreview, AnnotationKind
├── index/              PreviewIndex, PreviewValueExternalizer,
│                       PreviewPsiScanner, JvmFqnResolver, PreviewAnnotationMatcher
├── service/            PreviewIndexService
├── search/             PreviewSearchFilter
├── ui/                 PreviewGalleryToolWindowFactory, PreviewGalleryPanel,
│                       PreviewTreeModelBuilder, PreviewTreeCellRenderer,
│                       PreviewDetailPanel, PreviewRenderPlaceholder,
│                       ModuleFilterToggleAction, RefreshAction
└── searcheverywhere/   PreviewSearchEverywhereContributor(+Factory)
```

---

## 3. Index layer

### 3.1 Index shape

`PreviewIndex : FileBasedIndexExtension<String, List<IndexedPreview>>`, registered under the `com.intellij.fileBasedIndex` extension point.

| Aspect | Choice | Rationale |
|---|---|---|
| Key | `composableFqn` | Enumerable via `getAllKeys`; gives prefix search for free; stable identity |
| Value | `List<IndexedPreview>` | One file can declare several previews under one JVM class name |
| Input filter | Kotlin files whose text contains `Preview` | Skips the majority of the ~1053 Compose files without parsing them |
| `dependsOnFileContent` | `true` | Requires PSI/content access |
| `version` | Explicit constant, bumped on any schema change | Forces a full reindex when the value layout changes |

**Key collisions are expected and handled.** Two modules can declare the same package and file name. Queries use `FileBasedIndex.processValues(...)`, which yields `(VirtualFile, value)` pairs, so the module and file are resolved per value rather than per key.

### 3.2 What the index must not store

`moduleName` and `filePath` are **not** indexed. Both are resolved at query time — `moduleName` from `ProjectFileIndex.getModuleForFile(file)`, the path from the `VirtualFile` itself.

Module membership is a project-model property, not a file-content property. Storing it would make a Gradle sync silently invalidate index correctness without invalidating the index.

`lineNumber` is likewise not stored; the index stores a PSI `offset`, and the line is computed from the `Document` when the detail panel needs it.

### 3.3 Annotation matching without resolution

A `FileBasedIndex` indexer must not resolve references outside the file being indexed. Annotation identification therefore works purely from the `KtFile`'s import list.

| Case in source | Result |
|---|---|
| `@androidx.compose.ui.tooling.preview.Preview` (fully qualified) | `ANDROIDX` |
| `@org.jetbrains.compose.ui.tooling.preview.Preview` (fully qualified) | `JETBRAINS` |
| `import androidx.compose.ui.tooling.preview.Preview` + `@Preview` | `ANDROIDX` |
| `import org.jetbrains.compose.ui.tooling.preview.Preview` + `@Preview` | `JETBRAINS` |
| `import androidx.compose.ui.tooling.preview.Preview as P` + `@P` | `ANDROIDX` |
| `import androidx.compose.ui.tooling.preview.*` + `@Preview` | `ANDROIDX` |
| Both packages star-imported, short name used | `UNKNOWN` |
| Short name `Preview` with no matching import | `UNKNOWN` |

`UNKNOWN` entries **are** indexed. They are rare, discovery is the point of the tool, and the annotation kind only constrains rendering, which is a Phase 2 concern.

`@PreviewParameter` detection uses the same import-driven matching over value-parameter annotations and sets `hasPreviewParameter`.

### 3.4 FQN derivation

Derived from PSI at index time, never by string manipulation at render time (parent spec §8.1).

| Declaration | JVM class | `composableFqn` |
|---|---|---|
| Top-level `fun BarPreview()` in `Foo.kt`, package `com.example` | `com.example.FooKt` | `com.example.FooKt.BarPreview` |
| File annotated `@file:JvmName("Custom")` | `com.example.Custom` | `com.example.Custom.BarPreview` |
| `fun` inside `object Previews` | `com.example.Previews` | `com.example.Previews.BarPreview` |
| `fun` inside a class | — | Indexed with `unsupportedReason = "declared inside a class"` |

Class-nested previews are indexed and shown in the tree — they are still worth discovering — but carry an `unsupportedReason` that Phase 2 will surface as the `UNSUPPORTED` render state.

Files with no package declaration use the default package: the JVM class name is the file-derived name with no prefix.

### 3.5 Attribute extraction

| Field | Source | Fallback |
|---|---|---|
| `displayName` | `@Preview(name = "…")` string literal | `functionName` |
| `previewGroup` | `@Preview(group = "…")` string literal | `null` |
| `isPrivate` | `KtFunction` visibility modifier | `false` |

Non-literal argument values (constant references, string templates) require resolution and are treated as absent — the fallback applies.

### 3.6 Data model

```kotlin
enum class AnnotationKind { ANDROIDX, JETBRAINS, UNKNOWN }

/** File-local facts only — everything serialized into the index. */
data class IndexedPreview(
    val displayName: String,
    val functionName: String,
    val packageName: String,
    val jvmClassName: String,
    val composableFqn: String,
    val offset: Int,
    val annotationKind: AnnotationKind,
    val isPrivate: Boolean,
    val hasPreviewParameter: Boolean,
    val previewGroup: String?,
    val unsupportedReason: String?,
)

/** Index value plus project-model context resolved at query time. */
data class PreviewEntry(
    val indexed: IndexedPreview,
    val moduleName: String,
    val file: VirtualFile,
) {
    val id: String get() = "${indexed.composableFqn}#${indexed.displayName}"
}
```

`PreviewValueExternalizer` implements `DataExternalizer<List<IndexedPreview>>` with an explicit field-by-field read/write. Nullable strings are length-prefixed with a null marker; the enum is written as its ordinal, guarded by the index `version` constant.

### 3.7 Query service

`PreviewIndexService` — a project-level `@Service`.

```kotlin
fun findAll(): List<PreviewEntry>
```

- Runs off the EDT under a read action; results are published to the EDT by the caller.
- Guarded by `DumbService` — no query while indexing; the UI shows an `INDEXING` state instead.
- Cached through `CachedValuesManager` with a `PsiModificationTracker.MODIFICATION_COUNT` dependency, so any PSI edit invalidates the cached list automatically.
- Entries whose file resolves to no module (e.g. a file outside the project model) are dropped.

---

## 4. UI

### 4.1 Tool window

`PreviewGalleryToolWindowFactory`, registered on the right anchor with id `Compose Gallery`. Not `DumbAware`: index queries need smart mode, and the factory defers its first load with `DumbService.smartInvokeLater`.

```
OnePixelSplitter(vertical = true)
├─ first:  OnePixelSplitter(vertical = false)
│          ├─ first:  SearchTextField over a Tree in a JBScrollPane
│          └─ second: PreviewDetailPanel
└─ second: PreviewRenderPlaceholder      ← Phase 2 render surface
```

Splitter proportions are persisted per project via the splitter's `proportionKey`.

### 4.2 Search

- `SearchTextField` above the tree.
- 150 ms debounce via `Alarm` on the EDT.
- Case-insensitive substring match over `displayName`, `functionName` and `packageName`.
- Plain in-memory scan — under 10k entries, no optimisation is warranted.
- Empty query restores the full tree.
- Matching is a pure function (`PreviewSearchFilter`) so it is unit-testable without a fixture.

### 4.3 Tree

- Grouping: module → package → preview.
- Module nodes display a preview count; package nodes do not.
- Alphabetical sort at every level.
- Leaf renderer shows `displayName`, with a dimmed `functionName` when the two differ, plus icons/badges for `private`, `@PreviewParameter` and `unsupportedReason`.
- After a filter run, module and package nodes with no surviving children are removed; remaining nodes are expanded so matches are visible without manual expansion.
- `PreviewTreeModelBuilder` is a pure function from `List<PreviewEntry>` + query to a tree model, so grouping and counts are unit-testable headlessly.

### 4.4 Detail panel

Shown for the selected leaf; otherwise an instruction placeholder.

Fields: `displayName`, `composableFqn`, module, `file:line`, annotation kind, `private`, `@PreviewParameter`, `group`, and `unsupportedReason` when present.

Actions: `Open file`, `Copy FQN`.

### 4.5 Navigation

Double-click or `Enter` on a leaf opens `OpenFileDescriptor(project, file, offset)` and requests focus. The same path backs the detail panel's `Open file` action.

### 4.6 Module filter

A `ToggleAction` in the tool window toolbar: *Show only the active editor's module*.

- Subscribes to `FileEditorManagerListener.SELECTION_CHANGED` and recomputes the active module.
- When enabled with no active module (no file open), the tree is empty with an explanatory state.
- The toggle state persists per project.

### 4.7 States

| State | Display |
|---|---|
| `INDEXING` | "Waiting for indexing to finish", tree hidden |
| `NO_PREVIEWS` | "No @Preview functions found in this project" |
| `NO_MATCH` | "No preview matches '<query>'" |
| `LOADED` | Tree |

A `RefreshAction` in the toolbar forces a re-query, for cases where the project model changed without a PSI event.

---

## 5. SearchEverywhere

`PreviewSearchEverywhereContributor` plus its factory, registered on `com.intellij.searchEverywhereContributor`.

- Contributes preview entries to the Shift-Shift popup, searched with the same `PreviewSearchFilter` used by the tool window.
- Elements render as `displayName` with the module and package as secondary text.
- Selecting an element navigates to the declaration **and** activates the gallery tool window with the corresponding node selected.
- Contributes nothing while `DumbService.isDumb`.

---

## 6. Testing

### 6.1 Unit tests — no IDE fixture

| Target | Cases |
|---|---|
| `JvmFqnResolver` | Every row of §3.4, plus the default package |
| `PreviewAnnotationMatcher` | Every row of §3.3 |
| `PreviewSearchFilter` | Case insensitivity, substring position, empty query, no match, match by package |
| `PreviewTreeModelBuilder` | Grouping, per-module counts, alphabetical order, pruning of empty branches under a filter |

### 6.2 Integration tests — `BasePlatformTestCase` with `testData`

One Kotlin fixture per case, asserting the resulting `IndexedPreview` values:

androidx preview · JetBrains preview · `private` preview · `@PreviewParameter` · `@file:JvmName` · `object`-nested · class-nested (asserts `unsupportedReason`) · aliased import · star import · both packages star-imported (asserts `UNKNOWN`) · `@Preview(name=, group=)` · non-literal `name` argument (asserts fallback) · file with `@Composable` but no `@Preview` (asserts no entry) · multipreview wrapper usage (asserts **no** entry, per D4).

A multi-module fixture asserts that `PreviewIndexService.findAll()` resolves module names correctly and that identical FQNs in two modules produce two distinct entries.

### 6.3 UI tests — headless

Built from a fixed `List<PreviewEntry>` with no IDE fixture: node structure, module counts, filter results, empty-state selection, and detail-panel field mapping.

Robot-based UI testing is out of scope; the template's `run-ui-tests.yml` was already removed upstream.

### 6.4 Manual verification

`./gradlew runIde` against a real multi-module Compose project: the tool window populates, search filters, navigation lands on the right line, `idea.log` contains no plugin errors.

---

## 7. Acceptance criteria

| # | Criterion |
|---|---|
| AC1 | Tool window lists previews from a multi-module project, grouped module → package, with per-module counts |
| AC2 | Typing in the search field filters the tree after a 150 ms debounce; clearing it restores the full tree |
| AC3 | `Enter` or double-click navigates the editor to the preview function's declaration |
| AC4 | androidx, JetBrains, private, aliased-import and star-import previews are all indexed with the correct `AnnotationKind` |
| AC5 | Class-nested previews appear in the tree carrying an `unsupportedReason` |
| AC6 | The module filter toggle restricts the tree to the active editor's module and persists across IDE restarts |
| AC7 | Shift-Shift finds a preview by name and navigates to it |
| AC8 | `./gradlew test` passes; the plugin loads in Android Studio 253 with no errors in `idea.log` |
| AC9 | No template identifiers (`org.jetbrains.plugins.template`, `MyBundle`, `MyToolWindow`) remain in the repository |

---

## 8. Out of scope

Live rendering, disk and memory image caches, build-on-demand, device/theme selectors, multipreview resolution, pinned modules, preview-coverage reporting, CI.

## 9. Known gaps carried into later phases

| # | Gap | Consequence | Owner phase |
|---|---|---|---|
| G1 | CI workflows disabled (§2.4) | No automated build or release; local verification only | Phase 2 |
| G2 | Multipreview not resolved (D4) | Components annotated only with custom wrappers are absent from the gallery | Phase 2 |
| G3 | `UNKNOWN` annotation kind | Cannot pick a render path for those entries | Phase 2 |
| G4 | Class-nested previews unsupported | Discoverable but not renderable | Phase 2 |
