# Backlog

Everything known to be open as of 2026-09-14, collected from the area docs' "Open items" sections. Each line links to
the area doc that holds the detail and the source (spec, commit or code). When you finish or defer something, update
the area doc and this file together.

Categories:

- **Decision needed:** blocks nothing technically, but someone has to choose.
- **Bug:** known misbehaviour, including flaky tests.
- **Gap:** a capability that is missing or unfinished.
- **Idea:** a proposed feature nobody has committed to.
- **Debt:** code or test debt.
- **Limitation:** a known constraint accepted by design. Listed so nobody rediscovers it as a bug.

## Suggested next steps

A starting order, not a commitment. Cheap unblockers come first, then the highest user value.

1. **Decide `sinceBuild`.** It is a one-line change either way, and the build currently promises Panda 4 support it
   cannot render on.
2. **Fix the flaky `McpHttpServerTest`**, so a red suite means something again.
3. **Re-run the calibration gate** on the PG22-19 render, then decide whether D6b is needed and update roadmap F5. This
   unblocks or kills the in-IDE diff.
4. **Support multipreview annotations** (`@PreviewLightDark`, custom multipreviews). Components whose only previews
   use them are invisible in the gallery.
5. **Make the picker's hardware rows** (Width, Height, Density, …) change the comparison render. The UI offers them
   today and silently ignores them.
6. **Keep the measurement selection across a re-render**, so edit-then-measure does not need reselecting.

## Decisions needed

- `sinceBuild` is still `"253"` while the plugin compiles against 261. On Panda 4, `RenderApiProbe` turns rendering off
  because `getRenderedImage`/`disposeAsync` are missing. The options: raise to 261, or keep 253 with the degraded
  fallback. See [live rendering](features/live-rendering.md#open-items) and
  [project](project-build-and-conventions.md#open-items); `8667333 [PG24-10]`.

## Bugs

- `McpHttpServerTest` is flaky: `BindException: Address already in use` when `freePort()` races the server's own bind.
  1–4 of 8 tests fail per run. [MCP](features/mcp-index-server.md#open-items)
- `PreviewGalleryPanelTest > test a snapshot the project model places in no module still shows its references` failed
  once in a full run and passed on rerun; the cause has not been isolated.
  [snapshot coverage](features/snapshot-coverage-and-references.md#open-items)
- A measurement label on a line at the render's edge gets clipped; it is cosmetic.
  [render view](features/render-view-interaction.md#open-items)
- On Windows and Linux, holding Alt to measure also shows IntelliJ's tool-window stripe numbers (platform behaviour).
  [render view](features/render-view-interaction.md#open-items)

## Gaps

**Catalogue**
- Multipreview annotations (a custom annotation meta-annotated with `@Preview`) are not resolved by the file-local
  index. [catalogue](features/catalogue-and-navigation.md#open-items)

**Rendering**
- The PG7 render performance layer is not built: every render still builds and disposes a fresh `RenderTask`. It was
  designed as `RenderPlanner`, `BuildStateProbe`, `RenderTaskCache` and `FastCompileBridge`.
  [live rendering](features/live-rendering.md#open-items)
- There is no render output cache, in memory or on disk. [live rendering](features/live-rendering.md#open-items)

**Comparison views**
- The ephemeral picker's capitalised hardware rows (`Width`, `Height`, `Density`, `Orientation`, `Device`, …) have no
  render effect; `OverrideMerge` reads only the lowercase axes.
  [picker](features/property-picker-and-comparison-views.md#open-items)

**Render view**
- The measurement selection is cleared on every re-render. [render view](features/render-view-interaction.md#open-items)
- There is no multi-selection, no measure-to-parent without hovering, and no "select parent" when a parent is fully
  covered by its children. [render view](features/render-view-interaction.md#open-items)

**Snapshot testing**
- The coverage filter has only the "uncovered" direction; "covered only" was scoped out.
  [snapshot coverage](features/snapshot-coverage-and-references.md#open-items)
- The calibration has never produced a number; every gate run stopped at a variant, device or size mismatch.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- D6b (measure the overlap, report the size delta) is specified but not implemented in `ImageDiff`.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- The calibration gate has not been re-run since the PG22-19 parity fixes.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- The roadmap's F5 entry was never updated for PG22.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- F5's diff view (live render vs. golden) is not built; it is blocked on the calibration above.
  [snapshot coverage](features/snapshot-coverage-and-references.md#open-items)

**Project**
- The four screenshots `docs/feature-overview.md` links to do not exist; `docs/images/` is empty.
  [project](project-build-and-conventions.md#open-items)
- There is no CI: build, tests and the release zip are all manual.
  [project](project-build-and-conventions.md#open-items)

## Ideas

- Pinned modules at the top of the tree, deferred at v1. [catalogue](features/catalogue-and-navigation.md#open-items)
- F3, "Create snapshot test" as a PSI writer, deferred in favour of generating tests through an agent over MCP.
  [MCP](features/mcp-index-server.md#open-items)
- F4, promote a comparison view to a snapshot variant. It waits on F3's writer.
  [picker](features/property-picker-and-comparison-views.md#open-items)
- A "failing snapshots only" tree filter, and a click-through from the failing-verify notification to its row.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- A cached multi-step downscale for renders zoomed out below 50%. Measure before building.
  [render view](features/render-view-interaction.md#open-items)
- A configurable MCP port (fixed at 7891), and a `withReferenceImages` flag if `list_snapshots` gets slow.
  [MCP](features/mcp-index-server.md#open-items)

## Debt

**Code**
- `model/RenderConfig.kt` is dead code with no references. [live rendering](features/live-rendering.md#open-items)
- `SnapshotVerifyRunner` duplicates about 120 lines of `BuildService` machinery, and the two copies have already
  diverged once. [verify](features/snapshot-verify-and-calibration.md#open-items)
- `PreviewGalleryPanel.publishReferences`' staleness guard compares only the owner id (marked `ponytail:` in code).
  [snapshot coverage](features/snapshot-coverage-and-references.md#open-items)
- `applyCalibrationConfiguration` writes the device outside `startBulkEditing`/`finishBulkEditing`.
  [verify](features/snapshot-verify-and-calibration.md#open-items)
- Compiler warnings on platform 261: deprecated `ReadAction.compute`, `updateActionsImmediately` and
  `Disposer.isDisposed`, plus `INVISIBLE_REFERENCE` suppressions in the picker bridges.
  [project](project-build-and-conventions.md#open-items)
- The Android Studio internals `editor/` depends on (the `NlRhsConfigToolbar` place string, the `SplitEditor` cast)
  were not re-checked after the move to 261. [catalogue](features/catalogue-and-navigation.md#open-items)

**Tests without automated coverage**

| Area | What is untested |
|---|---|
| [Live rendering](features/live-rendering.md#open-items) | `RenderPipeline`'s async orchestration |
| [Catalogue](features/catalogue-and-navigation.md#open-items) | `PreviewToolbarInjector` injection and retry; `PreviewSearchEverywhereContributor.processSelectedItem` |
| [Picker](features/property-picker-and-comparison-views.md#open-items) | the comparison tab strip and both picker bridges |
| [Render view](features/render-view-interaction.md#open-items) | `navigateToSource`, `resolveSourceFile`, `packageHashOf` |
| [MCP](features/mcp-index-server.md#open-items) | `McpServerDialog` |
| [Verify](features/snapshot-verify-and-calibration.md#open-items) | the verify runner and the compare orchestration end to end |

## Limitations (by design)

**Catalogue**
- A `@Preview` declared inside a class is listed but never renders.
- The preview under the caret is approximated as the last one declared at or before it.
- Search and filters are a linear in-memory scan, assumed fine below about 10k entries.

**Rendering**
- A component that needs a theme wrapper its `@Preview` does not supply fails here as it does in Android Studio.
- A `@PreviewParameter` provider is capped at 16 values.
- `org.jetbrains.compose` multiplatform previews are attempted but not specifically supported.

**Comparison views**
- Tabs only, no grid.
- Export covers the active tab only.
- At most 5 extra tabs; adding past the cap silently does nothing.
- Nothing is persisted.

**Render view**
- Hover, selection, navigation and measurement need a view tree. `@PreviewParameter` and snapshot strips have none.
- A gap made of two separately rounded positions can be a pixel off.
- If the density cannot be read, labels show pixels labelled as dp.

**Snapshot coverage**
- Turning reference mode on briefly flashes "Nothing selected".
- Snapshot names that are prefixes of each other can mislabel an image.
- KMP source-set module attribution is unsupported.
- `TargetExtractor` only sees an unqualified trailing call.

**Verify and calibration**
- Only the `phone` variant is compared.
- There is no comparison without local `screenshotTest` build output.
- Any threshold holds only for one pair of Android Studio and AGP toolchain versions.

**MCP**
- Read-only, loopback only, no authentication or TLS.
- `list_snapshots` walks every row's reference roots.
