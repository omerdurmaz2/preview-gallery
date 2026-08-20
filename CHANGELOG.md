# Compose Preview Gallery Changelog

## [Unreleased]

### Added

- `@PreviewParameter` previews render: the provider's values are resolved and the composable is rendered once per
  value, stacked in one strip with each value's label under it (capped at 16 values, and the drop is logged). A
  value whose render fails costs that value, not the set.
- Reference and parameter strips are laid out top to bottom instead of left to right, so three phone-width images
  no longer force the shared scale down until nothing is readable.
- Trackpad zoom and pan: high-precision (sub-notch) two-finger scroll now pans instead of doing nothing, one notch
  pans a usable distance, and Ctrl/Cmd+wheel and pinch zoom continuously instead of jumping a whole zoom-ladder
  rung per event. The reference strip gets the same gestures, which it had none of before.

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
