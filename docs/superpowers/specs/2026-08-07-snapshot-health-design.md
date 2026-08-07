# Snapshot Health — Design

**Feature:** F7 · Degenerate golden detector
**Roadmap:** `docs/snapshot-testing-roadmap.md` — Theme 3, priority 1
**Commit prefix:** `PG18-N`

## Goal

Catch snapshot tests that pass without testing anything.

Two failure modes, both found in the reference project rather than imagined:

1. **A blank golden.** The consuming project's `snapshot-testing` skill opens with the case: a modal bottom
   sheet renders empty because the sheet starts collapsed, and the committed PNG is a flat rectangle. The test
   is green forever and guards nothing.
2. **A test named after something it does not show.** `DeleteSelectedProductsDialog_Preview` in `favorites`
   calls `PrimusDialog(...)` and rebuilds the dialog's insides by hand rather than calling
   `DeleteSelectedProductsDialog`. Its snapshot `DeleteSelectedProductsDialog_Default_Snapshot` copies the same
   mistake, while a sibling `_Direct_Snapshot` does call the real component and shows up as an orphan for it.
   If the component's signature or internals break, both stay green — they never touch it.

The second is the more dangerous of the two: the PNG is full, detailed and convincing.

## Non-Goals

- **Fixing anything.** The report names the rows; a human decides whether the test or the component is wrong.
- **Comparing goldens to live renders.** That is F5, gated on an unanswered AS-internal question.
- **Judging what a picture contains.** "Blank" here means the pixels are degenerate, not that the content looks
  wrong to a person.
- **A new toolbar button.** The gallery toolbar already carries seven controls.
- **Tree badges.** Reading a PNG per row is not something a tree repaint can do.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Two checks: a **blank golden** and a **name that does not match what the row shows**. | The first is the roadmap's original scope and the skill's own rule 4. The second is the class the reference project actually turned out to be carrying, and it is invisible to the first: the picture is full. |
| D2 | The blank check reuses `RenderedImageInspector.isBlank`, unchanged. | It already encodes "degenerate in size, or every pixel identical", it already documents why a flat-colour composable is an accepted false positive, and it is already tested headlessly. A second copy of that rule for reference PNGs would drift from the one the render path enforces. |
| D3 | The name check runs entirely on data the index already holds — no PSI, no file reads. | `IndexedPreview.functionName` and `targets` are both indexed. That makes the check free, testable as pure logic, and available wherever the rows are. |
| D4 | A row whose `targets` is **empty** is skipped, and the count of skipped rows is reported. | An empty `targets` means the call-site heuristic resolved nothing, not that the row shows nothing. Reporting it as a finding would blame the row for the extractor's gap; hiding it entirely would let a silent extraction failure read as a clean bill of health. |
| D5 | The report is a **`## Health` section appended to the existing coverage export**, not a second document or a second button. | "How healthy are this project's snapshots" is one question. Two files, each with half the answer, is how a number ends up quoted without its caveat. |
| D6 | A new MCP tool `snapshot_health(project?, module?)` serves the same findings. | An agent about to write snapshots for a module should be able to see that the module's existing ones are suspect. Same reasoning as `coverage_report` (F8 D8), and the same shape. |
| D7 | PNG decoding happens off the EDT and outside every read action, and an unreadable PNG is skipped and reported, never fatal. | `PreviewGalleryPanel.loadReferences` already establishes both: `RenderPipeline`'s class doc calls holding the read lock across long work "a prime freeze suspect", and `ImageIO.read` returns null for an unrecognised stream and throws for an IO failure. |
| D8 | With nothing wrong, the section says so in one line rather than being omitted. | "Checked and clean" and "not checked" are different facts, and an absent section reads as the second. |
| D9 | Both halves see the **orphan snapshots** as well as the previews and the snapshots hanging under them. | An orphan is a snapshot whose preview could not be found — exactly the population most likely to be dead or misnamed, and the one today's specimen surfaced through. It also matters for the vocabulary in step 2: `DeleteSelectedProductsDialog` is only known to be a real component *because* an orphan calls it. The coverage export currently passes the panel's `entries` alone, so the action must pass `orphanSnapshots` too. |

## The name check

The naive form of this check — "the function's name contains a component name it does not call" — produces
false positives on every `DarkThemePreview` and `Preview1` in a codebase. The rule is narrowed to three steps,
all of them answered from the project's own data:

**1. Derive candidate stems from the function name.**

| Function name | Candidates |
|---|---|
| `WidgetPreview` | `Widget` |
| `DeleteSelectedProductsDialog_Preview` | `DeleteSelectedProductsDialog` |
| `CreateListActionBar_Enabled_Snapshot` | `CreateListActionBar`, `CreateListActionBar_Enabled` |
| `Foo_Bar_Default_Snapshot` | `Foo`, `Foo_Bar`, `Foo_Bar_Default` |

More than one candidate on purpose: a component named `Foo_Bar` and a component named `Foo` cannot be told
apart by the name alone, so every prefix is a candidate and a match on any of them clears the row.

**2. Ask whether any candidate is a real component in this project.** The vocabulary is the union of every
`targets` entry across all previews, all snapshots and all orphan snapshots (D9) — the components the project's own preview bodies are
observed to call. No dictionary, no heuristic about capitalisation: if nothing anywhere renders `DarkTheme`,
then `DarkThemePreview` is a description, not a claim, and the row is left alone.

**Both previews and snapshots are checked**, and by the same rule — today's specimen is a pair, a preview and a
snapshot making the same mistake, and a check that looked at only one of them would have reported half of it.

**3. Flag the row when a candidate is a known component and none of the candidates is in this row's own
`targets`.** That is exactly the `DeleteSelectedProductsDialog_Preview` case: the stem is a component
(`_Direct_Snapshot` calls it), and this row calls `PrimusDialog` instead.

The finding names both sides — what it is called after, and what it actually shows — because the fix depends on
which one is wrong, and the report cannot know that.

## Architecture

```
service/
  SnapshotHealth.kt        NEW · the name rule, pure: rows in, findings out
  GoldenInspector.kt       NEW · reads reference PNGs, applies RenderedImageInspector.isBlank
  HealthReport.kt          NEW · findings → the `## Health` markdown section, pure
  CoverageReport.kt        unchanged — the action concatenates, the format does not learn about health

mcp/
  tools/SnapshotHealthTool.kt  NEW · the same findings as JSON
  ToolRegistry.kt          one more tool

ui/
  CoverageReportAction.kt  writes coverage + health
```

`CoverageReportAction` gains a second source: it already closes over the panel's `entries`, and now closes over
`orphanSnapshots` as well (D9). `SnapshotHealth` takes both lists and returns findings; it never sees a
`VirtualFile`. `GoldenInspector` is the only
part that touches disk. That split is what makes the interesting half — the name rule, with all its false
positive reasoning — testable without a fixture.

## Report format

```markdown
## Health

**2 blank goldens · 1 row named after something it does not show** · 14 rows skipped (no call targets resolved)

### Blank goldens

- `com.example.SheetSnapshotsKt.Sheet_Collapsed_Snapshot`
  - `/…/screenshotTest/reference/com/example/SheetSnapshotsKt/Sheet_Collapsed_0.png`

### Named after something they do not show

- `com.example.DeleteSelectedProductsDialogKt.DeleteSelectedProductsDialog_Preview`
  - named after `DeleteSelectedProductsDialog`, shows `PrimusDialog`
```

Clean project:

```markdown
## Health

No blank goldens, and every row shows what it is named after. 14 rows skipped (no call targets resolved).
```

The skipped count is always present, including when it is zero, because it is the report's own confidence
statement.

## Error handling

| Situation | Behaviour |
|---|---|
| A reference PNG cannot be decoded | That variant is skipped, counted, and named in the report. Never fatal — the other variants are still checked. |
| A snapshot has no reference PNG at all | Not a finding. It means `update…ScreenshotTest` has not run, which the coverage half of the report already covers. |
| A row's `targets` is empty | Skipped and counted (D4). |
| The index is still building | The MCP tool refuses, exactly as the other tools do (F8 D10). The export action is already not `DumbAware`. |
| A component is legitimately a wrapper of the same name | Cleared by step 3: the stem is in the row's own `targets`. |

## Testing

Plain JUnit 4:

- `SnapshotHealthTest` — the `DeleteSelectedProductsDialog_Preview` case as a fixture, flagged with both sides
  named; `DarkThemePreview` with no `DarkTheme` component anywhere, not flagged; `WidgetPreview` calling
  `Widget`, not flagged; a `Foo_Bar_Default_Snapshot` cleared by its second candidate stem; a row with empty
  `targets` skipped rather than flagged, and counted.
- `HealthReportTest` — the two finding kinds render under their headings; a clean project's one-line form; the
  skipped count present at zero.
- `SnapshotHealthToolTest` — the JSON carries both sides of a name finding and the PNG path of a blank one.

`BasePlatformTestCase`:

- `GoldenInspectorTest` — a real single-colour PNG written into a fixture reference directory is reported
  blank; a PNG with two colours is not; an unreadable file is skipped and counted rather than throwing.

## Risks

| Risk | Mitigation |
|---|---|
| The name rule fires on a legitimate pattern nobody anticipated. | Every finding names both sides, so a false positive is obvious on sight rather than requiring investigation. The first run against `hepsi-android` is the calibration: if it produces noise, the rule tightens before this ships. |
| The vocabulary is built from `targets`, which the extractor sometimes fails to fill — a component nothing successfully calls is invisible, so a genuinely misnamed row goes unflagged. | Accepted: this direction fails safe. D4's skipped count is what makes the gap visible rather than silent. |
| Decoding every reference PNG is slow on a large project. | Bounded by snapshot count, not preview count — 50 snapshots in the reference project, two PNGs each. It runs only when a report is asked for, on a pooled thread. |
| `isBlank` flags a composable whose output really is one flat colour. | Already documented and accepted in `RenderedImageInspector`; the finding names the file so it takes one look to dismiss. |
