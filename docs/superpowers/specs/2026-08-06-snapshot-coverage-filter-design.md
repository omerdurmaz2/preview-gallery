# Snapshot Coverage Filter and Report — Turn the Badge Into a Work Queue

| | |
|---|---|
| **Scope** | Phase 16 — F2 of the snapshot roadmap. A toolbar toggle that hides every preview which already has a snapshot, and an action that writes the project's coverage to a markdown file. |
| **Builds on** | [Phase 13](2026-07-30-snapshot-coverage-badge-design.md) — the per-row `SnapshotCoverage` the tree already carries. [Phase 15](2026-07-31-snapshot-reference-hardening-design.md) — the hardening that made those rows trustworthy. |
| **Tech** | Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · JUnit 4 · `BasePlatformTestCase` |
| **Commit prefix** | `[PG16-N]` |

## Goal

The badge answers "does this preview have a snapshot?" one row at a time. It provokes exactly one follow-up
— *so which ones do not?* — and today the only way to answer it is to scroll a tree with a thousand rows in
it. This phase answers it twice: on screen, by hiding everything already covered, and on paper, as a file
that can go in a ticket before the consuming project's CI job exists.

Nothing new is computed. `SnapshotCoverage` is already resolved per row by `SnapshotCoverageResolver` and
already drawn by `PreviewTreeCellRenderer`. This phase only filters on it and formats it.

## Non-Goals

- **A three-state filter.** The roadmap sketched *all / covered only / uncovered only*. Only the uncovered
  direction is built: it is the work queue, and it is the direction used daily. "Covered only" answers a
  real but rarer question — *which snapshots am I about to invalidate?* — and can be added later without
  changing the filter's contract, since it is one more branch in one function.
- **Writing snapshots.** The filter says what is missing; generating it is F3/F8.
- **A settings page.** The toggle persists in `PropertiesComponent` like the module filter; the report's
  destination is chosen per invocation in a save dialog. No configuration surface is added.
- **Reporting anything but coverage.** No timestamps, no per-variant counts, no history.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | The filter is a **two-state** `ToggleAction` in the tool window toolbar: off shows everything, on shows only rows whose coverage is `Uncovered`. It persists per project in `PropertiesComponent`, exactly as the module filter does. | The pattern is already in the toolbar next to it, so the control needs no explanation. A three-state control would need a dropdown, which is a new UI concept in this window for a direction nobody has asked for yet (see Non-Goals). |
| D2 | `NotApplicable` rows are **hidden** by the filter, not shown. | `NotApplicable` means the module has no `src/screenshotTest` at all, so "is this covered?" has no answer for it. Showing those rows in a list of work would fill it with modules that have not adopted screenshot testing, which is the same reasoning that keeps them unbadged. |
| D3 | The orphan branch stays **visible** while the filter is on. | An orphan is a snapshot that matches no preview — the mirror of what the filter selects for, and the same kind of defect: usually a renamed composable or a dead snapshot. Hiding it would make "show me what is wrong with coverage" tell half the truth. It is also the one place the filter's own subject matter cannot appear, since an orphan has no `coverage` to be `Uncovered`. |
| D4 | The filter composes with the search box and the module filter rather than replacing either. It is a third stage in `applyFilter`'s existing chain. | They are independent axes — *which module*, *matching what text*, *covered or not* — and `applyFilter` already threads two of them. One more `filter` call is the whole change. |
| D5 | The report reads `PreviewIndexService.findAll()` directly and **ignores** the filter, the module filter and the search box. | A report titled "snapshot coverage" that silently described a filtered subset is a footgun: the number lands in a ticket and nobody remembers a search box was open. Coverage is a property of the project, so the report describes the project. |
| D6 | The report counts and lists **applicable modules only** — a module with no `src/screenshotTest` contributes to neither the totals nor the body. | The reference project has 1371 modules and one of them has adopted screenshot testing. A percentage computed over all of them would read as ~0% forever and would be discarded as noise. |
| D7 | Uncovered previews are listed by **composable FQN**, sorted by module then FQN. | `displayName` collides across packages, and the FQN is what a reader pastes into Search Everywhere. A deterministic order makes two reports of the same project diff cleanly. |
| D8 | `ModuleFilterToggleAction`'s body — read a `PropertiesComponent` boolean, write it, call back — moves to a small `PersistentToggleAction` base that both toggles extend. | The second toggle differs from the first only in its storage key, its text and its icon. A base class plus two ten-line subclasses is less code than two twenty-five-line near-copies, and it means a change to how the toggles persist cannot apply to only one of them. |
| D9 | The report is written to a file chosen in a save dialog, reusing the `FileSaverDescriptor` / `createSaveFileDialog` pair already used to export a PNG. | The existing idiom is three lines and the user already knows the dialog from this window. Nothing is remembered between invocations. |

## Architecture

```
search/
  PreviewCoverageFilter    NEW · one function: drop everything that is not Uncovered
service/
  CoverageReport           NEW · pure: rows -> markdown string
ui/
  PersistentToggleAction   NEW · the PropertiesComponent-backed toggle both filters share (D8)
  ModuleFilterToggleAction now extends it; behaviour unchanged
  CoverageFilterToggleAction NEW · the toolbar control
  CoverageReportAction     NEW · save dialog + write
  PreviewGalleryPanel      applyFilter gains one stage; the toolbar gains two actions
```

Unchanged: `SnapshotCoverage`, `SnapshotCoverageResolver`, `PreviewIndexService`, `PreviewTreeModelBuilder`,
`PreviewTreeCellRenderer`, every rendering component.

`PreviewCoverageFilter` mirrors `PreviewModuleFilter` deliberately — same package, same shape, same
`enabled` parameter — so a reader who has seen one has seen both:

```kotlin
object PreviewCoverageFilter {
    fun <T : PreviewRow> apply(rows: List<T>, enabled: Boolean): List<T> =
        if (enabled) rows.filter { it.coverage is SnapshotCoverage.Uncovered } else rows
}
```

## Data flow

**The filter.** `PreviewGalleryPanel.applyFilter` already narrows `entries` through `PreviewModuleFilter`
and then hands the result to `PreviewTreeModelBuilder`, which applies the search query internally. The
coverage filter is inserted between those two, on the previews only:

```
entries → PreviewModuleFilter → PreviewCoverageFilter → PreviewTreeModelBuilder(query)
orphanSnapshots → PreviewModuleFilter → PreviewTreeModelBuilder(query)     ← unchanged (D3)
```

Toggling the action calls `applyFilter`, exactly as the module filter does. Nothing is re-indexed; the rows
already carry their coverage.

**The report.** `CoverageReportAction` reads `PreviewIndexService.findAll()` under a read action, hands the
rows to `CoverageReport.markdown`, opens the save dialog, and writes the string. The formatting half is pure
and takes no `Project`, so it is tested without a fixture.

The report's shape:

```markdown
# Snapshot coverage

**12/45 covered** across 3 modules

## features.favorites.ui.main — 9/12

- `com.example.favorites.EmptyStatePreview`
- `com.example.favorites.ErrorRowPreview`
- `com.example.favorites.LoadingPreview`

## features.cart.ui.main — 3/33
...
```

A module at full coverage still gets its heading and its count, with no list under it — the absence of
bullets is the signal, and dropping the module entirely would make a reader wonder whether it was missed.

## Error handling

| Situation | Behaviour |
|---|---|
| The filter is on and every row in view is covered | An empty tree. Correct: it means there is no work, and the toggle in the toolbar says why the tree is empty. |
| The filter is on with the module filter, and the active module has no uncovered rows | Empty for the same reason. The two filters compose; neither overrides the other. |
| The filter is on while the index is still building | Same as today: `applyFilter` runs on whatever `entries` holds, and the indexing tracker reloads when indexing finishes. |
| No module in the project has `src/screenshotTest` | The report is a single sentence saying no module has adopted screenshot testing, not an empty file with a heading. |
| Every applicable module is fully covered | A normal report: totals, per-module headings, no bullets. |
| The user cancels the save dialog | Nothing is written and nothing is reported. |
| The write fails | The existing `notify` path reports it; the report is not retried. |
| The report is taken while a filter is on | Unaffected — the report never reads the filtered view (D5). |

## Testing

Plain JUnit 4 (both units are pure):

- `PreviewCoverageFilterTest` — disabled passes every row through unchanged; enabled keeps `Uncovered`,
  drops `Covered`, and drops `NotApplicable` (D2).
- `CoverageReportTest` — the totals line; a module heading with its `X/Y`; uncovered rows listed by FQN and
  sorted by module then FQN (D7); a `NotApplicable` module contributing to neither the body nor the totals
  (D6); a fully covered module keeping its heading and losing its bullets; the no-applicable-module
  sentence.

`BasePlatformTestCase`:

- `PreviewGalleryPanelTest` — with the toggle on, an uncovered preview is in the tree and a covered one is
  not; the orphan branch is still there (D3); the coverage filter and the module filter compose rather than
  one winning (D4).
- `ModuleFilterToggleActionTest` (existing) must keep passing unchanged through D8's extraction — it is the
  evidence that moving the body changed no behaviour.

The save dialog is not tested: it is platform plumbing with no logic of its own.

## Risks

| Risk | Mitigation |
|---|---|
| Two toolbar toggles with the same `AllIcons.General.Filter` icon are indistinguishable. | The coverage toggle takes a different icon, so the two are told apart by icon and tooltip rather than by position. Which icon is pinned by the implementation plan, after checking it exists in this SDK — this project has been bitten before by assuming a platform symbol is present. |
| Extracting `PersistentToggleAction` breaks the shipped module filter. | Its existing test covers the persisted-state behaviour and must pass unchanged; the extraction is a move, not a rewrite. |
| An empty tree reads as a bug rather than as "nothing to do". | The toggle is a pressed toolbar button, which is the standard way this window already communicates the module filter's identical failure mode. Accepted rather than adding an empty-state panel. |
| The report grows unreadable on a project that adopts screenshot testing widely. | Bounded by D6: only applicable modules appear. If that ever stops bounding it, the fix is a per-module report, which this format nests under already. |
