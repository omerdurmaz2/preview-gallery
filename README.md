# Compose Preview Gallery

An Android Studio plugin that gives you a project-wide, searchable browser for Jetpack Compose `@Preview`
functions.

## Status

Phase 1 — indexing, search and navigation. Preview rendering arrives in Phase 2.

## Requirements

- Android Studio Panda 4 (platform branch 253)
- JDK 21

## Building

The plugin is compiled against a local Android Studio install. Point `platformLocalPath` in `gradle.properties`
at your own install if it is not at `~/Applications/Android Studio.app`, then:

    ./gradlew build       # compile and run the tests
    ./gradlew runIde      # launch a sandbox IDE with the plugin installed

## Documentation

- [Plugin spec](compose-preview-gallery-plugin-spec.md)
- [Phase 1 design](docs/superpowers/specs/2026-07-23-preview-gallery-phase1-design.md)
- [Phase 1 plan](docs/superpowers/plans/2026-07-23-preview-gallery-phase1.md)
