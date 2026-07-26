# Compose Preview Gallery Changelog

## [Unreleased]

### Added

- Config-aware rendering: each preview renders with the device, API level, size and system-UI chrome from its own
  `@Preview` annotation instead of a fixed default (falls back to the default configuration when unavailable).
- Live picker refresh: editing a value in Android Studio's `@Preview` property picker re-renders the displayed
  preview in place, without reselecting it.
- Interactive render overlay: hovering the rendered preview outlines the innermost composable under the cursor
  (resize-safe), and clicking it opens that composable's own source in the editor.
- Zoom and pan the render: a stepped zoom ladder (25%–400%) driven by the toolbar buttons, `Ctrl`+wheel, and macOS
  trackpad pinch — all cursor-anchored — with panning via scrollbars, the scroll wheel, two-finger trackpad scroll,
  and a hand-tool drag mode. A smaller-than-pane preview fits without upscaling.
- Export the render: save the raw preview as a PNG file or copy it to the system clipboard at full resolution
  (no overlay); failures notify without crashing.
- Iconified render toolbar: the zoom, fit, actual-size, hand-tool, save, copy, and properties controls are now
  native icon buttons with tooltips, replacing the previous text links.
- Same-named-file click-to-source: clicking a composable whose file name is shared by another project file now
  opens the correct file, disambiguated by the source's package hash (falls back to the previous behavior when the
  hash is unavailable).
- Initial project skeleton targeting Android Studio 253.
