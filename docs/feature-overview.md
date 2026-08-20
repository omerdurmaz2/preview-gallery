# Compose Preview Gallery — what it does

An Android Studio plugin that turns every `@Preview` in a project into a browsable, searchable catalogue —
and connects that catalogue to the team's screenshot tests.

---

## The problem it solves

Android Studio shows previews **only for the file you currently have open**. In a codebase with hundreds of
components spread over dozens of modules, that leads to three everyday costs:

1. **Nobody can see what already exists.** "Do we have a component for this?" can only be answered by someone
   who already knows the file path. So components get rebuilt instead of reused.
2. **Nobody can see what is covered by screenshot tests.** The team rule is "a UI change means a snapshot
   change", but nothing in the IDE shows which components actually have a snapshot and which do not.
3. **Checking a snapshot costs a full Gradle run.** Every "did I break a screenshot?" question is minutes of
   waiting, so people stop asking it.

---

## 1 · A catalogue of every preview in the project

<!-- SCREENSHOT: the preview screen — the tree on the left, a rendered component on the right -->
![Preview gallery](images/preview-screen.png)

A panel on the right side of the IDE lists **every** `@Preview` in the project, grouped by module and package.
Pick one and it renders on the spot — the same renderer Android Studio uses for its own preview, so what you
see is what the app draws.

What you can do with it:

- **Search** by component name, function name or package, across the whole project.
- **Filter** to just the module you are working in, or to just the components that have no screenshot test yet.
- **Zoom and pan** the render — mouse wheel, trackpad pinch, or the toolbar buttons.
- **Click any part of the rendered component** to jump straight to the code that draws it.
- **Export** a preview as a PNG, or copy it to the clipboard — useful for a ticket, a design review or a chat
  message.
- **Find previews from Search Everywhere** (`Shift Shift`), alongside classes and files.
- **Jump in from the code**: an icon in the editor gutter, next to every `@Preview` function, opens the gallery
  on that component.

The list builds itself from the code and stays up to date as the code changes — there is nothing to register
and no list to maintain.

---

## 2 · Try a component under different conditions, side by side

<!-- SCREENSHOT: the preview screen with the properties/config dialog open -->
![Preview configuration](images/preview-config.png)

Each preview can be opened with Android Studio's own property editor, so you can change the device, the screen
size, the API level or the system-UI chrome and see the component redraw immediately.

You can also **add comparison views**: several copies of the same component side by side, each with its own
settings. That answers "how does this look on a small phone versus a tablet?" in one screen instead of five
edit-run cycles.

Components declared with `@PreviewParameter` — one component, many sample inputs — render **every** sample
value in one stacked view, so a full set of states is one click rather than one per state.

---

## 3 · Screenshot-test coverage and results, in the same place

<!-- SCREENSHOT: a snapshot row showing golden / rendered / diff for a failing snapshot -->
![Snapshot diff](images/snapshot-diff.png)

The plugin reads the project's existing Compose Screenshot Testing setup — the same one CI would use — and
shows it next to the components it belongs to.

- **Coverage at a glance.** Every component in the tree says whether a screenshot test covers it. A filter
  shows only the ones that do not. In one real project this surfaced **854 components with no screenshot
  test**, a number nobody had before.
- **The committed reference images.** Select a component and see the images the test suite is checking
  against, without leaving the IDE or opening a file browser.
- **Run the check and see the result.** One button runs the project's own verification task and shows, per
  snapshot, the **reference image, what was rendered now, and the difference between them** — plus the exact
  percentage of pixels that differ. A failing component is also marked in the tree, so it is visible from the
  list.
- **Stale results are labelled as stale.** If the code changed after the last run, the plugin says so instead
  of showing an old green result as if it were current.
- **A health check** flags snapshot tests whose names no longer match any real component — the tests that
  quietly stopped testing anything.
- **A coverage report** in Markdown, ready to paste into a ticket or a channel, counting every module
  including the ones that never adopted screenshot testing at all.

---

## 4 · The same data, available to an AI agent

<!-- SCREENSHOT: the MCP server dialog -->
![MCP server](images/mcp-server.png)

The plugin can expose its catalogue to an AI coding assistant over a local connection (MCP). The assistant can
then ask, in its own words:

- *which components exist, in which module, in which package*
- *which ones have no screenshot test*
- *what the current coverage numbers are*
- *which screenshot tests look unhealthy*

This is what makes "write the missing screenshot tests for this module" a request an agent can actually carry
out: it can see the same list the developer sees, rather than guessing from file names.

The connection is **read-only** — the assistant can look, not change anything — and it is off until someone
turns it on.

---

## What this saves

| Question | Before | With the plugin |
|---|---|---|
| "Do we already have this component?" | Ask around, grep, hope | Search the catalogue |
| "Which components have no screenshot test?" | Nobody knows | A filter, and a number |
| "Did I break a screenshot?" | Full Gradle run, minutes | One button, results in place |
| "What does this look like on a tablet?" | Edit, rebuild, look, repeat | Change a setting, look |
| "Show me the failing snapshot" | Open the HTML report, find the file | Reference, current and diff, side by side |

---

## Status, honestly

**Working and used daily:** the catalogue, search and filters, rendering, zoom and export, click-to-source,
coverage badges, reference images, running the verification and showing its results, the health check, the
coverage report, and the agent connection.

**Depends on the project's own code:** a component that cannot render on its own — for example one that needs a
theme wrapper the preview does not provide — will show an error here for the same reason it shows one in
Android Studio's own preview. The plugin reports what the renderer said; it cannot fix the component.

**Not there yet:** comparing a live render against a committed reference image *without* running Gradle. The
groundwork is in place and it is the last open item in this area, but the comparison is not yet trustworthy
enough to put a number in front of anyone, so it is not presented as a feature.

**Not built:** generating the missing screenshot tests from inside the plugin. That is deliberately left to an
AI assistant through the connection above, which can read the real component and the project's own conventions
instead of filling in a template.
