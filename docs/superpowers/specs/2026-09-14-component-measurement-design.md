# Design — measure the distance between two components in a rendered preview

**Status:** approved in conversation, not implemented ·
**Feature:** Figma-style select + Alt-hover distance measurement on the live render ·
**Commit prefix:** `PG25`

## Goal

A developer checking a preview against a design wants to read spacing off the render the way Figma shows it:
select one component, hold Alt (Option on macOS), point at another, and read the distance in dp on a line drawn
between them. Today the render only outlines the component under the pointer and navigates to its source on click.

## Interaction

**D1 — Click selects, double-click navigates.** A single left click selects the innermost component at the point
(`PreviewViewHitTester.innermostAt`) and keeps its outline on screen. Clicking empty space clears the selection;
clicking the selected component again keeps it selected. A double click (`clickCount == 2`) navigates to source
exactly as a single click does today. The first click of a double click selects, which is harmless. Clicks with
`clickCount > 2` do nothing.

This changes an existing habit: navigating to source used to take one click. It matches Figma, which this feature
copies, and it keeps selecting from moving the editor and stealing focus while the user measures.

**D2 — Outlines.** The selection outline is 2 px (`JBUI.scale(2)`) in the existing `HOVER_OUTLINE` color. The hover
outline stays 1 px, as today, and is drawn together with the selection. It is skipped when the hovered node is the
selected node itself.

**D3 — Alt measures.** When a component is selected, Alt is down, and the pointer is over a *different* node, the
view draws the measurement lines from [Measurement rules](#measurement-rules) with a dp label on each. Releasing Alt
removes them. Nothing is drawn when there is no selection or no hovered node.

Alt state comes from two sources, so the overlay keeps up in both focus situations:

- **Key events.** The view becomes focusable and requests focus on a single click. `VK_ALT` press and release repaint
  immediately, so pressing Alt without moving the mouse shows the measurement at once.
- **Mouse events.** Every mouse move reads `MouseEvent.isAltDown`. This covers the case where focus is elsewhere,
  for example the editor after a double-click navigation.

Losing focus clears the key-event Alt state, so a release that happens outside the view (Alt+Tab) cannot leave
the lines stuck. The next mouse move re-reads the real state.

**D4 — Esc clears the selection.** Esc is handled by a component-scoped action (`DumbAwareAction` registered with
`CommonShortcuts.ESCAPE` on the view). It is enabled only while something is selected, so once nothing is selected
Esc keeps its normal tool-window meaning.

**D5 — Hand tool.** While the hand tool is active, the selection stays drawn but clicks do not change it, and no
measurement is drawn. The hover outline is already suppressed in that mode.

**D6 — Lifetime.** `setContent` and `clearContent` clear the selection. A new render is a new tree, and nodes are not
matched across trees.

**D7 — Where it works.** It works wherever a `ZoomableRenderView` shows a render with a view tree: the live render
and every comparison tab, each with its own selection. It does not work in `@PreviewParameter` strips or snapshot
reference strips, which carry images without view trees and have no hover outline today either.

## Measurement rules

`S` is the selected node's bounds and `H` is the hovered node's bounds, both in render pixels. On each axis the
bounds form an interval `[start, end]` with `end = start + size`. An axis is **disjoint** when `S.end <= H.start` or
`H.end <= S.start`. The rectangles **intersect** when neither axis is disjoint; containment counts as intersecting.
Every line runs from its lower coordinate to its higher one along its own axis.

**R1 — Not intersecting: gap lines.** Every disjoint axis gets one line between the two facing edges:

- **Placement across the axis.** If the other axis overlaps, the line sits at the middle of that overlap.
- **Diagonal case.** If neither axis overlaps, the line sits on `S`'s center on the other axis. A dashed guide then
  runs from whichever end of the line lies on `H`'s edge, across the other axis, to the `H` edge facing `S` on that
  axis: `H`'s start edge when `H` lies after `S`, its end edge otherwise. The line then visibly reaches `H`.

In practice: side by side gives one horizontal line, stacked gives one vertical line, diagonal gives both, each with
its guide.

**R2 — Intersecting: edge offsets.** Each axis gets two lines, one between the start edges and one between the end
edges, which gives left, right, top and bottom. Each sits at the middle of the overlap on the other axis. With `S`
inside `H` this is the padding around `S`; with `H` inside `S` it is the padding around `H`.

**R3 — Zero length is not drawn.** This covers touching rectangles and edges that coincide. It also means two nodes
with identical bounds, such as a composable and its wrapper layout, produce no lines at all.

Worked examples (render px, rectangles as `left,top – right,bottom`):

| Case | S | H | Lines (from → to, length) |
|---|---|---|---|
| Side by side | `0,0 – 100,50` | `120,10 – 160,60` | `(100,30) → (120,30)`, 20 |
| Stacked | `0,0 – 100,50` | `10,70 – 90,90` | `(50,50) → (50,70)`, 20 |
| Diagonal | `0,0 – 100,50` | `150,80 – 200,120` | `(100,25) → (150,25)`, 50, guide `(150,25) → (150,80)`; `(50,50) → (50,80)`, 30, guide `(50,80) → (150,80)` |
| S inside H | `20,10 – 80,40` | `0,0 – 100,50` | left `(0,25) → (20,25)` 20; right `(80,25) → (100,25)` 20; top `(50,0) → (50,10)` 10; bottom `(50,40) → (50,50)` 10 |
| Partial overlap | `0,0 – 100,50` | `50,25 – 150,75` | `(0,37.5) → (50,37.5)` 50; `(100,37.5) → (150,37.5)` 50; `(75,0) → (75,25)` 25; `(75,50) → (75,75)` 25 |
| Touching | `0,0 – 100,50` | `100,0 – 150,50` | none |
| Same bounds | `0,0 – 100,50` | `0,0 – 100,50` | none |

## Label

- **Value.** `dp = px × 160 / dpi`, using `ZoomMath.contentScale(dpi)` for the conversion.
- **Format.** When the value is within 0.05 of a whole number it prints as an integer (`16dp`); otherwise it gets one
  decimal (`16.4dp`). The number is formatted with `Locale.ROOT`, so the separator is always a dot.
- **Look.** A rounded pill in the measurement color, with white small-font text (`JBUI.Fonts.smallFont()`), centered
  on the line's midpoint. The pill and the line thickness are fixed screen sizes and do not grow with zoom. Lines are
  1 px solid and guides are 1 px dashed.
- **Color.** The measurement color is Figma's red, `#F24822`, as a `JBColor` for both themes.

Known limitation: when the render's density cannot be read, `LiveRenderer` falls back to 160 dpi. The label then
shows pixels labelled as dp. Zoom already relies on the same fallback.

Examples: 44 px at 440 dpi → `16dp`; 45 px at 440 dpi → `16.4dp`; 16 px at 160 dpi → `16dp`.

## What to build

### 1. `ui/MeasurementGeometry.kt` — new, pure

It has no Swing dependency and no Android Studio types, like `PreviewViewHitTester` and `ZoomMath`.

- `fun measure(selected: Rectangle, hovered: Rectangle): List<Measurement>` implements R1–R3.
- `data class Measurement(start, end, lengthPx: Int, guide)`. Points are render-pixel doubles, since midpoints can be
  half pixels. `guide` is a nullable start/end pair.
- `fun formatDp(lengthPx: Int, dpi: Int): String` produces the label text.

### 2. `ui/ZoomableRenderView.kt` — selection, Alt, painting

- **State.** `selected: PreviewViewNode?` is compared by identity, like `hovered` today. The view also keeps `dpi`
  from `setContent` for the label, where today it keeps only `contentScale`.
- **Clicks.** Single click selects per D1 and D5, then calls `requestFocusInWindow()`. Double click calls the
  existing `navigateAt`.
- **Keys and focus.** `isFocusable = true`. A key listener handles `VK_ALT` press and release (D3), a focus listener
  clears the key-event Alt state, and the Esc action follows D4.
- **Painting order.** Image, selection outline, hover outline, then measurements and labels. Every coordinate goes
  through `displayScale`, as the hover outline does today.
- **Test hooks.** `internal` read-only access to the selection and to the measurements currently drawn, following the
  existing `isFitPending` precedent.

Nothing changes in `PreviewRenderPanel`, `PreviewGalleryPanel`, the model or `render/`.

## Tests

Tests are written after the feature works. Each new test must be shown to fail with its production change reverted.

- **`MeasurementGeometryTest`** (plain JUnit): every row of the worked-examples table, `H` inside `S`, and the three
  `formatDp` examples.
- **`ZoomableRenderViewTest`** (existing, platform fixture):
  - A single click selects and does not navigate; a double click navigates. The existing click-to-source test moves
    to a double click.
  - Clicking empty space, the Esc action, and `setContent` each clear the selection.
  - With the hand tool active, a click does not select and nothing is measured.
  - Measurements are empty without Alt and match `MeasurementGeometry.measure` with Alt down.

## Out of scope

- **Keeping the selection across re-renders.** That needs node matching between trees; add it if edit-then-measure
  turns out to be the common loop.
- **Measurement in `@PreviewParameter` and snapshot strips.** That needs per-image view trees in
  `RenderOutcome.MultiSuccess`.
- **Selecting a parent that its children cover completely.** No keyboard "select parent" is provided; a fully
  covered parent has no padding to measure anyway.
- **Multi-selection, and Figma's measure-to-parent without hovering.**

## How the gate confirms it

The user checks this in a fresh `runIde`, with no Gradle running alongside it:

1. Click a component: a 2 px outline stays after the pointer leaves it. Clicking empty space removes it.
2. Double-click a component: the editor opens its source.
3. Select a child, hold Alt, and point at its parent's padding: four lines, each with a dp label matching the
   composable's `padding`.
4. Select one of two siblings in a `Row` with `spacedBy(8.dp)`, hold Alt, and point at the other: one line labelled
   `8dp`.
5. Release Alt: the lines disappear. Press Alt again without moving the mouse: they reappear.
