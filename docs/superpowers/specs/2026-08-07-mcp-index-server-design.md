# MCP Index Server — Design

**Feature:** F8 · Serve the preview and snapshot index over MCP
**Roadmap:** `docs/snapshot-testing-roadmap.md` — Theme 4, priority 1
**Commit prefix:** `PG17-N`

## Goal

Let an agent ask the plugin what the project contains, instead of re-deriving it by grepping.

The gallery already knows, per composable: its module and package, whether a snapshot covers it, which snapshot
functions cover it, which reference PNGs are committed, and which snapshots match no preview at all. Matching a
preview to its snapshot took a call-site heuristic and three phases to get right (PG13–PG15). An agent asked to
write a missing snapshot cannot cheaply reconstruct any of it.

Expose that read-only over MCP, so the agents already in this toolchain — Claude Code, Codex, Cursor, and the
IDE's own assistant — can answer "which composables have no snapshot?" in one call.

## Non-Goals

- **Writing anything.** No tool creates a file, edits one, or runs a Gradle task. The roadmap's scope guard
  decided this: the gallery describes what the repository contains; an agent that wants to write does the
  writing with its own tools, where a human can see the diff.
- **Serving image bytes.** Reference PNGs are returned as absolute paths.
- **Remote access.** Loopback only, no authentication scheme, no TLS. A socket reachable from another machine
  is a different product with a different threat model.
- **Rendering.** The render pipeline stays behind the IDE UI. No tool triggers a render.
- **Replacing the tool window.** This is a second reader of the same index, not a migration.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Transport is **JDK `com.sun.net.httpserver.HttpServer`**, bound to `127.0.0.1`, serving `POST /mcp` and `GET /health`. | The surface is two endpoints and a request body. Ktor + Netty — what `DepHealth` uses for the same job — is four artifacts and a second Netty class-loader tree inside the IDE to buy a routing DSL this does not need. The JDK server is ~30 lines and ships with the JVM. |
| D1a | Exactly one MCP protocol version is advertised: `2025-06-18`. | The two older versions are batching-era and this server takes no top-level JSON array. Advertising a version whose requests it would reject promises something it does not do, and nothing in the dispatcher behaves differently per version. |
| D2 | The protocol layer is **pure**: `McpDispatcher.handle(String): DispatchResult`, no I/O and no IntelliJ types. | Copied from `DepHealth`'s proven shape. Every protocol behaviour — `initialize`, `tools/list`, a malformed body, an unknown method — becomes a `String → DispatchResult` assertion with no IDE fixture. |
| D3 | **No new Gradle module.** The pure classes live in a `mcp/` package alongside the existing pure packages (`search/`, `model/`). | `DepHealth` gets test isolation from a module boundary; this project already gets it from a package boundary — `SnapshotCoverageResolverTest` and `CoverageReportTest` are plain JUnit in the same module today. A module would add build wiring for a boundary that is already enforced by "does this file import `com.intellij`". |
| D4 | **One application-level server** on fixed port **7891**, with an optional `project` argument on every tool and a `list_projects` tool to discover the names. | Two IDEs run at once in this workflow: the main Android Studio on `hepsi-android` and the `runIde` sandbox on this plugin. A project-level server would make the second one fight for the port. `DepHealth` holds 7890, so this takes the next one. |
| D5 | The server is **off by default** and started by hand from a toolbar action, which remembers the choice application-wide. | Opening a port is not something a tool window should do because it was installed. The action is also where the failure surfaces: a port already in use is a notification, not a line in `idea.log`. |
| D6 | **Four tools**: `list_projects`, `list_previews`, `list_snapshots`, `coverage_report`. | This is the whole payload the roadmap asked for, minus the split between "list snapshots" and "list their reference images" — one row carrying its PNG paths is one call instead of N. |
| D7 | Reference PNGs are returned as **absolute paths**, never bytes. | Every consumer here has its own file-reading tool. Base64 in a JSON-RPC response would make a 200 KB screenshot a 270 KB string in a context window, to do worse what `Read` already does. |
| D8 | `coverage_report` returns the **existing `CoverageReport.markdown` output**, verbatim. | The format is already pinned by tests and is what the human-facing export writes. A second, JSON-shaped coverage format would drift from it; `list_previews(uncoveredOnly = true)` already covers the machine-readable need. |
| D9 | A request carrying an **`Origin` header is rejected** with 403. | Without this, any web page the user has open can POST to `http://localhost:7891/mcp` — the browser will happily send it, and a read-only server still leaks the project's file paths and structure. MCP clients do not set `Origin`; browsers always do. Three lines, and it closes the only remote-ish hole a loopback socket has. |
| D10 | While the index is building, `list_projects` reports `"indexing": true` for that project and every other tool refuses, returning its message as an `isError` tool result. | `PreviewIndexService` returns an empty list in dumb mode. An agent handed `[]` concludes "this project has no previews" and acts on it; a refusal makes it wait or ask. The refusal is a **result**, not a JSON-RPC error: MCP reserves protocol errors for unroutable calls, and several clients reject the call outright on one — the message would never reach the model, which is the whole point of refusing. |
| D11 | Index reads happen inside `ReadAction.compute`, on the HTTP handler's own thread, and the resulting `ProjectSnapshot` is itself cached per project via `CachedValuesManager`, keyed on `PsiModificationTracker.MODIFICATION_COUNT`. | The handler thread is not the EDT and holds no read lock, so touching PSI without one is an outright violation. The snapshot-level cache is what makes the read action actually cheap: resolving every preview's and every snapshot's line number loads a `Document` per row, and doing that for ~1462 previews inside one read action on every tool call froze the EDT (PG17-10 item 2). Caching means only the first call after a PSI change pays that cost; every call in between is a lookup, cheap enough to take inline rather than pushing to a pooled thread and blocking on it. |
| D12 | JSON is built with the platform's bundled **kotlinx-serialization-json runtime API** (`Json.parseToJsonElement`, `buildJsonObject`), with no `@Serializable` classes and no Kotlin serialization compiler plugin. | Verified present at `Android Studio.app/Contents/lib/module-intellij.libraries.kotlinx.serialization.json.jar`. The runtime API needs no compiler plugin, so this adds neither a Gradle plugin nor a declared dependency — the project still has exactly one, JUnit. |
| D13 | The toolbar action also opens a dialog with ready-made client configuration snippets. | Four clients, four config shapes, one URL that nobody remembers. `DepHealth` ships the same dialog and it is the difference between "the server is running" and "the agent can reach it". |

## Architecture

```
mcp/                            (pure — no com.intellij imports, plain JUnit)
  McpHttpServer.kt        NEW · JDK HttpServer: POST /mcp, GET /health, loopback bind, Origin guard
  McpDispatcher.kt        NEW · JSON-RPC 2.0 router: initialize / ping / tools/list / tools/call
  ToolRegistry.kt         NEW · tool descriptors + name → execute dispatch
  ProjectSnapshot.kt      NEW · the IDE-free data one open project contributes
  tools/                  NEW · one object per tool, snapshot in → JSON out

service/
  McpServerService.kt     NEW · application service: owns the server, collects ProjectSnapshots

ui/
  McpServerAction.kt      NEW · toolbar toggle + the config dialog
```

Data flow, one call:

```
agent → POST /mcp → McpHttpServer → McpDispatcher → ToolRegistry → provider lambda
                                                                        ↓
                                              McpServerService.snapshots()
                                                                        ↓
                                        ReadAction { PreviewIndexService.findAll()
                                                     + findOrphanSnapshots() }
                                                                        ↓
                                              List<ProjectSnapshot> → JSON
```

`ProjectSnapshot` is the seam. `McpServerService` maps `PreviewEntry` (which carries a `VirtualFile` and only
means anything inside the IDE) into it; everything under `mcp/` sees only `ProjectSnapshot` and can therefore be
tested by constructing one. It holds, per project: name, base path, an `indexing` flag, the preview rows, and
the orphan snapshot rows — each row already flattened to strings and paths.

## Tool surface

Every tool takes an optional `project`, matched against the project's **name first and its base path
second**, so an agent can pass either whichever `list_projects` gave it. With one project open the argument may
be omitted; with more than one open, omitting it is an error listing the names — silently picking the first
would make an agent's answer depend on window order. A name that matches two open projects is the same error:
the path disambiguates it.

### `list_projects`

No arguments. The discovery call, and the one an agent makes first.

```json
[{ "name": "hepsi-android", "path": "/Users/…/hepsi-android",
   "indexing": false, "previewCount": 1462, "snapshotCount": 33,
   "orphanCount": 2, "uncoveredCount": 1436 }]
```

`uncoveredCount` is here so the discovery call already answers "is there work?" — otherwise every agent's second
call is `list_previews(uncoveredOnly = true)` just to find out.

### `list_previews`

`project?`, `module?`, `package?` (prefix match), `uncoveredOnly?` (default `false`).

```json
[{ "composableFqn": "com.example.FavoritesScreenKt.FavoritesPreview",
   "displayName": "Favorites – dark", "module": "hepsi-android.features.favorites.ui.main",
   "file": "/Users/…/FavoritesScreen.kt", "line": 142,
   "isPrivate": false, "hasPreviewParameter": false, "unsupportedReason": null,
   "covered": true, "snapshots": ["com.example.FavoritesSnapshotsKt.Favorites_Dark_Snapshot"] }]
```

`unsupportedReason` is carried through rather than dropped: a preview declared inside a class is not a snapshot
candidate, and an agent that writes a test for one has written a test that cannot run.

`line` is 1-based. The index stores a character `offset`, not a line, because a line number invalidates on every
edit above it; the conversion happens in `McpServerService` inside the same read action that collects the rows,
where the `Document` is available. A file whose document cannot be loaded reports `line: null` rather than
failing the call.

### `list_snapshots`

`project?`, `module?`, `orphansOnly?` (default `false`).

```json
[{ "snapshotFqn": "com.example.FavoritesSnapshotsKt.Favorites_Dark_Snapshot",
   "module": "hepsi-android.features.favorites.ui.main",
   "file": "/Users/…/FavoritesSnapshots.kt", "line": 61,
   "targets": ["FavoritesScreen"], "orphan": false,
   "referenceImages": [{ "variant": "Debug", "path": "/Users/…/screenshotTest/reference/…png" }] }]
```

`variant` is `ReferenceRoots.Root.buildVariant` (e.g. `"Debug"`, the same string that composes into
`update${variant}ScreenshotTest`), which is `null` for a module whose reference directory is not under a build
variant — the field is then emitted as JSON `null`, never omitted and never an empty string. One rule for every
optional field on this wire contract: always present, `null` when absent, so a consumer never needs a
key-presence check for one field and a null check for the rest. `referenceImages` is `[]` for a snapshot with no
committed PNG, which is a real state (the test exists, `update…ScreenshotTest` has not run) and not an error.

### `coverage_report`

`project?`, `module?`. Returns `CoverageReport.markdown` as text — the same document the toolbar's export
writes, so a number quoted from an agent and a number pasted from the IDE cannot disagree.

## Lifecycle, port and configuration

The server is an application service, so it outlives any one project and is shared by all of them. It starts
only when the user turns it on, and the choice is stored application-wide in `PropertiesComponent` — this is
deliberately *not* the per-project `PersistentToggleAction` the two filters share, because the thing being
remembered is not per project and toggling it can fail.

Failure modes are surfaced, not swallowed:

| Situation | Behaviour |
|---|---|
| Port 7891 already bound | The toggle stays off and a balloon says so, naming the port. Most likely cause is a second IDE that already has it — which is exactly the case D4 exists to make visible. |
| Server on, no project open | `list_projects` returns `[]`. Not an error: the server belongs to the application. |
| Project closed while a call is in flight | The snapshot was already taken; the response is the last consistent view. The next call omits the project. |
| Index still building | D10: `indexing: true`, and every other tool returns an `isError` result naming the project. |
| No project matches, or the argument is ambiguous | The same shape: an `isError` result listing the open projects. The tool ran; it could not answer. |
| A tool name that does not exist | JSON-RPC `-32602`. Unroutable, so a protocol error rather than a tool result. |
| Malformed body | JSON-RPC `-32700`, per spec. |
| Request carries `Origin` | HTTP 403, no JSON-RPC body. D9. |

The config dialog shows one snippet per client, each with a copy button:

- **Claude Code / Codex** — `npx -y mcp-remote http://localhost:7891/mcp`
- **Cursor** — the same, in its `mcpServers` shape
- **Raw URL** — for anything that speaks Streamable HTTP directly

## Testing

Plain JUnit 4, everything under `mcp/`:

- `McpDispatcherTest` — `initialize` echoes the supported protocol version and falls back for an unsupported
  one; `tools/list` names all four tools; a notification (no `id`) yields `NoContent`; malformed JSON is
  `-32700`; an unknown method is `-32601`; an unknown tool name is `-32602`; a tool that ran and could not
  answer comes back as an `isError` result carrying its message.
- `ToolRegistryTest` — each tool against a hand-built `ProjectSnapshot`: filters compose (`module` +
  `uncoveredOnly`), `orphansOnly` selects exactly the orphan rows, a missing `project` with two snapshots is an
  error naming both, a missing `project` with one snapshot resolves, and a present-but-wrong-typed argument is
  refused by name rather than treated as absent.
- `ToolsTest` — each tool object's JSON/markdown shape against hand-built `PreviewFacts`/`SnapshotFacts`,
  including that `coverage_report`'s output is byte-identical to `CoverageReport.markdown` for the same rows.
- `McpHttpServerTest` — binds a real ephemeral port: `GET /health` answers, `POST /mcp` round-trips an
  `initialize`, a request with `Origin` gets 403 on both endpoints, `stop()` releases the port and a subsequent
  `start()` rebinds it, an unhandled throw from the handler yields 500 with no detail in the body.

`BasePlatformTestCase`:

- `McpServerServiceTest` — a fixture project maps into a `ProjectSnapshot` with the previews and orphans the
  index holds, and `indexing` is true in dumb mode.

## Risks

| Risk | Mitigation |
|---|---|
| The platform's bundled kotlinx-serialization-json is not on the compile classpath, or its ABI shifts with a platform update. | The first task compiles a single `buildJsonObject` call and stops if it does not resolve. If it does not, the fallback is a ~40-line JSON writer plus the platform's own `JsonReaderEx` — decided then, not designed for now. |
| An agent treats an empty `list_previews` as ground truth during indexing. | D10 makes that state an error rather than an empty list. |
| The port choice collides with something else on the machine. | It is visible: the toggle reports the bind failure. A configurable port is a settings page nobody has asked for yet. |
| A read action on the handler thread blocks behind a write action in the IDE. | The read is a cached-value lookup, and the caller is an agent that already waits seconds for a tool call. If it ever shows up as a real stall, the fix is a pooled thread with a timeout, not a lock-free read of PSI. |
| Scope creep into writing. | The non-goal is a scope guard in the roadmap, not a preference. A tool that writes belongs behind the IDE UI. |

## Open questions

None blocking. Two decided-by-default and worth revisiting only if they bite:

- **Port configurability** — fixed at 7891 until someone hits a collision that is not another IDE.
- **`list_snapshots` reference paths** — always included. If walking the reference roots turns out to cost more
  than the call is worth on a large project, it becomes a `withReferenceImages` flag.
