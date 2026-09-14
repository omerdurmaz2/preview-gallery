# Preview Gallery

Android Studio plugin (Kotlin, IntelliJ Platform Gradle plugin). It catalogues every Compose `@Preview` in a project,
renders previews through Android Studio's own layoutlib pipeline, compares and measures them, checks Gradle screenshot
tests, and serves the catalogue over a local MCP server.

## Start here

- Before planning or changing anything, use the `preview-gallery-context` skill, or read `docs/context/README.md` and
  `docs/context/backlog.md` directly.
- Area docs in `docs/context/features/` hold each area's classes, decisions, tests and open items. Read only the ones
  the task touches.

## Rules that bite

- **Sandbox:** never run `./gradlew` while a `runIde` sandbox is live. Both
  `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` must print nothing first.
- **Compile target:** it is the live Android Studio install (`platformLocalPath` in `gradle.properties`). If a clean tree
  stops compiling on Android Studio internals, check whether Android Studio updated
  (`Contents/Resources/product-info.json`).
- **Workflow:** design spec in `docs/superpowers/specs/`, then plan in `docs/superpowers/plans/`, then implement, then
  tests, then code review. The user checks the result in a fresh `runIde`.
- **Tests:** every new test must be shown to fail with its production change reverted.
- **Code style:** no comments in new code, and no Kotlin `!!`.
- **Commits:** `[PGn-m] - Title` with a body explaining why. Work goes on `main`; the user pushes.
- **Known flaky tests:** `McpHttpServerTest` (`BindException`) and one no-module snapshot case in
  `PreviewGalleryPanelTest`.

Build gotchas and their fixes are in `docs/context/project-build-and-conventions.md`.

## Keep the context current

A change that lands updates, in the same commit:

- its area doc
- `docs/context/phase-log.md`
- `docs/context/backlog.md`
