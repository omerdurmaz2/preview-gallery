# "Show All Previews" — Editor Preview Toolbar Entry Point

| | |
|---|---|
| **Scope** | Phase 8 — a button in the Android Studio Compose preview's top-right toolbar that switches the editor to code-only and opens this plugin's gallery on the preview under the caret. |
| **Builds on** | The existing tool window (`PreviewGalleryToolWindowFactory` / `PreviewGalleryPanel`) and the Search Everywhere reveal path (`PreviewSearchEverywhereContributor.processSelectedItem`). |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG8-N]` |

## Goal

A developer reading a Composable in the editor has Android Studio's own preview open beside the code. That preview shows only what this file declares. One click on **Show all previews** should hand them off to the project-wide gallery: the editor collapses to code-only (the preview pane is no longer competing for width), the plugin's tool window opens, and the preview the caret is sitting in is already selected and rendering.

## Non-Goals

- **Persisting the code-only choice.** The switch applies to the current editor only; the user re-opens Design/Split whenever they want. No plugin setting, no per-file memory.
- **Replacing Android Studio's preview.** This is a hand-off, not a takeover — nothing suppresses or disables the built-in preview.
- **A gallery-side "back to editor preview" button.** Double-click-to-navigate already exists; restoring split mode is the user's own toolbar click.
- **Contributing to the preview's *left* (north) toolbar,** or shipping a `PreviewRepresentation` of our own.
- **Filtering the gallery tree down to the current file.** The whole tree stays; only the selection moves.

## Current state (what this builds on)

`PreviewGalleryPanel.selectEntry(entryId)` selects a tree node by `PreviewEntry.id` and scrolls to it; selection drives `RenderPipeline.select`, so selecting is enough to render. `PreviewSearchEverywhereContributor.processSelectedItem` already demonstrates the activation shape: `ToolWindowManager.getToolWindow(PreviewGalleryToolWindowFactory.ID)?.activate({ ...find panel..., panel.selectEntry(id) }, false)`. `PreviewIndexService.findAll()` returns every `PreviewEntry` (each carrying its `VirtualFile` and `indexed.offset`) from a cached, read-action-only computation.

## Findings that shape the design

Both were established by inspecting the installed Android Studio 253 jars (`unzip -p`, `javap`), not by assumption:

- **F1 — The preview's top-right toolbar is closed.** `ComposePreviewToolbar.getNorthEastGroup()` returns `com.android.tools.idea.compose.preview.actions.ComposeNotificationGroup`, a `DefaultActionGroup` whose children are hard-coded `new XxxAction()` instances. No `ActionManager.getAction(id)` lookup happens anywhere in that chain, so `<add-to-group>` cannot reach it, and no extension point feeds it (`composeStudioBotActionFactory` feeds the *north* group, is `firstOrNull()`-only, and is already claimed by Gemini). The toolbar is created by `ActionsToolbar.updateActionGroups` with the place string **`"NlRhsConfigToolbar"`**.
- **F2 — Switching to code-only is supported API.** `com.android.tools.idea.common.editor.SplitEditor.selectTextMode(boolean userExplicitlySelected)` is `public final`, carries no `@ApiStatus.Internal`, no deprecation, and no Kotlin `internal` mangling. Its platform ancestor `com.intellij.openapi.fileEditor.TextEditorWithPreview` exposes `setLayout(Layout.SHOW_EDITOR)` plus `Companion.getParentSplitEditor(FileEditor)` for unwrapping. For a `.kt` file, `SourceCodeEditorProvider` has policy `HIDE_DEFAULT_EDITOR`, so the split editor *is* the editor returned by `FileEditorManager`.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Place the button by **runtime injection**: locate the `ActionToolbar` whose place is `"NlRhsConfigToolbar"` inside the open file editor's component tree and add our action to its `DefaultActionGroup`. | F1 leaves no supported path to that toolbar, and the user chose the native top-right button over a supported-but-different banner. The plugin already binds to Android Studio internals elsewhere (`RenderApiProbe`, `PreviewPickerBridge`) behind probes. |
| D2 | Injection **fails silently**. If no matching toolbar is found after a bounded retry window, nothing is logged repeatedly and nothing breaks; the action stays registered by id in `plugin.xml`, so it remains reachable from Find Action and can be bound to a shortcut. | Degrade-don't-break, the rule every prior Android-Studio-internal task followed. A future Studio release that renames the place costs the button, not the feature. |
| D3 | Switch to code-only via `SplitEditor.selectTextMode(true)`, with `TextEditorWithPreview.setLayout(Layout.SHOW_EDITOR)` as the fallback when the editor is a platform split editor rather than an Android one. `true` means *user explicitly selected*, which stops the preview's `PreferredVisibility` from re-opening the pane. | F2. The explicit flag is what distinguishes "the user asked for code-only" from "the preview happens to be hidden right now". |
| D4 | Resolve the preview to select from the **caret**: among the entries whose `file` is the current file, pick the one with the greatest `indexed.offset` that is still `<= caretOffset`; if none qualifies (caret above the first preview), pick the file's first entry; if the file has no entries, select nothing. | Approximates "the preview function the caret is inside" using data the index already holds, with no PSI walk and no new index field. The fallbacks keep the button useful even when the caret sits in an import block. |
| D5 | Add `PreviewGalleryPanel.revealEntry(entryId)`: clear the search field, re-apply the filter, then select. If the entries have not loaded yet (the tool window was created by this very click), remember the id as a **pending selection** and apply it at the end of the next `applyFilter`. | `selectEntry` alone silently no-ops when a stale search query hides the node, or when the panel is still loading — exactly the two states this entry point creates. |
| D6 | Keep the module filter untouched. The current file is in the active module, so `PreviewModuleFilter` cannot hide its own previews. | No hidden state changes behind the user's back. |
| D7 | The action performs its steps independently: code-only always happens, gallery activation always happens, selection happens only when D4 found an entry. | A file whose previews are not indexed yet (dumb mode) still gets the hand-off rather than a dead button. |

## Architecture

New package `com.devomer.previewgallery.editor`:

| Unit | Responsibility | Depends on |
|---|---|---|
| `ShowAllPreviewsAction` | The button. `AnAction` + `DumbAware`. Reads project / file / caret from the action event, then runs D7's three steps. | the four units below |
| `PreviewToolbarInjector` | Project-level service listening on `FileEditorManagerListener`. On file opened / selection changed, schedules a bounded `Alarm` retry that asks `ToolbarLocator` for the toolbar and `ActionGroupInjector` to add the action. Disposed with the project. | `ToolbarLocator`, `ActionGroupInjector` |
| `ToolbarLocator` | Pure: walks a Swing component tree and returns the `ActionToolbar`s whose place matches a given string. | — |
| `ActionGroupInjector` | Pure: adds an action to a `DefaultActionGroup` only if an action of that class is not already a child, then requests a toolbar update. Idempotent across repeated injector runs. | — |
| `CaretPreviewResolver` | Pure: `(entries, file, caretOffset) -> PreviewEntry?` per D4. | model only |
| `SplitEditorSwitcher` | `(project, file) -> Unit`: walks `FileEditorManager.getAllEditors(file)`, unwraps each with `TextEditorWithPreview.getParentSplitEditor`, applies D3. Guards `SplitEditor` behind a class check so a missing Android class degrades to the platform path. | Studio + platform editor API |

Changed: `PreviewGalleryPanel` gains `revealEntry` + the pending-selection field (D5); `plugin.xml` registers the action id and the project listener; `PreviewGalleryBundle.properties` gains the button text and description.

The injector is the only unit that knows about Android Studio's toolbar internals, and the switcher the only one that knows about split editors — the same isolation `PreviewPickerBridge` already uses for the picker.

## Data flow

```
user clicks "Show all previews"  (toolbar button, place NlRhsConfigToolbar)
  -> ShowAllPreviewsAction.actionPerformed(e)
       file      = e.getData(CommonDataKeys.VIRTUAL_FILE)
       caret     = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset
       entryId   = CaretPreviewResolver.resolve(PreviewIndexService.findAll(), file, caret)?.id
       SplitEditorSwitcher.switchToCodeOnly(project, file)          // D3
       ToolWindowManager.getToolWindow(ID)?.activate({              // reuses the Search Everywhere shape
           panel.revealEntry(entryId)                               // D5, skipped when entryId == null
       }, false)
```

Threading: everything above is EDT (an action callback). The index read takes a read action; `findAll` is cached and returns an empty list in dumb mode, which D7 already tolerates.

## Error handling

| Situation | Behaviour |
|---|---|
| Toolbar never found (Studio renamed the place, or the preview pane was never opened) | No button. Action still invokable by id. No repeated logging. |
| `SplitEditor` class absent / editor is not a split editor | Platform `setLayout(SHOW_EDITOR)` path; if that also does not apply, the editor is left alone and the gallery still opens. |
| Index still building (dumb mode) | `findAll` returns empty, no selection; code-only + gallery open still happen. |
| Entry resolved but hidden by a stale search query | `revealEntry` clears the query first, so the node exists before selection. |
| Tool window created by this click | Pending selection is applied after the first `applyFilter`. |
| Action injected into more than one editor's toolbar | Each editor has its own `ComposeNotificationGroup` instance; `ActionGroupInjector` is idempotent per group, so no duplicates. |

## Testing

| Test | Kind | Covers |
|---|---|---|
| `CaretPreviewResolverTest` | pure JUnit | caret inside / above / below previews, multiple previews per file, other-file entries ignored, empty list |
| `ActionGroupInjectorTest` | pure JUnit | adds once, second run is a no-op, unrelated children preserved |
| `ToolbarLocatorTest` | `BasePlatformTestCase` | finds a real `ActionToolbar` by place in a nested panel tree; returns nothing for an unknown place |
| `PreviewGalleryPanelTest` (extended) | `BasePlatformTestCase` | `revealEntry` clears a stale query and selects; a pending id set before load is applied after `reloadSynchronously` |
| `SplitEditorSwitcherTest` | `BasePlatformTestCase` | a plain text editor is left alone (no exception); a `TextEditorWithPreview` double ends in `SHOW_EDITOR` |

Manual gate (runIde, per the project's usual Studio-internal verification): open a Composable with previews, confirm the button renders in the preview's top-right toolbar, click it, confirm the editor becomes code-only and the gallery opens with the caret's preview selected and rendering.

## Risks

- **R1 — `"NlRhsConfigToolbar"` is an internal place string.** A Studio update can rename it or restructure the toolbar. Mitigated by D2 (silent degrade) and by keeping the string in one place (`PreviewToolbarInjector`), so a future fix is a one-line change.
- **R2 — Injection timing.** The toolbar exists only after the preview pane has been built. Mitigated by a bounded `Alarm` retry per editor rather than a single attempt, and by re-running on editor selection changes.
- **R3 — Offset-based caret matching is approximate.** `indexed.offset` points at the preview declaration, not its body range, so a caret far below the last preview still resolves to that last preview. Accepted: the alternative is a PSI walk on every click for a marginal gain.
