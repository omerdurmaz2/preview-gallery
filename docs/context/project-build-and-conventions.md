# Project Build and Conventions

**Status:** shipped · **Phases:** PG-1, PG3-1, PG23-1, PG24-7–PG24-10 · **Last change:** 8667333 [PG24-10] 2026-09-14

The parts of the project no single feature owns: the repository layout, the Gradle/`plugin.xml` build, how a release
is cut, the test-fixture split, and the commit/workflow/code conventions every phase follows. Current code and the
commits above are the source; where a decision predates all of them it is the original parent spec.

## What this covers

- Where every package lives and which feature doc explains what it does (Repository map).
- What `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` / `plugin.xml` actually configure, and why
  (Build setup).
- How tests are split between two fixture styles, and which ones are known-flaky (Test infrastructure).
- How `0.1.0` was cut and how the README / changelog / feature overview are kept honest (Release).
- The commit format, the brainstorm → spec → plan → implement → test → review pipeline, and the code rules every
  phase follows (Engineering conventions and workflow).
- The operational traps that break a build or a `runIde` session if ignored (Build and environment gotchas).

## Repository map

Single Gradle module, single Kotlin source set (`src/main/kotlin`), package-per-feature under
`com.devomer.previewgallery` (107 files) mirrored by `src/test/kotlin` (80 files). Most packages map cleanly to one
feature doc; three of the largest (`render`, `service`, `ui`) are split across several because they hold one class
per feature rather than one class for the whole package.

| Package | Files | Responsibility | Feature doc |
|---|---|---|---|
| `com.devomer.previewgallery` (root) | 1 | [`PreviewGalleryBundle`](../../src/main/kotlin/com/devomer/previewgallery/PreviewGalleryBundle.kt), the `DynamicBundle` wrapper for user-facing strings in `messages/PreviewGalleryBundle.properties` | shared, no single doc |
| `editor` | 8 | Editor-side entry points into the gallery: gutter line marker, preview-toolbar button injection, caret→preview resolution, split-editor tracking | `features/catalogue-and-navigation.md` |
| `index` | 7 | The `FileBasedIndex` that finds every `@Preview` function from PSI and persists file-local facts, independent of the project model | `features/catalogue-and-navigation.md` |
| `mcp` | 5 | MCP/JSON-RPC plumbing: HTTP server, dispatcher, tool registry, per-request project snapshot | `features/mcp-index-server.md` |
| `mcp.tools` | 5 | The individual read-only MCP tools (list previews/projects/snapshots, coverage report, snapshot health) | `features/mcp-index-server.md` |
| `model` | 10 | Shared data classes: indexed/query-time preview facts, render config/outcome, reference image, snapshot coverage, comparison overrides | shared, consumed by every feature doc |
| `render` | 19 | The render engine, the `@Preview` picker bridge, and screenshot-test class loading / image diffing | split — see below |
| `search` | 3 | Pure predicates over `PreviewRow`: name, module and coverage filters | split — name/module in `features/catalogue-and-navigation.md`, coverage in `features/snapshot-coverage-and-references.md` |
| `searcheverywhere` | 2 | `SearchEverywhereContributor` for previews | `features/catalogue-and-navigation.md` |
| `service` | 15 | Project-level services wrapping the index, reference images, coverage, health and the MCP server lifecycle | split — see below |
| `ui` | 32 | The tool window panel and almost every Swing component: trees, render view, comparison views, dialogs, actions | split — see below |

Packages that don't map 1:1 to a feature doc:

- **`render/`** — [`LiveRenderer`](../../src/main/kotlin/com/devomer/previewgallery/render/LiveRenderer.kt),
  `BuildService`, `RenderPipeline`, `RenderApiProbe`, `RenderModelResolver`, `RenderTaskContext`,
  `RenderedImageInspector`, `AndroidModuleResolver`, `ModuleFreshness` → `live-rendering.md`; `PreviewPickerBridge`,
  `EphemeralPickerBridge`, `GalleryPickerTracker`, `OverrideMerge`, `PreviewAnnotationLocator` →
  `property-picker-and-comparison-views.md`; `ScreenshotTestClassLoader`, `ScreenshotTestClasses`,
  `SnapshotVerifyRunner`, `ImageDiff` → `snapshot-verify-and-calibration.md`.
- **`service/`** — `PreviewIndexService`, `ModuleDirectoryResolver` → `catalogue-and-navigation.md`;
  `ReferenceImageLocator`, `ReferenceRoots`, `SnapshotCoverageResolver`, `CoverageReport`, `SnapshotSourceScanner`,
  `SnapshotHealth`, `HealthReport` → `snapshot-coverage-and-references.md`; `SnapshotVerifyResults`,
  `SnapshotVerifyStore`, `VerifyFailureNotificationText`, `GoldenInspector` → `snapshot-verify-and-calibration.md`;
  `McpServerService`, `McpServerStartup` → `mcp-index-server.md`.
- **`ui/`** — tree/model-builder, module-filter, refresh and indexing-gate files → `catalogue-and-navigation.md`;
  zoom/pan/measurement/export/hit-testing files → `render-view-interaction.md`; comparison-view-list and
  compare-action → `property-picker-and-comparison-views.md`; reference-strip and coverage-toggle/report actions →
  `snapshot-coverage-and-references.md`; the verify action → `snapshot-verify-and-calibration.md`; the MCP dialog,
  action and client config → `mcp-index-server.md`. [`PreviewGalleryPanel`](../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt)
  itself is the hub that wires all of them together.

## Build setup

Bootstrapped from JetBrains' IntelliJ Platform Plugin Template in `3d580c7 [PG-1]` — the vendored
`.github/readme/intellij-platform-plugin-template*` assets and the template's multi-year Gradle-config history
(Kotlin/IntelliJ-platform/changelog plugin version bumps, `libs.versions.toml` added and later removed) sit under
that commit. No Gradle version catalog today: every plugin/library version is inlined directly in
`build.gradle.kts` / `settings.gradle.kts`.

| File | Setting | Value | Why |
|---|---|---|---|
| [`gradle.properties`](../../gradle.properties) | `platformLocalPath` | the user's real, live Android Studio install (currently AI-261.26222.65 / AS 2026.1.3) | compiles against the exact IDE it runs in — the render pipeline needs AS-internal classes, not a generic IntelliJ Platform artifact |
| `gradle.properties` | `org.gradle.jvmargs` | `-Xmx3g` | Gradle's 512 MiB default GC-thrashed compiling against the platform (`fd2f741 [PG-1]`) |
| `gradle.properties` | `kotlin.stdlib.default.dependency` | `false` | stdlib is already on the IDE's classpath |
| `gradle.properties` | `org.gradle.configuration-cache`, `org.gradle.caching` | `true`, `true` | faster incremental builds; both have failure modes, see Gotchas |
| [`build.gradle.kts`](../../build.gradle.kts) | `kotlin { jvmToolchain(21) }` | 21 | matches README's "JDK 21, to build it" |
| `build.gradle.kts` | `bundledPlugins(...)` | `org.jetbrains.kotlin`, `org.jetbrains.android`, `com.android.tools.design` | compile-time half of `plugin.xml`'s `<depends>`; must stay in sync with it |
| `build.gradle.kts` | `testFramework(TestFrameworkType.Platform)` + `testImplementation("junit:junit:4.13.2")` | — | two test styles side by side, see Test infrastructure |
| `build.gradle.kts` | `ideaVersion { sinceBuild; untilBuild }` | `"253"`; left `provider { null }` | see Key decisions and Open items |
| [`settings.gradle.kts`](../../settings.gradle.kts) | `org.jetbrains.intellij.platform.settings` | `2.16.0` | IntelliJ Platform Gradle Plugin version |
| `settings.gradle.kts` | `org.jetbrains.kotlin.jvm` | `2.3.21` | Kotlin Gradle plugin version |
| [`gradle/wrapper/gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties) | `distributionUrl` | Gradle 9.5.0 | wrapper-managed |

The `com.android.tools.design` bundled plugin was added in `21f61b4 [PG3-1]` specifically to reach the `@Preview`
property-picker classes; the original spec had claimed `org.jetbrains.android` hosted them, corrected after a
scratch-file compile check (build-file half only — the picker code itself belongs to
`features/property-picker-and-comparison-views.md`).

**Changelog wiring.** `org.jetbrains.changelog` (`2.5.0`) was applied from the template but did nothing until
`5b5df4a [PG24-8]` wired `pluginConfiguration.changeNotes` to render the current version's (or `Unreleased`'s)
`CHANGELOG.md` section as HTML, shown in Settings > Plugins — "which build is this" needs to be answerable without
asking whoever built the zip.

**Plugin icon.** `META-INF/pluginIcon.svg` / `pluginIcon_dark.svg` (light/dark) were added in `89ee9cf [PG23-1]` and
are picked up by the IntelliJ Platform purely by filename — no `plugin.xml` entry needed. The same commit also added
the tool window's own icon and a coverage-badge icon; those are feature-level UI and covered in their own docs.

**`plugin.xml` overall.** ID `com.devomer.previewgallery`, vendor `devomer`, one CDATA `<description>`, four
`<depends>` (`com.intellij.modules.platform`, `org.jetbrains.kotlin`, `org.jetbrains.android`,
`com.android.tools.design`), one resource bundle. Extension points:

| Extension / element | Declares | Why this shape |
|---|---|---|
| `fileBasedIndex` | `PreviewIndex` | the catalogue's data source |
| `toolWindow id="Compose Gallery"` | `PreviewGalleryToolWindowFactory`, right anchor, own icon | the plugin's one UI surface |
| `searchEverywhereContributor` | `PreviewSearchEverywhereContributorFactory` | previews reachable from Shift-Shift |
| `notificationGroup id="Compose Preview Gallery"` | `BALLOON` | render/verify failures surface as IDE notifications |
| `codeInsight.lineMarkerProvider` (kotlin) | `ShowAllPreviewsLineMarkerProvider` | an ordinary line marker, not a `runLineMarkerContributor` — the latter would merge into the run-gutter group and need a popup opened first (comment in the file) |
| `postStartupActivity` | `McpServerStartup` | starts MCP server state when the project opens |
| `org.jetbrains.kotlin.supportsKotlinPluginMode` | `supportsK2="true"` | required or the Kotlin plugin silently drops every extension above in K2 mode — found via the phase's first real test run, `27ac6d0 [PG-0]` |
| `projectListeners` | `PreviewToolbarInjector.Listener` on `FileEditorManagerListener` | reacts to editor changes to (re)inject the toolbar button |
| `actions` | `PreviewGallery.ShowAllPreviews`, registered by id, not added to a group | survives toolbar injection failing — AS's Compose preview toolbar is programmatic and unreachable via add-to-group; still reachable via Find Action |

Full file: [`src/main/resources/META-INF/plugin.xml`](../../src/main/resources/META-INF/plugin.xml).

## Test infrastructure

Two fixture styles, chosen per test's needs, both under plain `./gradlew test` — no separate suite or task:

- **`BasePlatformTestCase`** (the IntelliJ Platform's light-fixture base, from `testFramework(TestFrameworkType.Platform)`) —
  29 of 78 test classes, for anything touching PSI, the index, the VFS or the project/module model, e.g.
  [`PreviewIndexTest`](../../src/test/kotlin/com/devomer/previewgallery/index/PreviewIndexTest.kt),
  `McpServerServiceTest`, `PreviewGalleryPanelTest`.
- **Plain JUnit4** (`org.junit.Test` against `junit:junit:4.13.2`) — the remaining 49, for logic with no platform
  dependency, e.g. `ZoomMathTest`, `ImageDiffTest`. Some classes test only the pure half of a platform-integrated
  file by design — [`RenderPipelineTest`](../../src/test/kotlin/com/devomer/previewgallery/render/RenderPipelineTest.kt)
  covers only `RenderPipeline.classify`, "the pure part of the pipeline's state machine", because the async
  EDT/executor half has no seam to substitute a test double (no mocking framework is configured in this project) and
  is instead checked manually at a `runIde` gate.

**Shared helper:** [`ExcludedRoots.kt`](../../src/test/kotlin/com/devomer/previewgallery/ExcludedRoots.kt)
(`withExcludedRoot`), added in `16ecbe3 [PG14-5]` — marks/unmarks a directory as an excluded module root so a
`BasePlatformTestCase` fixture can reproduce a project whose `screenshotTest` source set never reached the IDE's
project model (outside `GlobalSearchScope.projectScope`, absent from `ProjectFileIndex`, still present on the VFS).
Without it a light-project fixture cannot tell "the index doesn't have this" apart from "the VFS doesn't have this".

No `src/test/resources` — fixtures are built in code (`myFixture.configureByText`, `PsiTestUtil`, temp directories),
not loaded from committed sample files.

**Known flaky tests** (fail for reasons unrelated to the change under test — check before chasing a red full suite;
both are covered in their own feature docs too):

- [`McpHttpServerTest`](../../src/test/kotlin/com/devomer/previewgallery/mcp/McpHttpServerTest.kt) — some of its 8
  cases intermittently throw `java.net.BindException: Address already in use`. Its own `freePort()` helper (line 22)
  opens a socket to grab a free port and closes it before `McpHttpServer.start()` re-binds that port (line 26) — a
  TOCTOU race between the two.
- [`PreviewGalleryPanelTest`](../../src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt) —
  `` `test a snapshot the project model places in no module still shows its references` `` (line 481) has failed
  once in a full run and passed alone and on retry; looks order- or fixture-dependent.

## Release

`0.1.0` was cut in `5b5df4a [PG24-8]` on 2026-08-20 — the first zip handed to the team. `gradle.properties` still
reads `version = 0.1.0`; `CHANGELOG.md`'s `[Unreleased]` section has since grown (the PG25 distance-measurement
feature) — normal Keep-a-Changelog flow, not a gap.

- **[`CHANGELOG.md`](../../CHANGELOG.md) format:** `[Unreleased]` at the top, then dated version sections
  (`## [0.1.0] - 2026-08-20`), each broken into `### Added` / `### Changed` / `### Fixed` / `### Known limitations`
  as needed. PG24-8 rewrote it from "the running log of commits it had grown into" into prose a reader would want.
- **[`README.md`](../../README.md):** rewritten end-to-end in `ae83887 [PG24-9]` — the previous copy still opened
  with "Phase 1 — indexing, search and navigation. Preview rendering arrives in Phase 2", wrong since PG2. Current
  shape: problem statement → what it does (3 groups) → Requirements (AS Panda 4 / 253+, JDK 21) → Installing →
  Building (`./gradlew test|buildPlugin|runIde`, with the `runIde`-concurrency warning) → Known limitations →
  documentation links.
- **[`docs/feature-overview.md`](../feature-overview.md):** added in `fad7123 [PG24-7]`, a one-page, non-technical
  walkthrough (problem → 4 feature groups → a savings table → an honest status section) meant to be handed to a
  non-engineer. It and the README state the same three known limitations (a component needing a theme wrapper the
  preview doesn't provide; the Gradle-free snapshot comparison not being trustworthy yet; test generation being left
  to an agent) — deliberately said "out loud" in both, per the PG24-7/PG24-9 commit bodies.
- **No CI/CD.** `.github/` has issue templates and a Dependabot config (leftovers from the plugin template) but no
  `workflows/` directory — building (`./gradlew buildPlugin` → `build/distributions/preview-gallery-<version>.zip`)
  and publishing a release are both manual. See Open items for the feature-overview screenshots this also affects.

## Engineering conventions and workflow

- **Commits:** `[PGn-m] - Title` (phase `n`, task `m`); phase 1 used a flat `PG-N` counter before the two-part
  scheme started at PG3 (`3d580c7 [PG-1]`, `4093e99 [PG-9]`, …). The body explains why, not what. Trailer:
  `Co-Authored-By: Claude <model name> <noreply@anthropic.com>`, model name unbracketed.
- A phase typically opens with a `-0`/`-1` spec-and-plan pair (phase 1: the `[PG-1]` design commit plus `[PG-0]`
  plan commits). `-0` is also reused later for plan corrections discovered mid-implementation — `27ac6d0 [PG-0]`
  fixed both `plugin.xml` and a test after phase 1's first real test run, alongside a plan-doc edit.
- **Workflow:** brainstorm → design spec in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` → plan in
  `docs/superpowers/plans/` → implement (production code first — this repo's standing rule overrides any skill that
  would mandate TDD) → tests written once the feature works → code review → the user verifies in a fresh `runIde`
  sandbox → done. Larger plans run through subagent-driven development: one implementer per task, a task-level
  review, a final whole-branch review, one fix wave.
- **Test rule:** every new test must be shown to fail with its production change reverted. Self-review and the
  per-task review can both miss a test that doesn't exercise its own call site — only a revert-and-rerun catches
  it. PG21 shipped two such tests (one defeated by a fixture whose parent directories carried a `now` mtime; one
  built its renderer with a null project, so the branch under test never ran) — caught only at final review.
- **Code rules:** no comments in new code (pre-existing code, e.g. most of `render/` and `index/`, is heavily
  KDoc'd); never use Kotlin `!!`.
- **Push:** `origin` is an HTTPS GitHub remote; this agent environment holds no credentials for it. Work is
  committed directly on `main`, and the user runs `git push origin main` by hand afterward.
- **Context for Claude sessions:**
  - The repository-root [`CLAUDE.md`](../../CLAUDE.md) loads in every session opened on this repo. It holds the rules
    above in short form and points at `docs/context/`.
  - The project skill [`preview-gallery-context`](../../.claude/skills/preview-gallery-context/SKILL.md) says which
    context doc to read for which code, and what to update before committing. Claude Code discovers skills under
    `.claude/skills/` whenever this repo is the working directory or an added directory, so no symlink into
    `~/.claude/skills` is needed. A symlink there would make it a personal skill listed in every project.
  - `CLAUDE.md` itself does not load when the repo is only an added directory, unless
    `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1` is set; the skill still does.

## Key decisions

- Compile against a live, local Android Studio install rather than a pinned IntelliJ Platform artifact — the render
  pipeline needs AS-internal classes unavailable in a generic distribution — `gradle.properties`, `3d580c7 [PG-1]`.
- `untilBuild` left open, only `sinceBuild` pinned — an aggressive upper pin turns a soft rendering failure into a
  refusal to load — `compose-preview-gallery-plugin-spec.md` §7.7, restated as a comment in `build.gradle.kts`.
- K2 plugin-mode support explicitly declared — otherwise required or the Kotlin plugin drops every extension this
  plugin declares — `27ac6d0 [PG-0]`.
- `com.android.tools.design` added as a bundled + runtime dependency solely to reach the `@Preview` property-picker
  classes, correcting the original spec's assumption that `org.jetbrains.android` hosted them — `21f61b4 [PG3-1]`.
- Gradle daemon heap raised to 3g — compiling against the local AS platform GC-thrashed at Gradle's 512 MiB default
  — `fd2f741 [PG-1]`.
- Changelog plugin wired to the plugin descriptor only at the first real release, not at bootstrap — a
  team-distributed zip is exactly the case where "what's in this build" must be answerable from Settings > Plugins
  — `5b5df4a [PG24-8]`.
- Two-tier test setup (`BasePlatformTestCase` for platform-touching code, plain JUnit for pure logic) rather than
  one framework for everything, so pure-logic tests don't pay the light-fixture's startup cost — `build.gradle.kts`.

## Android Studio and platform internals relied on

- The build depends on Android Studio-internal, non-public classes (render/layoutlib internals in `render/`, the
  `@Preview` picker in `com.android.tools.design`) rather than stable IntelliJ Platform API — the reason for
  compiling against a live local install instead of a versioned artifact. The reflective probes and exact API names
  are the rendering doc's concern (`RenderApiProbe`); this doc owns only the build consequence: an AS self-update
  moves the compile target with no warning (see Gotchas).
- `.intellijPlatform/layoutIndex/*.json` — one cache file per AS build the project has compiled against; both
  `AI-253.32098.37...json` and `AI-261.26222.65...json` are present today. A stale or missing entry here is a
  symptom of a moved compile target, not a cause.
- Bundled-plugin coupling: a class pulled in at compile time via `bundledPlugins(...)` must also appear in
  `plugin.xml`'s `<depends>`, or it compiles but is absent at runtime.

## Build and environment gotchas

- **Never run `./gradlew` (any task) while a `runIde` sandbox is live.** The sandbox
  (`.intellijPlatform/sandbox/<project>/<build>/`) auto-reloads the plugin jar a concurrent build is rewriting.
  Check liveness with `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"`. Symptom:
  `ZipException: invalid distance too far back` loading plugin resources, then a dropped `PreviewIndex` and an
  empty tool window. Recovery: close the sandbox, delete
  `<sandbox>/system/index/com.devomer.previewgallery.previewindex/` and its `.persistent/` sibling, restart
  `runIde` for a full rescan.
- **Config cache corruption:** `instrumentIdeaExtensions doesn't support the nested "skip" element`. Fix with
  `--no-configuration-cache` or by deleting `.gradle/configuration-cache`.
- **`:instrumentCode` / `:instrumentTestCode` race** under parallel execution. Use `--max-workers=1 --no-parallel`.
- **Stale build-cache bytecode:** the Gradle build cache can serve a pre-change test class after a signature
  change, surfacing as `NoSuchMethodError` naming the *old* signature even though everything compiled. Use
  `--no-build-cache --rerun-tasks`.
- **Android Studio updates itself silently**, and `platformLocalPath` points at that live install: on 2026-08-31
  it moved from Panda 4 (AI-253.32098.37) to 2026.1.3 (AI-261.26222.65), breaking two render calls with no warning
  (`8667333 [PG24-10]`). Check `Contents/Resources/product-info.json`'s `buildNumber` and
  `.intellijPlatform/layoutIndex/` first when a clean tree stops compiling on AS-internal symbols. On 261,
  `JBUI.scale(float)` is deprecated — use `JBUIScale.scale(float)`.

## Open items

- [gap] `sinceBuild` is still `"253"` even though the plugin now compiles against platform 261. Panda 4 (253) has
  no `getRenderedImage`, so `RenderApiProbe` turns rendering off there rather than crashing, but whether to raise
  `sinceBuild` to 261 or keep 253 with that degraded fallback is undecided — [`build.gradle.kts`](../../build.gradle.kts),
  `8667333 [PG24-10]`.
- [gap] The four screenshots `docs/feature-overview.md` references (`images/preview-screen.png`,
  `images/preview-config.png`, `images/snapshot-diff.png`, `images/mcp-server.png`) have never resolved —
  [`docs/images/`](../images) is empty and has never held a tracked file since the doc was added in
  `fad7123 [PG24-7]`.
- [gap] No CI/CD: no `.github/workflows`. Build, test and release-zip creation are manual, and the README's pointer
  to a GitHub Releases page presumes a publish step that nothing automates — `.github/`, [`README.md`](../../README.md)
  "Installing".
- [debt] Pre-existing compiler warnings on platform 261, not yet cleaned up: deprecated `ReadAction.compute` /
  `updateActionsImmediately` / `Disposer.isDisposed` calls in `render/`, `ui/`, `service/`, `searcheverywhere/` and
  `editor/` (confirmed by grep), plus `INVISIBLE_REFERENCE` suppressions in `render/GalleryPickerTracker.kt`,
  `EphemeralPickerBridge.kt` and `PreviewPickerBridge.kt`.
- [debt] Known flaky tests `McpHttpServerTest` (`BindException` race in `freePort()`) and `PreviewGalleryPanelTest`'s
  no-module-snapshot case — see Test infrastructure; also listed in their own feature docs.

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG-1 (bootstrap slice) | 2026-07-23 | `3d580c7`, `fd2f741`, `27ac6d0` | Project bootstrapped from the IntelliJ Platform Plugin Template; Gradle daemon heap raised to 3g; K2 plugin-mode support declared after the phase's first real test run |
| PG3-1 (build-file slice) | 2026-07-24 | `21f61b4` | `com.android.tools.design` added as a bundled + runtime dependency for the `@Preview` picker API |
| PG23-1 | 2026-08-19 | `89ee9cf` | Plugin given its own Marketplace icon (`pluginIcon.svg` / `_dark.svg`) |
| PG24-7 – PG24-9 | 2026-08-20 | `fad7123`, `5b5df4a`, `ae83887` | `docs/feature-overview.md` written; `0.1.0` cut and the changelog plugin wired into the build; README rewritten for a repo newcomer |
| PG24-10 (build slice) | 2026-09-14 | `8667333` | Compile target follows Android Studio's self-update to 2026.1.3 / platform 261; `sinceBuild` held at 253 |
| PG26-1 | 2026-09-14 | this commit | `docs/context/` written from the code and history; repository `CLAUDE.md` and the `preview-gallery-context` project skill added so every session starts from it |

## References

- [`compose-preview-gallery-plugin-spec.md`](../../compose-preview-gallery-plugin-spec.md) — parent spec: §4 target
  environment, §7.7 API stability strategy, §13 open questions
- [`docs/superpowers/plans/2026-07-23-preview-gallery-phase1.md`](../superpowers/plans/2026-07-23-preview-gallery-phase1.md) — the K2/index-key fix landed as a plan correction (`27ac6d0`)
- [`README.md`](../../README.md), [`CHANGELOG.md`](../../CHANGELOG.md), [`docs/feature-overview.md`](../feature-overview.md)
- [`docs/snapshot-testing-roadmap.md`](../snapshot-testing-roadmap.md) — for the snapshot-verify open items this doc only lists in one line
