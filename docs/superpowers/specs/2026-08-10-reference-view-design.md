# Reference View — Design

**Roadmap:** F5, the half that is not gated on a spike
**Spike:** [2026-08-10-screenshottest-render-spike.md](2026-08-10-screenshottest-render-spike.md)

## Goal

Let a preview row show the committed golden PNGs of the snapshots that cover it, without leaving the gallery.

Today a golden is only reachable by selecting the **snapshot** row hanging under the preview. That is one click
away, but it is also a different row with a different mental model: you leave the composable you were looking at
to go inspect its test. The question this answers is the plain one — *what does this composable's committed
image look like?* — asked from the row where you already are.

## Non-Goals

- **No diff.** The blocking reason is not effort: the live render and the golden are not comparable. The plugin
  renders the `main` source set's `@Preview`; the golden was produced by the `screenshotTest` function, whose
  body wraps content in `PreviewComponent { PrimusTheme { … } }`. They are *expected* to differ, and a UI that
  invites the comparison would present that difference as a regression. The diff waits on the second spike (can
  the `debugScreenshotTest` classes directory be injected into the render classloader?), which is what would let
  the plugin render the same function the golden came from.
- **No synced zoom or side-by-side.** Both exist to serve comparison, which is out of scope for the same reason.
- **No navigation between snapshots.** The tree's own snapshot children already do that.
- **No tree badge, no new gallery-toolbar button.** The control belongs to the render pane, beside the zoom
  controls that already act on the strip.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | The golden appears through a **mode toggle in the render pane's actions bar**, not as a tab in `viewTabs`. | Every tab in `viewTabs` is the same preview re-rendered under a different `ViewOverride`, and the tab machinery says so: `extraViews`, `extraScrolls`, `extraGenerations`, per-tab close, `activeComparisonView()`. A golden is not a render, has no override, and cannot be closed. Teaching all of that machinery a "this tab is not a render" case buys a tab strip and costs the invariant that makes the strip simple. |
| D2 | The mode reuses `RenderState.REFERENCE`, `ReferenceStripView` and the existing strip zoom/fit branch **unchanged**. | That surface exists and is already the answer to "show these goldens at one shared scale". The snapshot row's path is not modified; it gains a second caller. |
| D3 | A preview shows the goldens of **every** snapshot covering it, in one strip. | The strip is built for multiple images at one shared scale, and "every committed state of this composable, at once" is the useful view. Showing only the first would be arbitrary, and the tree already offers the per-snapshot view. |
| D4 | Labels carry the snapshot's function name **only when more than one snapshot covers the preview** — `Loaded · phone` with several, plain `phone` with one. | The single-snapshot case is the common one, and it should read exactly as it does today. A name repeated on every image when there is only one thing to name is noise. |
| D5 | The mode is **sticky**: switching previews keeps it on. | The workflow it exists for is scanning — arrow down a package looking at goldens. Resetting per selection would mean one click per row. |
| D6 | The toggle is **hidden** for a preview with no covering snapshot, rather than shown disabled. | `PreviewRenderPanel`'s own convention, stated in its capability gates: never a dead control. |
| D7 | With the mode on, an uncovered preview shows its **live render** and keeps the mode on. | The alternative — an empty or error state on every uncovered row — would make the sticky mode unusable in exactly the projects with partial coverage, which is all of them. Nothing is wrong with an uncovered preview; there is simply no golden to show. |
| D8 | The reference lookup moves out of `PreviewGalleryPanel` into its own class. | It gains a second caller here, and `PreviewGalleryPanel` is already 1022 lines. This is a move, not a redesign: the behaviour, threading and guards are preserved, and the existing reference tests must pass unchanged as the evidence. |
| D9 | The in-flight guard tests the **mode** as well as the selected row. | Today `publishReferences` re-reads the tree's selection. With a mode that can be switched while a decode is in flight, a stale result could otherwise land on top of a live render. |

## Architecture

One flag, one extracted class, one new control.

**`ReferenceStripLoader`** (new, in `ui/` — it produces `ReferenceStripView.LabelledImage`, a UI type, and
`service/ReferenceImageLocator` remains the thing that finds the files) — the lookup and decode chain moved
verbatim out of `PreviewGalleryPanel`:
`resolveReferences` (resolve the module directory, refresh the roots), `locateReferences` (list the roots under a
read action, collect the regenerating Gradle tasks), `decodeReferences` (decode off every read lock, skipping and
naming what will not decode) and `readImage`, plus the `LocatedReferences` / `DecodedReferences` result types.

Its input is a `List<PreviewEntry>` of snapshot rows — one element for a snapshot row, `entry.snapshots` for a
preview row. Its output is what `PreviewRenderPanel.showReference` already takes: labelled images, the labels it
could not decode, and the Gradle tasks to name when there are none.

Label composition lives here, since this is where the row count is known (D4).

**`PreviewGalleryPanel`** — gains `showReferenceForPreviews: Boolean`, and a third branch in `routeSelection`:

| Selection | Mode | Shows |
|---|---|---|
| snapshot row | — | that snapshot's goldens (unchanged) |
| preview row, covered | on | every covering snapshot's goldens |
| preview row, uncovered | on | live render (D7) |
| preview row | off | live render (unchanged) |

**`PreviewRenderPanel`** — gains a `ToggleAction` in the actions bar and an `onToggleReference: (Boolean) -> Unit`
callback, following the class's existing `onRender` / `onOpenFile` / `onProperties` / `onRequestVariant` pattern.
The action is contributed only when the current entry is a covered preview (D6).

## Data flow

```
tree selection ─→ routeSelection ─→ [snapshot row]      ─→ loader(listOf(row))
                                 ├─ [preview, mode, covered] ─→ loader(row.snapshots)
                                 └─ [otherwise]         ─→ pipeline.select(row)

toggle click ─→ flip flag ─→ routeSelection (same row)

loader: debounced ─→ background: resolve roots, locate, decode
                  ─→ EDT: guard (row still selected AND mode unchanged) ─→ showReference
```

The debounce, background executor, `disposalCheck` and `expireWith` are the existing ones from the snapshot
path. A preview covered by three snapshots at two variants decodes six device-resolution PNGs; the per-image cost
is what it is today, and the debounce already absorbs arrow-key bursts.

## Error handling

| Situation | Result |
|---|---|
| A reference PNG will not decode | That variant is skipped and named in the strip's tooltip — the others still show |
| Covered, but no PNG on disk | `NO_REFERENCE`, naming the Gradle tasks that would regenerate them |
| Preview has no covering snapshot | Toggle hidden; with the mode on, the live render, and the mode survives (D7) |
| Module directory cannot be resolved | Empty result, as today |

## Testing

Written after the feature works, per this project's workflow.

- Labels: one covering snapshot yields the bare variant; several yield the snapshot-prefixed form (D4).
- Sticky: the mode survives a selection change, including onto an uncovered preview (D5, D7).
- The toggle is absent for an uncovered preview and present for a covered one (D6).
- A decode in flight is dropped when the mode is switched off before it lands (D9).
- **The extraction's evidence:** `PreviewGalleryPanelTest`'s existing reference tests pass **unmodified** (D8).

## Risks

| Risk | Response |
|---|---|
| A preview with many covering snapshots renders a wide strip of small images | The strip's shared scale and Fit already handle this, and it is the honest view of "this composable has eight committed states". Revisit only if a real project produces an unusable one. |
| The extraction changes behaviour subtly | The existing reference tests are not touched, so a behaviour change fails them. That is the point of not modifying them. |
| Sticky mode surprises a user who forgot it was on | The toggle is a visible pressed state in the actions bar, next to the controls that act on what it shows. |
