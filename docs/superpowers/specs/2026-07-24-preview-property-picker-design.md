# Preview Gallery — Preview Property Picker Design

| | |
|---|---|
| **Status** | Approved — ready for implementation planning |
| **Date** | 2026-07-24 |
| **Scope** | Open Android Studio's own `@Preview` property picker from the gallery, and re-render on change |
| **Builds on** | [Phase 2 MVP](2026-07-23-preview-gallery-phase2-mvp-design.md) (live rendering, pipeline, render panel) |
| **Target IDE** | Android Studio Panda 4 — build `AI-253.32098.37.2534.15336583` (platform branch 253) |

---

## 1. Scope

In the editor, the gutter icon next to a `@Preview` opens a property picker: name, group, Device, Dimensions,
Density, Orientation, apiLevel, locale, fontScale, showSystemUi, showBackground, backgroundColor, uiMode,
wallpaper. This feature brings that same picker into the gallery, so a preview's configuration can be changed
without leaving the tool window.

### In scope

- A **Properties** button in the render panel that opens Android Studio's own picker for the selected preview.
- The picker is pre-populated with the preview's current `@Preview` arguments (this is AS's own behaviour).
- Changes are written to the **source** `@Preview` annotation, exactly as the editor's picker does.
- The render refreshes after a change.

### Out of scope

- Writing our own property UI, or any property the AS picker does not already offer.
- A temporary/preview-only configuration that does not touch the source (explicitly rejected — see D1).
- Editing properties for more than one preview at a time.
- Persisting per-preview render settings in plugin state.

### 1.1 Decisions

| # | Question | Decision | Rationale |
|---|---|---|---|
| D1 | Where do changes go | **Written to the source `@Preview` annotation**, like the editor's picker | User's call. Least code — AS's picker already does exactly this, and the change flows back through our index automatically. Accepted cost: browsing the gallery can now modify source files (undo still works, since AS writes through the normal PSI/undo machinery) |
| D2 | Build our own dialog, or reuse AS's | **Reuse AS's picker** via `PsiPickerManager` | The whole picker — including device/API/locale enumerations — comes for free. Writing an equivalent UI would be a large, duplicated effort that drifts from AS's behaviour |
| D3 | Where the button lives | **Render panel**, next to the existing actions | The picker acts on the previewed thing; the render panel is where that thing is |
| D4 | Behaviour when the picker API is missing | **Button is hidden; everything else keeps working** | Same posture as the renderer (Phase 2 §5.3): an AS-internal API change degrades one feature, never the gallery |

---

## 2. Verified API surface

Probed against the Android Studio 253 jars. It lives in the bundled **`com.android.tools.design`** plugin
(`design-tools.jar`) — NOT in `org.jetbrains.android`, which is where the renderer's API lives. That plugin was
not previously a dependency, so this feature adds `bundledPlugins("com.android.tools.design")` in
`build.gradle.kts` and a matching `<depends>` in `plugin.xml`. It is `com.android.tools.*` internal API, so §5 applies.

```
com.android.tools.idea.compose.pickers.PsiPickerManager
    INSTANCE
    void show(java.awt.Point, String title, PsiPropertiesModel, com.intellij.openapi.ui.popup.Balloon$Position)

com.android.tools.idea.compose.pickers.preview.model.PreviewPickerPropertiesModel
    extends PsiCallPropertiesModel  (which is a PsiPropertiesModel)
    Companion.fromPreviewElement(
        Project,
        Module,
        com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiElement>,
        ComposePickerTracker
    ): PreviewPickerPropertiesModel

com.android.tools.idea.compose.pickers.base.tracking.ComposePickerTracker   (interface)
    void pickerShown()
    void pickerClosed()
    void registerModification(String, EditorPickerEvent.EditorPickerAction.PreviewPickerModification.PreviewPickerValue, com.android.sdklib.devices.Device)
    void logUsageData()
```

**Why this is small:** `fromPreviewElement` builds the whole model — the `PreviewPropertiesProvider` (which reads
the existing `@Preview` arguments off the `KtAnnotationEntry`) and the `EnumSupportValuesProvider` (which supplies
the Device / apiLevel / locale / uiMode dropdown values) are constructed inside it. The only collaborator we must
supply is `ComposePickerTracker`, which exists solely for Google's usage analytics.

### 2.1 Unverified — the implementation must settle these against a running IDE

| # | Unknown | Where it bites |
|---|---|---|
| P1 | Which PSI element `fromPreviewElement` expects the pointer to hold — the `@Preview` `KtAnnotationEntry`, or the annotated `KtNamedFunction` | Building the model at all. `PreviewPropertiesProvider`'s own constructor takes a `KtAnnotationEntry`, which is the strong hint |
| P2 | Whether a no-op `ComposePickerTracker` can be implemented without pulling in the analytics proto types at runtime (`EditorPickerEvent...PreviewPickerValue` appears in one method signature) | Compiling and loading the tracker |
| P3 | What signals a change so the render can refresh: `ComposePickerTracker.registerModification` (called per edit?), `pickerClosed()`, or a PSI change listener | Refreshing the render after an edit |
| P4 | The screen `Point` and `Balloon.Position` that place the popup sensibly relative to the button | Cosmetic, low risk |

---

## 3. Architecture

```
PreviewRenderPanel ──(Properties button)──▶ PreviewPickerBridge ──▶ PsiPickerManager.show(...)
                                                   │                        │
                                                   │                        ▼
                                                   │              AS writes the change into
                                                   │              the @Preview annotation (PSI)
                                                   ▼                        │
                                          GalleryPickerTracker ◀────────────┘ (change signal)
                                                   │
                                                   ▼
                                          RenderPipeline.rerenderCurrent()
```

| Component | Responsibility | AS-internal? |
|---|---|---|
| `PreviewPickerBridge` | Resolve the preview's `@Preview` PSI element, build the model, show the picker | **Yes — the third and last home for AS-internal API, alongside `LiveRenderer` and `RenderModelResolver`** |
| `GalleryPickerTracker` | Implements `ComposePickerTracker`; does no analytics, but forwards "something changed" to the pipeline | **Yes — interface is AS-internal** |
| `PreviewRenderPanel` | Hosts the Properties button; hides it when the picker is unavailable | No |
| `RenderPipeline` | Gains `rerenderCurrent()` — re-render the current entry without changing selection | No |

The AS-internal surface of this plugin therefore grows from two classes to four, all under `render/`, all guarded.

---

## 4. Flow

```
click Properties:
    if !PreviewPickerBridge.isAvailable()      → button is not shown at all
    entry = the currently selected preview
    annotation = the @Preview KtAnnotationEntry for entry (from entry.file + entry.indexed.offset)   [P1]
    model = PreviewPickerPropertiesModel.fromPreviewElement(project, module, pointer(annotation), tracker)
    PsiPickerManager.show(pointAtButton, entry.displayName, model, Balloon.Position.below)           [P4]

on change (from the tracker, or the fallback in P3):
    RenderPipeline.rerenderCurrent()          → RENDERING → LIVE / FAILED

the source file now differs:
    the FileBasedIndex reindexes it, PreviewIndexService's cache invalidates on the PSI change, and the tree
    refreshes through the existing Phase 1 path — no new wiring needed
```

The picker writes through AS's normal PSI machinery, so **undo works** and the change appears in the editor like
any other edit.

---

## 5. API-stability posture

Identical to the renderer's (Phase 2 §5.3), and the reason D4 is what it is:

1. **Capability probe.** Extend the existing `RenderApiProbe` (or add a sibling) with the picker's classes and
   methods. `PreviewPickerBridge.isAvailable()` reflects it.
2. **Runtime guard.** Every AS-internal call catches `Exception` and `LinkageError`. A failure disables the
   button for the session and is logged once — the gallery and the renderer keep working.
3. The Properties button is only added to the panel when `isAvailable()` is true, so a missing API is invisible
   rather than a dead control.

---

## 6. Testing

| Target | How |
|---|---|
| Resolving the `@Preview` annotation element from a `PreviewEntry` | `BasePlatformTestCase` with Kotlin fixtures: a plain `@Preview`, one with arguments, one of several `@Preview`s on one function, a `@Preview` reached through an alias import. Assert the resolved element is the expected `KtAnnotationEntry` |
| `RenderPipeline.rerenderCurrent()` | Plain JUnit with a fake renderer: re-renders the current entry, is a no-op when nothing is selected, and respects the generation counter so a stale result is ignored |
| Probe reports the picker missing | Plain JUnit against a probe fed a nonexistent class name |
| `PreviewPickerBridge`, `GalleryPickerTracker`, the popup itself | **Manual, in `runIde`** — same rule as the renderer: AS-internal UI cannot be unit-tested |

## 7. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | `fromPreviewElement` rejects our pointer (P1) | Medium | High — feature blocked | Try the `KtAnnotationEntry` first (strongly hinted by `PreviewPropertiesProvider`'s constructor); the function element is the fallback; verify in `runIde` before building the rest |
| R2 | No usable change signal (P3) | Medium | Medium — picker works but render is stale until reselect | Fallback chain: tracker callback → picker-closed → PSI change listener scoped to the file |
| R3 | The analytics proto type blocks a no-op tracker (P2) | Low | Medium | The type only appears in a parameter; an implementation can accept and ignore it. If the class is missing at runtime, the probe disables the feature |
| R4 | Accidental source edits while browsing | Medium | Low-Medium | Inherent to D1 and accepted by the user; the picker requires an explicit button click and edits go through normal undo |
| R5 | AS changes the picker API on upgrade | High over time | Low | §5: probe + guard; the button disappears, nothing else breaks |

## 8. Acceptance criteria

| # | Criterion |
|---|---|
| AC1 | A Properties button appears in the render panel when a preview is selected and the picker API is available |
| AC2 | Clicking it opens Android Studio's own picker, pre-filled with that preview's current `@Preview` values |
| AC3 | Changing a value (e.g. Device or apiLevel) writes it into the `@Preview` annotation in the source file |
| AC4 | The render refreshes to reflect the change without reselecting the preview |
| AC5 | The tree/index pick up the edited annotation through the existing Phase 1 path (e.g. an edited `name` shows in the tree) |
| AC6 | Undo reverts the change like any other edit |
| AC7 | With the picker API absent, the button is not shown and the gallery + renderer work normally |
| AC8 | `./gradlew test` passes, including the new tests in §6 |
