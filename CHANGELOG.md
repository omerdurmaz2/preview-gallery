# Compose Preview Gallery Changelog

## [Unreleased]

## [0.1.0] - 2026-08-20

First build shared with the team.

### Added

- Project-wide preview catalogue: every `@Preview` in the project, grouped by module and package, searchable by
  component name, function name or package, and kept up to date from the code itself.
- Live rendering through Android Studio's own renderer, with the device, API level, size and system-UI chrome
  taken from each preview's own `@Preview` annotation (falls back to the default configuration when unavailable).
- Kotlin Multiplatform previews: a `@Preview` in a `commonMain` source set renders against its Android target's
  module instead of failing for having no Android facet.
- `@PreviewParameter` previews render: the provider's values are resolved and the composable is rendered once per
  value, stacked in one strip with each value's label under it (capped at 16 values, and the drop is logged). A
  value whose render fails costs that value, not the set.
- Comparison views: several copies of the same preview side by side, each with its own configuration, driven by
  Android Studio's own `@Preview` property picker.
- Live picker refresh: editing a value in that picker re-renders the displayed preview in place, without
  reselecting it.
- Interactive render overlay: hovering the rendered preview outlines the innermost composable under the cursor
  (resize-safe), and clicking it opens that composable's own source — disambiguated by the source's package hash
  when two project files share a name.
- Zoom and pan: a stepped ladder (25%-400%) on the toolbar, continuous zoom on Ctrl/Cmd+wheel and trackpad pinch,
  panning by scrollbar, wheel, two-finger scroll and a hand-tool drag. A smaller-than-pane preview fits without
  upscaling.
- Export: save the rendered preview as a PNG or copy it to the clipboard at full resolution, without the overlay.
- **Show all previews** from the editor: a gutter icon beside every `@Preview` function and a button on the
  preview toolbar, both opening the gallery on that component.
- Search Everywhere contributes previews alongside classes and files.
- Snapshot coverage: every component in the tree says whether a screenshot test covers it, with a filter for the
  ones that do not, and a Markdown coverage report counting every module — including the ones that never adopted
  screenshot testing.
- Committed reference images for a snapshot, discovered per build variant from disk and refreshed before every
  lookup, so a PNG written from a terminal shows up without pressing Refresh.
- Snapshot verification: runs the project's own `validate…ScreenshotTest` task and shows, per snapshot, the
  reference image, what was rendered, the diff and the engine's own differing-pixel percentage — marking the
  failing rows in the tree, and marking a result stale when the code has changed since it was measured.
- Snapshot health: names that no longer match any real component, and degenerate reference images.
- A read-only MCP server exposing the catalogue, the coverage and the health check to an AI assistant, started
  and stopped from the toolbar.

### Fixed

- Previews rendered at the wrong device: a `@Preview` that names no `device` left the render on whatever device
  the module's `ConfigurationManager` had persisted (in one project a large landscape screen), so a phone-shaped
  composable came back page-wide. The render now supplies the same fallback device Android Studio's own preview
  does.
- The **Render** button could leave the pane on "Rendering…" forever: the build callback it waits on fired on
  Gradle's own thread, and publishing render state from there threw an EDT-only assertion before the render was
  ever submitted. Build results are now always delivered on the EDT.
- Trackpad zoom and pan: high-precision (sub-notch) two-finger scroll now pans instead of doing nothing, one notch
  pans a usable distance, and Ctrl/Cmd+wheel and pinch zoom continuously instead of jumping a whole zoom-ladder
  rung per event. The reference strip gets the same gestures, which it had none of before.
- Reference and parameter strips are laid out top to bottom instead of left to right, so three phone-width images
  no longer force the shared scale down until nothing is readable.
- A failed render's layoutlib problems are readable in the log and in Details instead of appearing as object
  identity hashes.

### Known limitations

- A component that cannot render on its own — one needing a theme wrapper its `@Preview` does not provide — fails
  here for the same reason it fails in Android Studio's own preview. The plugin reports what the renderer said.
- Comparing a live render against a committed reference image without running Gradle is not finished, and is not
  offered in the UI as a result anyone should act on.
