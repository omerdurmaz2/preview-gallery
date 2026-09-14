# MCP Index Server

**Status:** shipped · **Phases:** PG17, PG18 (partial) · **Last change:** `405fc5a` [PG18-6] 2026-08-07

Exposes the plugin's live preview/snapshot index to AI coding agents over Model Context Protocol, so a tool
like Claude Code can ask which composables lack a snapshot test instead of grepping for it. It ships as a
local, read-only HTTP server the user starts by hand from the gallery toolbar, with five tools covering
discovery, previews, snapshots, a markdown coverage report and a snapshot-health check. It also stands in for
a different approach the roadmap once planned — a PSI snapshot-file writer (F3) — which is deferred in this
server's favor until the agent route proves insufficient.

## What the user gets

- A toolbar button, **"MCP server for agents"**, in the Compose Gallery tool window next to Refresh / the
  module and coverage filters / Coverage report / Verify / Compare
  ([PreviewGalleryPanel.kt:273](../../../src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt)).
  Off by default.
- The button doubles as a status light: the plain `AllIcons.General.Web` icon and tooltip while stopped; the
  platform's own live-process green-dot badge (`ExecutionUtil.withLiveIndicator`) plus
  `"MCP server for agents — running on http://localhost:7891/mcp"` while started — set on both the action's
  `text` and its `description`, because a toolbar tooltip renders the text and may not show the description at
  all (`8e3390b` [PG17-15]).
- Clicking it opens `McpServerDialog`: a status line, a Start/Stop button, and one tab per known client
  (Claude Code, Claude Desktop, Cursor, Codex, Raw URL). Each tab shows a ready-made snippet, a **Copy**
  button, and — except Raw URL — an **Open config** / **Create & open** button that opens that client's real
  config file in the editor, creating it from the snippet if missing and never rewriting one that already
  exists (merging into someone's editor config is not something a plugin should attempt unasked). The snippet
  is copied to the clipboard either way, so the file opens with the paste already waiting.
- Turning it on persists the choice application-wide (`PropertiesComponent`, key
  `com.devomer.previewgallery.mcpServerEnabled`), so the server comes back by itself the next time any project
  opens (`McpServerStartup`); turning it off clears that flag.
- If port 7891 is already bound — typically a second open IDE window already serving it — the toggle stays off
  and a warning dialog names the port instead of failing silently.
- With more than one project open — routine for this plugin's own dev loop, the main IDE on `hepsi-android`
  plus this plugin's own `runIde` sandbox — every tool but `list_projects` needs an explicit `project`
  argument; an agent is expected to get that name/path from `list_projects` first.

## How it works

A request lifecycle: `McpHttpServer` accepts a `POST /mcp`, rejects it with 403 if it carries an `Origin`
header or 405 if it is not a `POST`, then hands the raw body to a pure `handle: (String) -> DispatchResult`
lambda. `McpServerService` wires that lambda to `dispatchLogged`, which calls `McpDispatcher.handle` and logs
(via the project logger, since `mcp/` cannot import `com.intellij`) before rethrowing — `McpHttpServer`'s own
catch turns any throw into an opaque 500 with no message or stack trace in the body, so nothing about a
project's file paths ever leaks to whatever made the request. `McpDispatcher` parses JSON-RPC 2.0: `initialize`
negotiates the one supported protocol version (falling back to it if the client asks for another),
`tools/list` returns the five tool descriptors with their JSON-schema `inputSchema`, `tools/call` routes into
`ToolRegistry`, and a request with no `id` (a notification) short-circuits to `DispatchResult.NoContent` (HTTP
202) without reaching the registry at all.

`ToolRegistry.call` owns the two rules every tool but `list_projects` shares: resolve the optional `project`
argument against `McpServerService.snapshots()` via `ProjectSelector` (name match, then path; anything
ambiguous or, with more than one project open, omitted, is a `Failure` naming the choices rather than a silent
first pick), then refuse if that project's `indexing` flag is true. Every remaining string/boolean argument is
type-checked before it is used as a filter — a `module: 42` or `uncoveredOnly: "true"` is refused by name
rather than silently coerced into "no filter", which would look identical to an empty index and defeat the
indexing guard through a different door.

`McpServerService.snapshots()` is the only place that touches the IDE: one `ReadAction.compute` per open,
non-disposed project, reading `PreviewIndexService.findAll()` / `.findOrphanSnapshots()` and flattening every
`IndexedPreview`/`PreviewEntry` field a tool needs into `PreviewFacts` / `SnapshotFacts` — nothing under `mcp/`
ever sees a `Project`, `VirtualFile` or PSI element. A project whose own read action throws is dropped from the
response (and logged) rather than failing the call for every other open project. `snapshot_health` additionally
needs `blankGoldens(projectName)`, computed separately from `ProjectSnapshot` and only on demand, so an
ordinary `list_previews` call never pays to decode reference PNGs.

### Tools

| Tool | Arguments | Returns |
|---|---|---|
| `list_projects` | none | JSON array, one row per open project (including ones still indexing): `name`, `path`, `indexing`, `previewCount`, `snapshotCount`, `orphanCount`, `uncoveredCount`. The only call that works with nothing resolved and while an index builds. |
| `list_previews` | `project?`, `module?` (exact), `package?` (prefix), `uncoveredOnly?` (default `false`) | JSON array of `@Preview` rows: `composableFqn`, `displayName`, `module`, `package`, `file`, `line` (1-based or `null`), `isPrivate`, `hasPreviewParameter`, `unsupportedReason`, `covered`, `snapshots[]` (FQNs covering it). |
| `list_snapshots` | `project?`, `module?` (exact), `orphansOnly?` (default `false`) | JSON array of `@PreviewTest` rows: `snapshotFqn`, `module`, `file`, `line`, `targets[]`, `orphan`, `referenceImages[]` (`{variant, path}`, absolute paths). |
| `coverage_report` | `project?`, `module?` (exact) | The same markdown `CoverageReport.markdown` writes for the toolbar's export — byte-identical, so a number an agent quotes cannot disagree with one pasted from the IDE. |
| `snapshot_health` | `project?`, `module?` (exact) | JSON object: `blankGoldens[]` (`snapshotFqn`, `module`, `variant`, `path`), `namedAfterSomethingElse[]` (`composableFqn`, `module`, `namedAfter`, `shows[]`), `skippedRows`. The health rules themselves belong to the snapshot health feature (see References); this tool only carries their result over the wire. |

Every optional field is always present and `null` when absent — never omitted — so a consumer needs one rule
(check for `null`) instead of a key-presence check for some fields and a null check for others.

| Class / file | Responsibility |
|---|---|
| [McpDispatcher](../../../src/main/kotlin/com/devomer/previewgallery/mcp/McpDispatcher.kt) | JSON-RPC 2.0 router; pure `String -> DispatchResult`, no I/O |
| [McpHttpServer](../../../src/main/kotlin/com/devomer/previewgallery/mcp/McpHttpServer.kt) | JDK `HttpServer` transport: `POST /mcp`, `GET /health`, loopback bind, `Origin` guard |
| [ProjectSelector](../../../src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSelector.kt) | Resolves the optional `project` argument to one snapshot or a named-choices error |
| [ProjectSnapshot](../../../src/main/kotlin/com/devomer/previewgallery/mcp/ProjectSnapshot.kt) | The IDE-free facts (`ProjectSnapshot`, `PreviewFacts`, `SnapshotFacts`, `ReferenceImage`) every tool reads |
| [ToolRegistry](../../../src/main/kotlin/com/devomer/previewgallery/mcp/ToolRegistry.kt) | Tool descriptors, name → execute dispatch, the shared project-resolution and indexing-guard rules |
| [tools/ListProjectsTool](../../../src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListProjectsTool.kt) | `list_projects` |
| [tools/ListPreviewsTool](../../../src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListPreviewsTool.kt) | `list_previews` |
| [tools/ListSnapshotsTool](../../../src/main/kotlin/com/devomer/previewgallery/mcp/tools/ListSnapshotsTool.kt) | `list_snapshots` |
| [tools/CoverageReportTool](../../../src/main/kotlin/com/devomer/previewgallery/mcp/tools/CoverageReportTool.kt) | `coverage_report`, adapting rows onto the existing `CoverageReport.markdown` |
| [tools/SnapshotHealthTool](../../../src/main/kotlin/com/devomer/previewgallery/mcp/tools/SnapshotHealthTool.kt) | `snapshot_health`, adapting rows onto `SnapshotHealth.check` plus the blank-golden findings |
| [service/McpServerService](../../../src/main/kotlin/com/devomer/previewgallery/service/McpServerService.kt) | Application service: owns the socket, maps `PreviewEntry` → `ProjectSnapshot`, decodes blank goldens on demand |
| [service/McpServerStartup](../../../src/main/kotlin/com/devomer/previewgallery/service/McpServerStartup.kt) | Restarts the server on IDE start if the user left it on |
| [ui/McpServerAction](../../../src/main/kotlin/com/devomer/previewgallery/ui/McpServerAction.kt) | Toolbar toggle; carries the running state in its own icon/text/tooltip |
| [ui/McpServerDialog](../../../src/main/kotlin/com/devomer/previewgallery/ui/McpServerDialog.kt) | Status, Start/Stop, per-client config tabs |
| [ui/McpClientConfig](../../../src/main/kotlin/com/devomer/previewgallery/ui/McpClientConfig.kt) | Pure: each client's config-file path and snippet text |

## Key decisions

- **JDK `HttpServer`, not Ktor/Netty** — two endpoints do not justify a second Netty class-loader tree inside
  the IDE — spec D1, `dfcbc96` [PG17-0].
- **One protocol version advertised, `2025-06-18`** — the two older MCP versions are batching-era and the
  dispatcher takes no top-level array — spec D1a.
- **The protocol layer is a pure function**, `McpDispatcher.handle(String): DispatchResult`, with no I/O and no
  `com.intellij` import — every JSON-RPC behaviour becomes a plain-JUnit assertion with no IDE fixture — spec
  D2.
- **No new Gradle module; the `mcp/` package itself is the test boundary** — "does this file import
  `com.intellij`" is enforced by review, the same way `search/` and `model/` already were — spec D3.
- **One application-level server, fixed port 7891, an optional `project` argument on every tool** — this
  workflow runs two IDEs at once (the main Studio window and this plugin's own `runIde` sandbox); a
  project-level server would make the second fight for the port — spec D4, `794a813` [PG17-7].
- **Off by default, toggled from the toolbar, remembered application-wide** — opening a socket should not
  happen just because a tool window was installed, and the toggle is also where a bind failure surfaces,
  instead of a silent line in `idea.log` — spec D5.
- **Five tools, not a generic query API** — `reference_images` was folded into `list_snapshots` (one row
  carrying its own PNG paths beats N calls) and `coverage_report` reuses `CoverageReport.markdown` verbatim
  rather than growing a second, driftable JSON coverage shape; `snapshot_health` was added once F7 shipped —
  spec D6/D8, `c5f96a4` [PG18-6].
- **Reference PNGs are absolute paths, never bytes** — every MCP client already has its own file-reading tool;
  base64 in a JSON-RPC response would spend a context window doing that worse — spec D7.
- **A request carrying `Origin` is refused with 403, on both endpoints** — MCP clients never set it, browsers
  always do, so this is the entire access-control story for a loopback socket; `/health` was missed in the
  first pass and picked up in the PG17-10 review wave — spec D9, `d98b4c5` [PG17-10].
- **The indexing refusal is a tool result (`isError: true`), never a JSON-RPC error** — several MCP clients
  reject a call outright on a protocol error, which would keep the "wait, don't conclude the project is
  empty" message from ever reaching the model. Decided the other way at first and reversed in `eb797c0` /
  `c99ed6d` [PG17-5] — spec D10.
- **No snapshot-shaped cache above `PreviewIndexService`'s own** — a `McpServerService`-level cache keyed only
  on `PsiModificationTracker.MODIFICATION_COUNT` shipped in the same review wave (`d98b4c5` [PG17-10], item 2)
  and was reverted one commit later (`404b467` [PG17-11]) once it reproduced, through a cache window instead of
  dumb mode, the exact stale-index false negative D10 exists to prevent — spec D11.
- **`kotlinx-serialization-json`'s runtime API only, from the platform's own bundled jar** —
  `buildJsonObject`/`parseToJsonElement`, no `@Serializable`, no added Gradle dependency, no compiler plugin —
  confirmed by a compile probe before the first tool was written — spec D12.
- **The toolbar action also owns a client-config dialog** — "is it running" and "how do I point Claude at it"
  are the same question the first time anyone asks. Later reworked to open/create the client's real config
  file (never overwriting one that exists) and to drop the `npx mcp-remote` proxy wrapper for the plain `url`
  form, since this is already Streamable HTTP — spec D13, `3e9893b` [PG17-13].
- **F3 (a PSI snapshot-file writer) is deferred to this agent route, not cancelled** — an agent holding the
  index reads the real preview body, the module's existing fake-state factory and the project's own skill,
  none of which a template can do; it only comes back if that route needs the same manual correction every
  time — roadmap Theme 2 F3, `5e5681d` [PG17-16].

## Android Studio and platform internals relied on

- `@Service(Service.Level.APP)` on `McpServerService` is a *light service*: registration is the annotation
  itself, no `<applicationService>` entry in `plugin.xml` (verified — only the `postStartupActivity` for
  `McpServerStartup` is registered there). `McpServerService` also implements `Disposable`; the platform calls
  `dispose()` automatically, which is where the socket gets a last `stop()`.
- `ProjectActivity` / `<postStartupActivity>` restores the server on IDE start because there is no
  application-level "started" extension point; the first project to open runs it, and starting an
  already-running server is a no-op (`StartResult.AlreadyRunning`).
- `DumbService.isDumb(project)` is the indexing gate every tool but `list_projects` respects (D10).
- `ReadAction.compute<T, RuntimeException>` runs on the HTTP handler's own executor thread, never the EDT.
  `ProcessCanceledException` and `CancellationException` are rethrown ahead of any logging catch in both
  `McpServerService.snapshotOrNull` and `dispatchLogged` — platform rule: these are control flow, not failures
  worth a stack trace.
- `FileDocumentManager.getCachedDocument` is preferred over `.getDocument` so resolving one preview's line does
  not load and decode a file just to answer that lookup; the fallback reads raw text once per **call** (not per
  row) via `LoadTextUtil.loadText`, which normalizes line separators the same way a PSI offset is computed —
  `VfsUtilCore.loadText` (raw bytes) under-counted lines on CRLF files with no open editor (`73d08f5`
  [PG17-12]).
- `com.sun.net.httpserver.HttpServer` is a JDK class, not a platform one — chosen so `mcp/` never needs
  `com.intellij` on its classpath — bound explicitly to `InetAddress.getLoopbackAddress()`.
- `ExecutionUtil.withLiveIndicator(AllIcons.General.Web)` produces the toolbar button's "running" badge, the
  same green dot a live run configuration shows. Built `by lazy` because composing an `Icon` at class-init time
  runs before the icon subsystem is up under a headless test.
- `ActionUpdateThread.BGT` on `McpServerAction` — `update()` only reads one `@Volatile` field and touches no
  PSI, so it need not run on the EDT.
- `HttpURLConnection` cannot set an `Origin` request header at all (the JDK treats it as restricted); tests use
  `java.net.http.HttpClient` instead to actually exercise the guard — worth knowing before "simplifying" that
  test back to `HttpURLConnection`.
- `McpHttpServer.stop()` waits up to 500 ms (`SHUTDOWN_GRACE_MS`) for its thread pool to drain before
  `shutdownNow()`; `McpServerDialog` calls `stop()` straight from the EDT, so that grace period is time the UI
  thread can block.

## Tests

- [ProjectSelectorTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/ProjectSelectorTest.kt) —
  project-argument resolution: none/one/many open, a name match, a path fallback, a name collision
  disambiguated by path, an unknown name, zero open projects.
- [McpDispatcherTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/McpDispatcherTest.kt) — JSON-RPC
  framing as a pure function: version negotiation and fallback, all five tools listed, a notification yields
  `NoContent`, a malformed body is `-32700`, an unknown method is `-32601`, an unknown tool is `-32602` even
  with nothing open, a tool failure comes back as an `isError` result rather than a protocol error, `ping`.
- [McpHttpServerTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/McpHttpServerTest.kt) — the real
  socket on an ephemeral port: `/health` answers, a `POST` round-trips, a notification is 202 with an empty
  body, `Origin` gets 403 on both `/mcp` and `/health`, `stop()` releases the port, `stop()` then `start()`
  rebinds the same instance, an unhandled throw yields 500 with no exception detail in the body. **Flaky — see
  Open items.**
- [ToolRegistryTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/ToolRegistryTest.kt) — dispatch
  and the two rules every tool shares: all five tools advertise a schema, `list_projects` answers with nothing
  open and while indexing, every other tool refuses while indexing by name, an ambiguous project is a failure
  not a guess, the `project` argument selects, an unknown tool name is `UnknownTool` not `Failure`, a
  wrong-typed string/boolean argument is refused by name, a blank string is treated as absent.
- [ToolsTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/ToolsTest.kt) — the four data tools'
  wire shape against hand-built `PreviewFacts`/`SnapshotFacts`: `list_projects`'s five counts,
  `list_previews`'s module/package/`uncoveredOnly` filters and null-vs-value `line`/`unsupportedReason`,
  `list_snapshots`'s `orphansOnly` and reference-image paths, `coverage_report` byte-matching
  `CoverageReport.markdown`.
- [SnapshotHealthToolTest](../../../src/test/kotlin/com/devomer/previewgallery/mcp/SnapshotHealthToolTest.kt) —
  `snapshot_health`'s JSON shape only (the name-mismatch and blank-golden rules themselves are pinned by the
  snapshot health feature's own tests): a name finding carries both the claimed and the actual component, a
  blank finding carries its PNG path, the module filter applies to both arrays, `skippedRows` reaches the
  agent.
- [McpServerServiceTest](../../../src/test/kotlin/com/devomer/previewgallery/service/McpServerServiceTest.kt) —
  the one seam that needs a real project: `PreviewEntry` → `ProjectSnapshot`/`PreviewFacts`/`SnapshotFacts`
  mapping, an empty project, a reference PNG found at its real nested path (PG17-10 regression), a resolved
  line with no editor open (PG17-11 regression) and on a CRLF file (PG17-12 regression), and a snapshot
  covering two previews reporting its blank golden once, not twice (PG18-9 regression).
- [McpServerActionTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/McpServerActionTest.kt) — the
  toolbar button's state: stopped keeps the plain icon, `update()` sets both `text` and `description`, the
  running tooltip embeds the literal port un-grouped (`7891`, not `7,891`).
- [McpClientConfigsTest](../../../src/test/kotlin/com/devomer/previewgallery/ui/McpClientConfigsTest.kt) — the
  per-client paths and snippets are pure and pinned: every snippet carries the running port, each client's real
  config path (including Claude Desktop's three OS branches), Codex is TOML while the rest are `mcpServers`
  JSON, Claude Code's snippet declares `"type": "http"`, no snippet wraps `mcp-remote`, Raw URL has no config
  file.

**Notable test gaps:**
- No test drives `McpServerDialog` itself — no `McpServerDialogTest` exists (verified: none under
  `src/test/`). The Start/Stop button, the port-in-use warning, and the open/create-config file-system
  behaviour are only exercised by the manual gate.
- The toolbar icon's actual green "running" badge is explicitly left to the manual gate:
  `ExecutionUtil.withLiveIndicator` needs the icon subsystem a headless test does not start, so
  `McpServerActionTest` can only assert the un-badged fallback icon (its own class KDoc says so).

## Open items

- [bug] `McpHttpServerTest` is flaky: on 2026-09-14, repeated local runs each failed a different 1–4 of its 8
  tests with `java.net.BindException: Address already in use` at `McpHttpServerTest.kt:26`
  (`McpHttpServer(port, handle).also { it.start() }`), and the failures predate PG25. Suspected cause:
  `freePort()` (bind an ephemeral port, close it, hand the number to the next `McpHttpServer`) racing that next
  server's own bind for the same loopback port, possibly worse under JBR 25. A fix task was proposed but not
  started. — source: local observation, 2026-09-14.
- [gap] No `McpServerDialog` test exists — Start/Stop, the port-in-use warning and the config-file create/open
  behaviour are unverified except by hand. — verified in code (no `McpServerDialogTest` under `src/test/`).
- [limitation] The port is fixed at 7891 with no settings UI; a collision with anything other than a second IDE
  has no workaround besides closing the other process. — spec "Open questions", unresolved as of PG18.
- [limitation] `list_snapshots` always walks the reference roots for every row. The spec's fallback if that
  ever costs more than the call is worth on a large project is a `withReferenceImages` flag — not needed yet,
  not built. — spec "Open questions".
- [limitation] Read-only and loopback-only by design, not a gap: no tool creates, edits or runs anything, and
  there is no remote access, authentication scheme or TLS. — roadmap "Scope guard for F8"; spec Non-Goals.
- [idea] F3 ("Create snapshot test", a PSI-writer action) is deferred, not cancelled, in favor of this MCP
  route. It comes back only if writing snapshots through an agent needs the same manual correction every time —
  unmeasured as of this writing. F4 (promote a comparison view to a snapshot variant) waits on F3's writer. —
  roadmap Theme 2 F3/F4, `5e5681d` [PG17-16].
- [idea] The same deferral is documented from the user's side: generating snapshot tests is deliberately left
  to an assistant over MCP rather than a plugin template. — [README.md](../../../README.md) "Known
  limitations".

## History

| Phase | Dates | Commits | What changed |
|---|---|---|---|
| PG17 | 2026-08-07 | `dfcbc96` … `5e5681d` (29 commits) | Designed and shipped the whole MCP surface: the pure `mcp/` package (snapshot model, project selector, four tools, registry, JSON-RPC dispatcher, JDK HTTP transport with the `Origin` guard), the `McpServerService` application service and startup activity, and the toolbar action plus client-config dialog. Review waves fixed a thread/executor leak on a failed bind (PG17-6), a wrong-typed-argument coercion bug and the indexing-refusal-as-protocol-error bug (PG17-4/5), a `referenceImages: []` bug from a flat directory scan that never matched the real nested layout (PG17-10), a stale-during-indexing cache that was tried and reverted a commit later (PG17-10/11), and a CRLF line-counting drift (PG17-12). The manual gate ran this server against `hepsi-android` (880 previews, 50 snapshots, 24 orphans, 854 uncovered across 92 modules). Ends by deferring roadmap item F3 to this agent route (PG17-16). |
| PG18 (partial) | 2026-08-07 | `c5f96a4`, `405fc5a` | Added `snapshot_health` as the registry's fifth tool once F7 (snapshot health checks) shipped, and widened the dispatcher/registry tests for the five-tool surface. The health rules themselves (blank-golden decode, name-vs-target mismatch) live in the snapshot health feature — see References. A same-day follow-up outside this doc's owned commits (`3ff1411` [PG18-9]) deduped `McpServerService.blankGoldens` so a snapshot covering two previews is reported once, not twice — covered here because the fix and its regression test both land in files this doc owns. |

## References

- Spec: [2026-08-07-mcp-index-server-design.md](../../superpowers/specs/2026-08-07-mcp-index-server-design.md)
- Plan: [2026-08-07-mcp-index-server.md](../../superpowers/plans/2026-08-07-mcp-index-server.md)
- Snapshot health spec (the `snapshot_health` payload's underlying rules):
  [2026-08-07-snapshot-health-design.md](../../superpowers/specs/2026-08-07-snapshot-health-design.md)
- Roadmap: [snapshot-testing-roadmap.md](../../snapshot-testing-roadmap.md) — Theme 4 F8 (shipped), Theme 2
  F3/F4 (deferred/blocked), "Scope guard for F8"
- [README.md](../../../README.md) — "Hand the same data to an AI assistant", "Known limitations"
- [CHANGELOG.md](../../../CHANGELOG.md) — Unreleased "Added"
- [feature-overview.md](../../feature-overview.md) — "4 · The same data, available to an AI agent"
