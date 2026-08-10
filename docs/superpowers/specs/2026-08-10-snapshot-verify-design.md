# Snapshot Verify — Design

**Roadmap:** F6, reached ahead of F5's diff half
**Related:** [reference view (PG19)](2026-08-10-reference-view-design.md) · [render classloader spike](2026-08-10-render-classloader-spike.md)

## Goal

Answer "does this component still match its committed golden?" from inside the gallery, by running the
project's own `validate…ScreenshotTest` task and showing what it found.

The answer is exact, because the thing producing it is the same engine that produced the golden. That is the
whole reason this arrives before F5's in-IDE diff: the diff would be fast but is gated on an unproven question —
whether the plugin's layoutlib render is pixel-comparable with Gradle's. This feature never asks that question.

## Why this, and why now

The roadmap had F6 depending on F5's diff view. That dependency turned out to be wrong. The Gradle task already
writes everything a diff view would have to compute:

```
build/outputs/screenshotTest-results/preview/debug/
  rendered/   the freshly rendered PNGs
  diffs/      the per-snapshot difference images
build/test-results/validate<Variant>ScreenshotTest/
  TEST-<facade>.xml   one JUnit file per facade class
```

and the JUnit XML hands over, per snapshot **and per variant**, everything the plugin would otherwise derive:

```xml
<testcase name="RefreshFavoritesButton_Visible_Snapshot_phone" …>
  <property name="PreviewScreenshot.previewName"  value="phone"/>
  <property name="PreviewScreenshot.methodName"   value="RefreshFavoritesButton_Visible_Snapshot"/>
  <property name="PreviewScreenshot.refImagePath" value="…/reference/…_phone_eee23ffd_0.png"/>
  <property name="PreviewScreenshot.newImagePath" value="…/rendered/…_phone_eee23ffd_0.png"/>
</testcase>
```

Function name, variant, golden path and rendered path are all given. No file-name parsing, no configuration-hash
guessing — and `ReferenceImageLocator`'s known "a prefix is not an identity" limitation never applies here.

## Non-Goals

- **No in-IDE rendering.** F5's diff half and its classloader composition are untouched by this. They remain a
  separate, later question — a fast pre-check to this feature's authoritative one.
- **No triggering over MCP.** The F8 scope guard rules it out and stays as written: what writes — generating a
  file, running a Gradle task — stays behind the IDE's own UI with a human present. Serving the *results* is
  read-only and fits the guard, but it is its own later session.
- **No second Gradle daemon** (plugin spec goal G5). Everything goes through the IDE's own external-system
  infrastructure, exactly as `BuildService` already does.
- **The plugin never writes reference images.** It runs `validate`, never `update`. Regenerating a golden stays
  the human's deliberate act at a terminal.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Selecting a **snapshot** row starts a verify for its module, **debounced**. Selecting a preview row does not. | The user asked for automatic verification. The debounce is what keeps it survivable: `RenderPipeline.DEBOUNCE_MS` already means "the user settled on this row" for both the render path and PG19's reference lookup, and arrow-keying through 50 snapshot children must not start 50 Gradle runs. |
| D2 | A new verify **cancels the one in flight** — whether started by the button, by another row, or by another module. | One question at a time. The user asked for exactly this, and `BuildService`'s generation guard plus `cancelCurrent` already implement the shape. |
| D3 | Results cover the **whole module**; the selected row's are shown first. | The run produced all of them — discarding 49 results to show 1 wastes what was already paid for, and the neighbour your change broke is the one you would never have thought to check. |
| D4 | Editing the module marks results **stale**, never deletes them. | "It was green" and "it is green" are different facts, and this project has repeatedly chosen to make that distinction visible rather than silent (PG18 D8). Deleting also throws away minutes of work over one keystroke. |
| D5 | The task name is **derived** (`validate<Variant>ScreenshotTest`), not read from the IDE's task list. | The project is synced without `-Pandroid.experimental.enableScreenshotTest=true`, so AGP's screenshot plugin is not applied in the IDE's model and the task is absent from it; the flag passed at invocation makes it exist for that run. `ReferenceRoots.updateTask` already derives the sibling `update` name the same way. |
| D6 | The **variant comes from `ReferenceRoots`** — the one whose reference directory exists. | Validating a variant whose goldens are not committed answers a question nobody asked. The variant that owns the goldens is the variant to check. |
| D7 | XML older than the run's own start time is **ignored**. | The same directory can hold results from a `update` the human ran by hand. Reading those would present someone else's older run as this verify's answer — the exact "stale data shown as fresh" failure this project keeps designing against. |
| D8 | "Could not run" and "ran, found nothing" are **different states**, never both green. | A green badge for a run that never happened is worse than no badge. Same reasoning as PG18's always-present skipped count. |
| D9 | A failing snapshot shows **golden, rendered and diff** side by side in the existing `ReferenceStripView`. | That view already lays out N labelled images at one shared scale, with the zoom/fit branch PG19 wired. Three images is what it is for. |
| D10 | The XML reader holds no `com.intellij` import. | The results are the natural thing to serve over MCP next, and `mcp/` may not import platform classes. Keeping the reader pure is what leaves that door open. |

## Architecture

**`SnapshotVerifyRunner`** (`render/`) — runs `validate<Variant>ScreenshotTest` for one module through the IDE's
external-system infrastructure with the gate flag as a script parameter. Generation-guarded and cancellable,
following `BuildService`'s established shape (rule B1: never spawns a daemon directly). Reports start, success
and failure to its caller; it does not read results.

**`SnapshotVerifyResults`** (`service/`) — reads `build/test-results/<task>/TEST-*.xml`, ignoring files older than
the run's start (D7), and returns pure data:

```
VerifyResult(snapshotFqn, methodName, variant, status, goldenPath, renderedPath, diffPath)
VerifyRun(moduleName, ranAt, results, outcome)      outcome: RAN | BUILD_FAILED | NOT_RUN
```

No `com.intellij` imports (D10).

**`SnapshotVerifyStore`** (`service/`) — the last run per module, plus a `stale` flag set when that module's
source changes. Nothing is deleted on edit (D4).

**UI** — a plain toolbar action, **Verify snapshots** (not a toggle: it starts a run, it does not hold a mode),
hidden when the selected row's module has no `screenshotTest` (this panel's own convention: never a dead
control). Failing rows get a tree badge; the selected row's images go to the render pane through
`ReferenceStripView` (D9). Stale results render in a distinguishable style carrying the run's timestamp.

A snapshot that **passed** shows its golden and the rendered image, with no diff — there is no difference image
to show, and an empty third slot would read as one. A snapshot that **failed** shows all three.

## Data flow

```
snapshot row selected ─→ debounce ─→ resolve module + variant ─→ derive task name
                                              │
                        button pressed ───────┤ (cancels any run in flight, D2)
                                              ▼
                              IDE external-system run, gate flag passed
                                              │
                        ┌─────────────────────┼─────────────────────┐
                     cancelled            build failed            finished
                        │                     │                     │
                   nothing published    BUILD_FAILED          read XML (D7)
                                                                    │
                                                        badges + three-image strip
```

## Error handling

| Situation | Result |
|---|---|
| Module has no `screenshotTest` source set | Action hidden; no automatic run |
| Derived task does not exist in Gradle | The run fails → `NOT_RUN`, reported as "could not run" — never as "no differences" (D8) |
| Compilation failure | `BUILD_FAILED`, shown distinctly from "N snapshots differ" |
| Run cancelled | Nothing published; the previous result stays, still marked stale if it was |
| XML unreadable, or its shape changed | No result, logged, plugin keeps working — same degrade-don't-break posture as every AS-facing call in `render/` |
| A referenced image path is missing on disk | That snapshot shows the images that do exist; the missing one is named, not silently skipped |

## Testing

Written after the feature works, per this project's workflow.

- The XML reader against **real files**, both a passing run and a failing one.
- D7's timestamp guard: an older `update` result in the same directory is not read.
- Task-name derivation across variants, and the null case when no variant is known.
- Staleness: a source edit marks, and does not delete.
- `NOT_RUN` / `BUILD_FAILED` / `RAN` stay distinguishable — no path collapses a failed run into a clean one.
- Cancellation: a second verify supersedes the first, and the first publishes nothing.

## Risks

| Risk | Response |
|---|---|
| **The shape of a *failing* `validate` XML is unverified.** Only a passing `update` run was available while designing: `<failure>` elements and the diff-path property name are assumed, not observed. | Step 1 of the manual gate: deliberately corrupt one committed golden, run verify, read the XML, and correct the reader before anything else is judged. This is the one assumption the whole reader rests on. |
| Selecting a snapshot row starts a multi-minute run the user did not ask for | The debounce means "settled on the row", not "passed over it" (D1). Whether that is enough is a gate judgement — the alternative is button-only, and reverting to it is a one-line change. |
| Cancellation is not free — a cancelled run may already have started compiling | Accepted; `BuildService` already pays the same cost on the render path. |
| The result directory layout is not a stable API | Read the XML properties rather than deriving paths, and degrade to "no result" rather than throwing when the shape changes. Never scrape the HTML report. |
| Repeated start/cancel cycles keep the Gradle daemon busy | The debounce plus one-run-at-a-time (D2) bounds it to one run per settled selection. |
