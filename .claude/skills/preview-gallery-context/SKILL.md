---
name: preview-gallery-context
description: Use when starting any task in the preview-gallery repository (the Compose Preview Gallery Android Studio plugin), such as planning or building a feature, fixing a bug, explaining how part of the plugin works, or reviewing a change, and again before committing a change there.
---

# Preview Gallery context

## Overview

The project's working memory is `docs/context/`: what exists, how it works, which decisions are deliberate, and what is
still open. Read the slice the task needs before touching code. Leave it true after changing code. The whole set is
about 64k tokens, so pick files with the table below instead of reading everything.

## Before work

1. Read `docs/context/README.md` for the area map and the current state.
2. Read `docs/context/backlog.md` when choosing or scoping work.
3. Open only the area doc for the code you will touch:

| Touching | Read |
|---|---|
| `index/`, `search/`, `searcheverywhere/`, `editor/`, the gallery tree | `docs/context/features/catalogue-and-navigation.md` |
| `LiveRenderer`, `RenderModelResolver`, `RenderPipeline`, `BuildService`, render states | `docs/context/features/live-rendering.md` |
| picker bridges, `OverrideMerge`, `ViewOverride`, comparison tabs | `docs/context/features/property-picker-and-comparison-views.md` |
| `ZoomableRenderView`, `ZoomMath`, `ViewportGestures`, export, measurement | `docs/context/features/render-view-interaction.md` |
| snapshot rows, coverage, reference images, health checks | `docs/context/features/snapshot-coverage-and-references.md` |
| `SnapshotVerifyRunner`, `ImageDiff`, screenshotTest class loader, calibration | `docs/context/features/snapshot-verify-and-calibration.md` |
| `mcp/`, the MCP server action and dialog | `docs/context/features/mcp-index-server.md` |
| Gradle, `plugin.xml`, release, test infrastructure, commit rules, build failures | `docs/context/project-build-and-conventions.md` |

4. The area doc's "Key decisions" are constraints. If a change reverses one, say so and update the doc.
5. Where code and a doc disagree, the code is right. Fix the doc in the same change.

## Before committing

Update, in the same commit:

- **The area doc:** "What the user gets", the class table, "Key decisions", "Tests", "Open items", and one new
  History row.
- **`docs/context/phase-log.md`:** the phase row. A new phase gets a new row.
- **`docs/context/backlog.md`:** remove what the change finished, and add what it deferred under the right category.
- **`docs/context/README.md`:** only if an area's status changed or an area was added. A new area gets
  `docs/context/features/<name>.md` with the same sections.

## Quick reference

- Why a phase went the way it did: `git log --reverse --format='%h %s%n%b' --grep='^\[PG13-'`
- Design specs are in `docs/superpowers/specs/`; plans are in `docs/superpowers/plans/`.
- Snapshot feature roadmap: `docs/snapshot-testing-roadmap.md`.

## Common mistakes

- **Reading every doc up front.** It burns context; choose by the table.
- **Closing a backlog item from the doc alone.** Check the code before calling it resolved.
- **Adding a History row but leaving "Open items" and the backlog stale.** They are what the next session plans from.
