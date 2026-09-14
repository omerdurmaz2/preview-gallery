# Preview Gallery: project context

Start here. This folder is the project's working memory: what exists, how it works, why it was built that way, and
what is still open. It was written on 2026-09-14 from the code, the 310 plugin commits (PG1–PG25), and the specs and
plans in [`docs/superpowers/`](../superpowers).

## The plugin in one paragraph

Compose Preview Gallery is an Android Studio plugin that catalogues every Compose `@Preview` in a project. It renders
any of them through Android Studio's own layoutlib pipeline, compares configurations side by side, and measures spacing
on the render the way Figma does. It checks the project's Gradle screenshot tests (coverage, goldens, health, verify)
and exposes the catalogue to AI agents over a local MCP server. Version 0.1.0 shipped on 2026-08-20; the measurement
feature (PG25) is unreleased. The plugin compiles against the locally installed Android Studio, currently 2026.1.3
(platform 261).

## How to use these docs

- **Planning new work:** read [backlog.md](backlog.md) first, then the doc for the area you will touch.
- **Changing an area:** its doc lists the classes, the decisions not to undo silently, the Android Studio internals
  involved, and the tests that pin the behaviour.
- **Finding out why something is the way it is:** start at [phase-log.md](phase-log.md), then read that phase's spec,
  then the commit bodies (`git log --grep='^\[PG13-'`).
- **Building, testing, committing, environment gotchas:** see
  [project-build-and-conventions.md](project-build-and-conventions.md).
- **Claude sessions:** the repository [`CLAUDE.md`](../../CLAUDE.md) points here, and the
  [`preview-gallery-context`](../../.claude/skills/preview-gallery-context/SKILL.md) skill says which doc to read for
  which code and what to update before committing.

## Areas

| Area | Status | Phases | Covers |
|---|---|---|---|
| [Catalogue and navigation](features/catalogue-and-navigation.md) | shipped | PG1, PG8–PG10, PG24-3 | `@Preview` index, gallery tree, search and filters, Search Everywhere, editor entry points (toolbar button, gutter icon) |
| [Live rendering](features/live-rendering.md) | shipped | PG2–PG4, PG7 (design only), PG12, PG24 | layoutlib render pipeline, build-on-demand, per-`@Preview` configuration, `@PreviewParameter`, KMP, Android Studio update compatibility |
| [Property picker and comparison views](features/property-picker-and-comparison-views.md) | shipped | PG3, PG4-1, PG6 | Android Studio's `@Preview` picker opened from the gallery; copy tabs with property overrides |
| [Render view interaction](features/render-view-interaction.md) | shipped | PG4, PG5, PG12, PG24-1, PG25 | zoom, fit and dp size; pan and trackpad gestures; export; hover, double-click to source; selection and Alt distance measurement |
| [Snapshot coverage, references and health](features/snapshot-coverage-and-references.md) | shipped | PG13–PG16, PG18, PG19, PG24-1 | screenshot-test coverage badge and rows, golden strip, coverage filter and report, health checks, reference view |
| [Snapshot verify and calibration](features/snapshot-verify-and-calibration.md) | partial | PG19–PG23 | Gradle screenshot validation run from the IDE (shipped); live render vs. golden calibration (no number yet) |
| [MCP index server](features/mcp-index-server.md) | shipped | PG17, PG18 | read-only loopback MCP server exposing previews, snapshots and health to agents |
| [Project, build and conventions](project-build-and-conventions.md) | n/a | PG1, PG23-1, PG24-7…PG24-10 | repository map, Gradle and platform setup, release, test infrastructure, workflow rules, build gotchas |

## Where things stand (2026-09-14)

- **Shipped:** catalogue, live rendering, comparison views, render interaction including measurement, snapshot
  coverage, references and health, snapshot verify, the MCP server.
- **Not finished:** the screenshotTest render calibration has never produced a trustworthy number, so the in-IDE diff
  (roadmap F5) is not built. Details are in [snapshot verify and calibration](features/snapshot-verify-and-calibration.md).
- **Designed but never built:** the PG7 render performance layer (skip unneeded builds, warm render tasks, Fast
  Preview).
- **Decision pending:** `sinceBuild` 253 vs. 261. See [backlog.md](backlog.md#decisions-needed).

## Keeping this current

When a change lands, update these in the same commit:

- the area doc: behaviour, classes, decisions, tests, open items, and a History row
- [phase-log.md](phase-log.md): the phase row
- [backlog.md](backlog.md): close what was finished, add what was deferred

A new area gets its own file under `features/` with the same sections.

## Other documents

- [README](../../README.md) and [CHANGELOG](../../CHANGELOG.md)
- [Feature overview for non-engineers](../feature-overview.md)
- [Snapshot testing roadmap](../snapshot-testing-roadmap.md)
- [Original parent spec](../../compose-preview-gallery-plugin-spec.md)
- [Design specs](../superpowers/specs) and [implementation plans](../superpowers/plans)
