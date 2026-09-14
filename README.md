# Compose Preview Gallery

An Android Studio plugin that turns every Jetpack Compose `@Preview` in a project into one browsable,
searchable catalogue — and connects that catalogue to the team's screenshot tests.

Android Studio renders previews only for the file you have open. In a codebase with hundreds of components
across dozens of modules, that means nobody can see what already exists, nobody can see which components have
a screenshot test, and checking a snapshot costs a full Gradle run. This plugin answers all three from one
panel.

## What it does

**Find and render any preview**

- Every `@Preview` in the project, grouped by module and package, searchable by component name, function name
  or package — built from the code and kept current as the code changes.
- Rendered through Android Studio's own renderer, using the device, API level, size and system-UI chrome from
  each preview's own annotation. Kotlin Multiplatform previews in `commonMain` render against their Android
  target.
- `@PreviewParameter` previews render once per provider value, stacked with each value labelled.
- Zoom, pan, save as PNG, copy to clipboard. Double-click any part of a render to open the code that draws it.
- Measure spacing the way Figma does: click a component, hold Alt (Option on macOS) and point at another to read
  the distance between them in dp, or the four paddings when one contains the other.
- Comparison views: several copies of one preview side by side, each with its own configuration, edited
  through Android Studio's own `@Preview` property picker.
- Reachable from the editor: a gutter icon on every `@Preview` function, a button on the preview toolbar, and
  Search Everywhere.

**See and run the screenshot tests**

- A coverage badge per component, plus a filter for the ones no screenshot test covers, and a Markdown
  coverage report that counts every module — including those that never adopted screenshot testing.
- The committed reference images for a snapshot, discovered per build variant and refreshed from disk before
  every lookup.
- One button runs the project's own `validate…ScreenshotTest` task and shows, per snapshot, the reference
  image, what was rendered, the diff, and the engine's own differing-pixel percentage. Failing rows are marked
  in the tree; a result measured before the last code change is marked stale.
- A health check for snapshot names that no longer match any component, and for degenerate reference images.

**Hand the same data to an AI assistant**

- A read-only MCP server, started from the toolbar, exposing the catalogue, the coverage numbers and the
  health check — so "write the missing screenshot tests for this module" becomes a request an agent can act on
  from the same list a developer sees.

For a walkthrough written for a non-engineer, see [docs/feature-overview.md](docs/feature-overview.md).

## Requirements

- Android Studio Panda 4 (platform branch 253) or newer
- JDK 21, to build it

## Installing

Build a zip yourself (below) — or take one from [Releases](../../releases) once a build is published — then:

    Android Studio → Settings → Plugins → ⚙ → Install Plugin from Disk… → the .zip → Restart IDE

The gallery opens from the **Compose Gallery** tool window on the right.

## Building

The plugin compiles against a local Android Studio install. Point `platformLocalPath` in `gradle.properties`
at yours if it is not at `~/Applications/Android Studio.app`, then:

    ./gradlew test          # run the test suite
    ./gradlew buildPlugin   # produce build/distributions/preview-gallery-<version>.zip
    ./gradlew runIde        # launch a sandbox IDE with the plugin installed

Never run another Gradle task while a `runIde` sandbox is live: the sandbox reads the plugin jar a concurrent
build is rewriting.

## Known limitations

- A component that cannot render on its own — one needing a theme wrapper its `@Preview` does not provide —
  fails here for the same reason it fails in Android Studio's own preview. The plugin reports what the
  renderer said; it cannot fix the component.
- Comparing a live render against a committed reference image *without* running Gradle is not finished. The
  groundwork is in place (see the calibration specs below), but the number is not trustworthy yet and is not
  presented as a feature.
- Generating the missing screenshot tests is deliberately not in the plugin — it is left to an assistant over
  MCP, which can read the real component and the project's conventions instead of filling in a template.

## Documentation

- [Feature overview](docs/feature-overview.md) — what it does, in plain language
- [Changelog](CHANGELOG.md)
- [Plugin spec](compose-preview-gallery-plugin-spec.md) — architecture and design rules
- [Snapshot testing roadmap](docs/snapshot-testing-roadmap.md) — what shipped, what is left, and why
- [Designs and plans](docs/superpowers) — one design document per feature, with the reasoning behind each
  decision and the evidence it rests on

## License

[Apache 2.0](LICENSE)
