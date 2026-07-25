# Compose Preview Gallery Changelog

## [Unreleased]

### Added

- Config-aware rendering: each preview renders with the device, API level, size and system-UI chrome from its own
  `@Preview` annotation instead of a fixed default (falls back to the default configuration when unavailable).
- Live picker refresh: editing a value in Android Studio's `@Preview` property picker re-renders the displayed
  preview in place, without reselecting it.
- Interactive render overlay: hovering the rendered preview outlines the innermost composable under the cursor
  (resize-safe), and clicking it opens that composable's own source in the editor.
- Initial project skeleton targeting Android Studio 253.
