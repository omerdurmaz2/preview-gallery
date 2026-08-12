# Snapshot Verify Hardening (H2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close what PG20's gate left open — two silent paths in the verify trigger, one unbounded EDT filesystem
walk in the tree's paint callback, and two gate checks that were never observed.

**Architecture:** three small, independent changes inside the already-shipped verify feature. (1) `startVerify`
learns that an explicit Verify already armed on the alarm outranks the automatic path, so a selection change inside
the debounce can no longer drop it. (2) `runVerify` learns whether the run it is about to skip was asked for by a
human, and answers with a message that keeps the actionable half of the pane it replaces. (3) `ModuleFreshness`
gains a stale-while-revalidate reader for the source clock, so the tree's per-row paint callback serves the last
known value and refreshes behind it instead of walking a module's whole source tree on the EDT.

**Tech Stack:** Kotlin, IntelliJ Platform (Alarm, `AppExecutorUtil`, `ColoredTreeCellRenderer`, `DumbService`),
JUnit via `BasePlatformTestCase`.

## Global Constraints

- **Commit prefix `PG21-N`**, message form `[PG21-N] - Task name`, with the trailer
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **No `!!`** anywhere. No code comments beyond KDoc (this codebase documents *why* in KDoc, never inline
  narration of *what*).
- **Never run `./gradlew` while a `runIde` sandbox is live.** Check with exactly
  `pgrep -f "idea.plugin.in.sandbox.mode=true"` and `pgrep -f "gradlew.*runIde"` before every Gradle invocation; if
  either prints a pid, stop and report. Do **not** run `./gradlew runIde` — the human runs the gate.
- **Implementation first, tests last** (user's `CLAUDE.md`): tasks 1–3 ship production code with no test cycle;
  task 4 is the single Test phase for all three; task 5 is review; the gate and the roadmap follow.
- `main` is 23 commits ahead of `origin` and unpushed. Do not push; the human does.
- Test command: `./gradlew test --tests "com.devomer.previewgallery.<Class>"` (see the sandbox rule above).

---

## File Structure

| File | Change | Responsibility after the change |
|---|---|---|
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` | Modify (`startVerify`, `runVerify`, new field, new message helper, two test seams) | Owns *when* a verify runs and what the pane says when one does not |
| `src/main/resources/messages/PreviewGalleryBundle.properties` | Modify (one key) | The "nothing to verify" wording |
| `src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt` | Modify (new non-blocking reader + background refresh) | Both source-clock readers, blocking and stale-while-revalidate |
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt` | Modify (second `isStale` overload) | The staleness rule, in both a blocking and a non-blocking flavour |
| `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt` | Modify (one call site) | Paints the badge without touching the filesystem |
| `src/test/kotlin/com/devomer/previewgallery/render/ModuleFreshnessModuleTest.kt` | Modify (add cases) | Covers the new reader against a real module |
| `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt` | Modify (add cases) | Covers the trigger and the two gate checks that are automatable |

---

### Task 1: An explicit Verify survives a selection change inside the debounce

`startVerify` cancels the alarm before it knows whether it is going to re-arm it. Press **Verify snapshots**, then
move the selection within the debounce window: the non-forced call cancels the forced request and then declines to
re-arm, because `needsVerify` says this module's measurement still stands — which is exactly the answer the user
was forcing a refresh of. The run disappears with no message.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt:798-803` (`startVerify`), plus a
  new field next to `verifyInFlightModule` (around `:159`)

**Interfaces:**
- Consumes: `verifyAlarm`, `needsVerify(moduleName)`, `RenderPipeline.DEBOUNCE_MS`, `runVerify()` (unchanged here)
- Produces: `private var forcedVerifyPending: Boolean`, and the alarm callback that clears it — task 2 is what adds
  `runVerify`'s `force` parameter and passes it from this callback.

- [ ] **Step 1: Add the field**

Next to `verifyInFlightModule` (`PreviewGalleryPanel.kt:159`):

```kotlin
    /** True while an explicit Verify is armed on [verifyAlarm] and has not fired yet. The automatic path calls
     *  [startVerify] on every selection change and used to cancel that request on its way past — and then declined
     *  to re-arm, because [needsVerify] is false for exactly the module whose standing measurement the user was
     *  asking to refresh. The press vanished without a word. EDT-only, like every other field here: set and cleared
     *  in [startVerify] and in the alarm's own callback, so a forced run never outlives the request it belongs to. */
    private var forcedVerifyPending = false
```

- [ ] **Step 2: Rewrite `startVerify`**

Replace the body at `PreviewGalleryPanel.kt:798-803` with:

```kotlin
    private fun startVerify(snapshot: PreviewEntry?, force: Boolean) {
        if (!force && forcedVerifyPending) return
        verifyAlarm.cancelAllRequests()
        forcedVerifyPending = false
        if (snapshot == null) return
        if (!force && !needsVerify(snapshot.moduleName)) return
        forcedVerifyPending = force
        verifyAlarm.addRequest(
            {
                forcedVerifyPending = false
                runVerify()
            },
            RenderPipeline.DEBOUNCE_MS,
        )
    }
```

- [ ] **Step 3: Extend the KDoc above `startVerify`**

Append to the existing KDoc block (after the `[force]` paragraph at `:793-796`):

```
     * A pending forced request outranks the automatic path outright: [forcedVerifyPending] makes a non-forced call
     * return before it cancels anything. The run that then fires is still about whatever row is selected when the
     * alarm expires, exactly as it always was — [runVerify] reads the selection, not this argument — so the
     * user-visible rule is "the press is honoured", not "the press is pinned to a row".
```

`runVerify` keeps its current no-argument signature in this task — task 2 is what gives it a `force` parameter and
changes this call site with it. Do not add an unused parameter here.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL, no new warnings.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "$(cat <<'EOF'
[PG21-1] - Keep an explicit Verify through a selection change

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: A forced Verify with nothing to verify says so

`runVerify` starts `verifyTarget() ?: return`. On a module with snapshot tests but no committed goldens there is no
build variant to name, so the target is null and the press records nothing and says nothing.

The wording is the whole decision here. Recording a `NOT_RUN` attempt instead would replace the pane's
`No reference images — run the update…ScreenshotTest task for this module.` with a bare `Not verified` — strictly
less useful than the message it destroys, and persistent, where the press was a moment. So: nothing is recorded (no
run was launched, and an attempt is what a *run* reports), the automatic path stays silent (the pane it would
overwrite already carries the instruction), and the forced path shows one transient message that keeps that
instruction and adds why nothing ran.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt:821-822` (`runVerify`)
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties:60` (new key after `verify.notRun`)

**Interfaces:**
- Consumes: `verifyTarget(): VerifyTarget?`, `selectedSnapshotEntry(): PreviewEntry?`,
  `PreviewRenderPanel.showVerified(entry, images, message)` — an empty `images` with a non-null `message` renders
  the message as the pane's own centered text plus the "Open file" link.
- Produces: `private fun reportNothingToVerify()`; bundle key `verify.nothingToVerify`.

- [ ] **Step 1: Add the bundle key**

In `src/main/resources/messages/PreviewGalleryBundle.properties`, directly after
`verify.notRun=Not verified` (line 60):

```properties
verify.nothingToVerify=Nothing to verify — no reference images. Run the update…ScreenshotTest task for this module first.
```

The `update…ScreenshotTest` spelling is deliberate and matches `render.noReference` verbatim: with no reference
root there is no variant to name, so naming a concrete task would be a guess.

- [ ] **Step 2: Use `force` in `runVerify`**

Replace `PreviewGalleryPanel.kt:821-822`:

```kotlin
    private fun runVerify() {
        val target = verifyTarget() ?: return
```

with:

```kotlin
    private fun runVerify(force: Boolean) {
        val target = verifyTarget()
        if (target == null) {
            if (force) reportNothingToVerify()
            return
        }
```

The rest of the body is unchanged.

- [ ] **Step 3: Add the message helper**

Immediately after `runVerify`, before `showVerifyOutcome` (`PreviewGalleryPanel.kt:843`):

```kotlin
    /**
     * What an explicit Verify says when [verifyTarget] found nothing to run: no snapshot row selected, no module the
     * project model resolves, or — the case that actually reaches a user — no committed golden to name a build
     * variant with.
     *
     * The automatic path deliberately stays silent here, because the pane this replaces already says the useful
     * half (`render.noReference` names the task that would create the goldens). A button press has to answer, so
     * this message keeps that instruction and adds why nothing ran. Nothing is recorded in
     * [SnapshotVerifyStore]: an attempt is what a run reports, and no run was launched — a persistent `Not verified`
     * would outlive the press and hide the more useful sentence for good.
     */
    private fun reportNothingToVerify() {
        val snapshot = selectedSnapshotEntry() ?: return
        renderPanel.showVerified(snapshot, emptyList(), PreviewGalleryBundle.message("verify.nothingToVerify"))
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL, no unused-parameter warning left.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/main/resources/messages/PreviewGalleryBundle.properties
git commit -m "$(cat <<'EOF'
[PG21-2] - Answer a Verify that has nothing to verify

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: The staleness clock leaves the paint path

`PreviewTreeCellRenderer` asks `SnapshotVerifyStore.isStale` from inside Swing's per-row paint callback, for every
painted row of a module with a failing snapshot. That reaches `ModuleFreshness.newestModuleSourceMtime`, which walks
the module's whole source tree — deliberately unbounded, because a depth cap would let a deep-package edit read as
"nothing changed" — behind a 5 s TTL. Whenever the TTL has lapsed, one paint pays for the entire walk on the EDT.

The fix keeps the walk unbounded and moves it off the EDT: a stale-while-revalidate reader serves the last known
value (expired or not) and schedules the recomputation on the app executor, calling back so the tree repaints when
it lands. Only a genuinely cold cache reads unknown, which `isStale` already spells "stale" — the safe direction,
corrected one repaint later. The blocking reader stays exactly as it is for `needsVerify` and `verifyMessage`, both
of which need a real answer at the moment they ask: a cold-cache "unknown" there would launch a Gradle run, or tell
the user their code changed the instant after a verify measured it.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt` (new public reader, new private
  refresh, new in-flight set, `invalidate` untouched)
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt:156-160` (second `isStale`
  overload, shared module lookup)
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt:95` (one call site)

**Interfaces:**
- Consumes: `ModuleFreshness.newestModuleSourceMtime(module): Long?`, `SnapshotVerifyStore.isStale(measurement,
  newestSourceMillis)` (the existing pure companion rule).
- Produces:
  - `ModuleFreshness.cachedModuleSourceMtime(module: Module, onRefreshed: () -> Unit): Long?`
  - `SnapshotVerifyStore.isStale(measurement: Measurement, onRefreshed: () -> Unit): Boolean`

- [ ] **Step 1: Add the non-blocking reader to `ModuleFreshness`**

Insert after `newestModuleSourceMtime` (`ModuleFreshness.kt:109`):

```kotlin
    /**
     * [newestModuleSourceMtime]'s answer for a caller that must not wait for it: whatever is cached, expired or
     * not, with a background refresh scheduled whenever the cached value is missing or past [CACHE_TTL_MS], and
     * [onRefreshed] called once that refresh has landed.
     *
     * The paint path is the whole reason this exists. `PreviewTreeCellRenderer` asks whether a failing module's
     * verdict is stale from inside Swing's per-row paint callback, and [newestModuleSourceMtime] walks that
     * module's entire source tree — unbounded on purpose, see its own doc — the first time it is asked after the
     * TTL lapses. On the EDT, once per repaint of a large module, that is a visible hitch.
     *
     * Serving the *expired* value rather than nothing is what keeps the badge steady: returning null every time the
     * TTL lapsed would flicker a fresh verdict to "stale" once every [CACHE_TTL_MS], since
     * [com.devomer.previewgallery.service.SnapshotVerifyStore.isStale] reads an unknown clock as stale. Only a cold
     * cache reads unknown, and that is the safe direction — it overstates staleness for one paint, and
     * [onRefreshed] corrects it.
     *
     * One walk per module at a time ([refreshingSourceMtime]): every painted row of a failing module asks this same
     * question inside the same frame, and they must not each schedule their own walk.
     */
    fun cachedModuleSourceMtime(module: Module, onRefreshed: () -> Unit): Long? {
        val entry = sourceMtimeCache[module.name]
        if (entry == null || System.currentTimeMillis() - entry.computedAtMs > CACHE_TTL_MS) {
            refreshSourceMtime(module, onRefreshed)
        }
        return entry?.value
    }

    /**
     * Recomputes [module]'s source clock on the app executor and calls [onRefreshed] afterwards, at most one walk
     * per module in flight. [newestModuleSourceMtime] does the work and the caching, so the two readers cannot
     * disagree about what the clock means.
     *
     * [onRefreshed] is a `repaint()` in the only production caller, which Swing documents as safe from any thread;
     * anything heavier belongs on the EDT by its own hop, not here. It fires whether or not the value changed —
     * one extra repaint costs nothing, and comparing values here would mean caching them twice.
     */
    private fun refreshSourceMtime(module: Module, onRefreshed: () -> Unit) {
        if (!refreshingSourceMtime.add(module.name)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (!module.isDisposed) newestModuleSourceMtime(module)
            } catch (e: ProcessCanceledException) {
                thisLogger().debug("Source-clock refresh for '${module.name}' was cancelled", e)
            } catch (e: Exception) {
                thisLogger().warn("Failed to refresh the source clock for module '${module.name}'", e)
            } finally {
                refreshingSourceMtime.remove(module.name)
            }
            onRefreshed()
        }
    }
```

- [ ] **Step 2: Add the in-flight set and the imports**

Next to the two caches (`ModuleFreshness.kt:143-144`):

```kotlin
    private val refreshingSourceMtime = ConcurrentHashMap.newKeySet<String>()
```

Add to the imports at the top of the file:

```kotlin
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.concurrency.AppExecutorUtil
```

- [ ] **Step 3: Add the non-blocking `isStale` to the store**

Replace `SnapshotVerifyStore.kt:156-160`:

```kotlin
    fun isStale(measurement: Measurement): Boolean = isStale(measurement, newestSourceMillis(measurement.moduleName))

    private fun newestSourceMillis(moduleName: String): Long? =
        ModuleManager.getInstance(project).findModuleByName(moduleName)
            ?.let { ModuleFreshness.newestModuleSourceMtime(it) }
```

with:

```kotlin
    fun isStale(measurement: Measurement): Boolean = isStale(measurement, newestSourceMillis(measurement.moduleName))

    /**
     * [isStale] for a caller that must not block on a filesystem walk — the tree's per-row paint callback, and
     * nothing else. Same rule and the same "an unknown clock reads stale" direction; only where the clock comes
     * from differs. [onRefreshed] fires when the walk this call scheduled has landed, so the caller can ask again;
     * see [ModuleFreshness.cachedModuleSourceMtime] for why an expired value is served rather than an unknown.
     */
    fun isStale(measurement: Measurement, onRefreshed: () -> Unit): Boolean =
        isStale(measurement, moduleFor(measurement.moduleName)?.let { ModuleFreshness.cachedModuleSourceMtime(it, onRefreshed) })

    private fun newestSourceMillis(moduleName: String): Long? =
        moduleFor(moduleName)?.let { ModuleFreshness.newestModuleSourceMtime(it) }

    private fun moduleFor(moduleName: String) = ModuleManager.getInstance(project).findModuleByName(moduleName)
```

- [ ] **Step 4: Point the renderer at it**

At `PreviewTreeCellRenderer.kt:95`, replace:

```kotlin
                    val label = if (store.isStale(verify)) {
```

with:

```kotlin
                    val label = if (store.isStale(verify) { tree.repaint() }) {
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/ModuleFreshness.kt src/main/kotlin/com/devomer/previewgallery/service/SnapshotVerifyStore.kt src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt
git commit -m "$(cat <<'EOF'
[PG21-3] - Take the staleness walk off the paint path

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Test phase — the three fixes and the two gate checks that automate

This is the single Test phase for tasks 1–3, plus the automatable half of the two gate checks PG20 never ran. The
checks that need a real Gradle run stay in the manual gate below.

Two `@TestOnly` seams are needed, because the verify trigger is reachable from production only through a toolbar
action and a 400 ms alarm: one that *arms* the alarm (so the debounce rule can be asserted without waiting) and one
that *fires* the run (so the target and indexing paths can be asserted without an alarm at all).

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt` (two seams, near
  `navigateToSelectionForTest` at `:443`)
- Modify: `src/test/kotlin/com/devomer/previewgallery/render/ModuleFreshnessModuleTest.kt`
- Modify: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: everything produced by tasks 1–3, plus the existing seams `reloadSynchronously()`,
  `selectByLabelPathForTest(vararg labels)`, `renderMessageForTest`.
- Produces: `verifyForTest(force: Boolean)`, `pendingVerifyRequestsForTest`, `runVerifyForTest(force: Boolean)`.

- [ ] **Step 1: Add the test seams**

After `navigateToSelectionForTest` (`PreviewGalleryPanel.kt:443`):

```kotlin
    /** Arms the verify alarm exactly as the toolbar action and the selection listener do, so the debounce rules can
     *  be asserted without a 400 ms wait. Deliberately does not fire the run — [runVerifyForTest] is for that. */
    @TestOnly
    internal fun verifyForTest(force: Boolean) = startVerify(selectedSnapshotEntry(), force)

    /** How many verify runs are currently armed on [verifyAlarm]: 1 after a request that stands, 0 after one that
     *  was cancelled or declined. */
    @get:TestOnly
    internal val pendingVerifyRequestsForTest: Int get() = verifyAlarm.activeRequestCount

    /** Fires the run the alarm would fire, on the calling thread. [SnapshotVerifyRunner] refuses synchronously
     *  while the project is indexing, and [runVerify] answers a missing target synchronously, so both of those
     *  paths complete before this returns. */
    @TestOnly
    internal fun runVerifyForTest(force: Boolean) = runVerify(force)
```

- [ ] **Step 2: Cover the debounce rule (task 1)**

Add to `PreviewGalleryPanelTest`, which already has every fixture piece this needs: `projectWithSnapshot()` writes
the `@Preview` in `main` and the matching `@PreviewTest` under `src/screenshotTest`, `panel()` builds the panel,
`referencePng(directory, name)` commits a golden, and `reloadSynchronously()` loads the tree. The store needs a
standing measurement so `needsVerify` answers false for the non-forced call — that is the exact state the bug needs.

```kotlin
    fun `test a non-forced verify does not cancel an explicit one armed inside the debounce`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")
        SnapshotVerifyStore.getInstance(project).record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = listOf(passingResult("Widget_Default_Snapshot")),
            launchedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = System.currentTimeMillis(),
        )

        panel.verifyForTest(force = true)
        assertEquals(1, panel.pendingVerifyRequestsForTest)

        panel.verifyForTest(force = false)

        assertEquals(
            "an automatic verify must not cancel the run the user asked for",
            1,
            panel.pendingVerifyRequestsForTest,
        )
    }
```

`passingResult` is a local helper in the test class. Build it by copying `SnapshotVerifyStoreTest`'s own
`results(...)` helper — that file already constructs a `SnapshotVerifyResults.SnapshotResult` against the real
constructor, so copy from there rather than from this plan, and keep only what this test needs:

```kotlin
    private fun passingResult(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.PASSED,
    )
```

If the real constructor has no defaults for the image paths, pass nulls for them — a passing result carries no
diff, and this test never publishes it.

`module.name` is what `PreviewEntry.moduleName` holds for the light fixture's single module. If an assertion fails
because the store's key does not match the row's, read the module name off the tree
(`panel.visibleRowLabelsForTest()` shows it as the module row) rather than guessing a second time.

- [ ] **Step 3: Cover the "nothing to verify" message (task 2)**

The fixture for both is `projectWithSnapshot()` with **no** `referencePng(...)` call at all — no reference
directory means no build variant to name, which is what makes `verifyTarget()` null. The existing test
`test a module with no reference directory at all names no task` is that exact fixture, and its assertion is the
"before" message these two are about.

```kotlin
    fun `test an explicit verify with no committed goldens says so instead of nothing`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        panel.runVerifyForTest(force = true)

        assertEquals(
            PreviewGalleryBundle.message("verify.nothingToVerify"),
            panel.renderMessageForTest,
        )
    }

    fun `test an automatic verify with no committed goldens leaves the no-reference pane alone`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        panel.runVerifyForTest(force = false)

        assertEquals(
            "the automatic path must not overwrite the pane's own instruction",
            "No reference images — run the update…ScreenshotTest task for this module.",
            panel.renderMessageForTest,
        )
    }
```

- [ ] **Step 4: Cover the indexing refusal (gate check 2)**

`DumbModeTestUtils.runInDumbModeSynchronously` (`com.intellij.testFramework.DumbModeTestUtils`) puts the fixture
project in dumb mode; the runner refuses there without touching Gradle and reports `NOT_RUN` on the calling thread.
This needs a committed golden, or `verifyTarget()` returns null before the runner is ever reached — the same
`referencePng` call the reference tests make, with the plain `Debug` variant.

```kotlin
    private fun projectWithSnapshotAndGolden() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
    }

    fun `test a verify pressed while the project is indexing records that it did not run`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            panel.runVerifyForTest(force = true)
        }

        val attempt = requireNotNull(SnapshotVerifyStore.getInstance(project).lastAttempt(module.name))
        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, attempt.outcome)
    }

    fun `test a snapshot row whose module never ran a verify shows the outcome, not a silent strip`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        SnapshotVerifyStore.getInstance(project).record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.NOT_RUN,
            results = emptyList(),
            launchedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = System.currentTimeMillis(),
        )

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(PreviewGalleryBundle.message("verify.notRun"), panel.renderMessageForTest)
    }
```

Together these are the gate check: the refusal is recorded, and a recorded refusal is what the pane shows.

- [ ] **Step 5: Cover the UP-TO-DATE second verify (gate check 1)**

The store half is already covered (`SnapshotVerifyStoreTest`:
`test an UP-TO-DATE run that rewrites no results leaves the measurement intact and records the attempt`). What was
never observed is the *pane*: the older measurement's images still publish, with the attempt's sentence beside
them. `selectByLabelPathForTest` routes synchronously through `publishVerifiedResultSynchronously`, which reads the
result's `goldenPath`/`renderedPath` with `ImageIO.read(File(path))` — so these must be **real files on disk**, not
`referencePng`'s `temp://` fixture files, which have no `java.io.File` behind them:

```kotlin
    private fun pngOnDisk(name: String): String {
        val directory = FileUtil.createTempDirectory("preview-gallery-verify", null)
        val file = File(directory, name)
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", file)
        Disposer.register(testRootDisposable) { FileUtil.delete(directory) }
        return file.path
    }

    private fun failingResultWithImagesOnDisk(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.FAILED,
        goldenPath = pngOnDisk("golden.png"),
        renderedPath = pngOnDisk("rendered.png"),
        diffPath = pngOnDisk("diff.png"),
    )
```

Match the real constructor as in step 2 — copy the parameter list from `SnapshotVerifyStoreTest`'s `results(...)`.

```kotlin
    fun `test an UP-TO-DATE second verify keeps the measurement and says the attempt measured nothing`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        val store = SnapshotVerifyStore.getInstance(project)
        val launched = System.currentTimeMillis()
        store.record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = listOf(failingResultWithImagesOnDisk("Widget_Default_Snapshot")),
            launchedAtMillis = launched,
            finishedAtMillis = launched,
        )
        store.record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = emptyList(),
            launchedAtMillis = launched,
            finishedAtMillis = launched + 1_000,
        )

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertNotNull("the earlier measurement must survive a run that measured nothing", store.measurementFor(module.name))
        val message = requireNotNull(panel.renderMessageForTest)
        assertTrue(
            "expected the attempt's own sentence beside the older measurement, got: $message",
            message.contains("measured nothing"),
        )
    }
```

`contains`, not `assertEquals`: this pane deliberately says more than one thing at once, and in this fixture it
will also carry the staleness sentence — the light fixture's source roots are `temp://`, so the source clock is
unknown and `isStale` reads true by design. That is correct behaviour, not a broken test. What must not happen is
the attempt's sentence going missing, or the measurement's images not publishing at all.

- [ ] **Step 6: Cover the non-blocking source clock (task 3)**

The serve-while-expired branch is the one worth a test and the one a test cannot reach without waiting out
`CACHE_TTL_MS`. Add a test-only expiry seam to `ModuleFreshness`, next to `invalidate`:

```kotlin
    /** Ages [module]'s cached verdicts past [CACHE_TTL_MS] without waiting for it, so the serve-while-expired
     *  branch of [cachedModuleSourceMtime] can be asserted. Deliberately not [invalidate], which *drops* the entry
     *  — the whole point of that branch is what happens to an entry that still exists and is merely old. */
    @TestOnly
    internal fun expireCachesForTest(module: Module) {
        sourceMtimeCache.computeIfPresent(module.name) { _, entry -> CacheEntry(0L, entry.value) }
        freshnessCache.computeIfPresent(module.name) { _, entry -> CacheEntry(0L, entry.value) }
    }
```

(`import org.jetbrains.annotations.TestOnly`.) Then, in `ModuleFreshnessModuleTest`, whose fixture already writes
real files at `OLDER_MTIME` on disk:

```kotlin
    fun `test the non-blocking source clock reads unknown while cold and the real mtime once the refresh lands`() {
        val refreshed = CountDownLatch(1)

        assertNull(
            "a cold cache must not walk the tree on the caller's thread",
            ModuleFreshness.cachedModuleSourceMtime(module) { refreshed.countDown() },
        )
        assertTrue("the background refresh did not land", refreshed.await(10, TimeUnit.SECONDS))

        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) {}))
    }

    fun `test the non-blocking source clock serves the expired value rather than unknown`() {
        assertEquals(OLDER_MTIME, requireNotNull(ModuleFreshness.newestModuleSourceMtime(module)))
        writeOnDisk("src/main/kotlin/com/example/Widget.kt", NEWER_MTIME)
        ModuleFreshness.expireCachesForTest(module)
        val refreshed = CountDownLatch(1)

        assertEquals(
            "an expired entry must still be served, or the badge flickers once every TTL",
            OLDER_MTIME,
            requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) { refreshed.countDown() }),
        )
        assertTrue("the background refresh did not land", refreshed.await(10, TimeUnit.SECONDS))

        assertEquals(
            "the refresh behind the expired value must land the new mtime",
            NEWER_MTIME,
            requireNotNull(ModuleFreshness.cachedModuleSourceMtime(module) {}),
        )
    }
```

Imports for the test file: `java.util.concurrent.CountDownLatch`, `java.util.concurrent.TimeUnit`.

- [ ] **Step 7: Imports**

`PreviewGalleryPanelTest` gains: `com.devomer.previewgallery.PreviewGalleryBundle`,
`com.devomer.previewgallery.render.SnapshotVerifyRunner`, `com.devomer.previewgallery.service.SnapshotVerifyResults`,
`com.devomer.previewgallery.service.SnapshotVerifyStore`, `com.intellij.openapi.util.io.FileUtil`,
`com.intellij.testFramework.DumbModeTestUtils`, `java.io.File`. `BufferedImage`, `ImageIO`, `Disposer` and
`WriteAction` are already imported there.

- [ ] **Step 8: Run the touched suites**

Run:

```bash
./gradlew test --tests "com.devomer.previewgallery.ui.PreviewGalleryPanelTest" --tests "com.devomer.previewgallery.render.ModuleFreshnessModuleTest" --tests "com.devomer.previewgallery.service.SnapshotVerifyStoreTest" --tests "com.devomer.previewgallery.ui.PreviewTreeCellRendererTest"
```

Expected: PASS. Check the sandbox rule first.

- [ ] **Step 9: Run the whole suite**

Run: `./gradlew test`
Expected: PASS — the count should be the pre-existing total plus the cases added here. Report the number.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt src/test/kotlin/com/devomer/previewgallery
git commit -m "$(cat <<'EOF'
[PG21-4] - Cover the verify trigger and the two unrun gate checks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Code review

- [ ] **Step 1: Review**

Run `/code-review` (or `superpowers:requesting-code-review`) over `PG21-1..PG21-4`. Pay attention to: the EDT
discipline of `forcedVerifyPending` (every read and write must be on the EDT), whether `refreshSourceMtime`'s
`onRefreshed` can be called after the panel is disposed and whether that matters (`JComponent.repaint()` on a
disposed tree is a no-op, but say so explicitly rather than assuming), and any test that would pass with the
production change reverted.

- [ ] **Step 2: Fix what it reports, then re-run `./gradlew test`**

- [ ] **Step 3: Commit the fixes as `[PG21-5] - <what was fixed>`**

---

## Manual gate

Against `hepsi-android`, from a `runIde` sandbox, after the review. The human runs this.

1. Select a snapshot row in a module whose snapshots all pass and let the verify finish. Select it again and force a
   second run with **Verify snapshots**. Gradle reports the task UP-TO-DATE and writes no XML: the row keeps its
   verdict, and the pane says *"The verify at … measured nothing — this is the last measured result"* next to the
   images from the first run. **(PG20 gate check 1, never observed.)**
2. Press **Verify snapshots** while the project is indexing. The pane says *"Not verified"* — visibly, without
   hovering. **(PG20 gate check 2, never observed.)**
3. Select a snapshot row in a module with snapshot tests but **no** committed goldens. The pane names the
   `update…ScreenshotTest` task, as before. Press **Verify snapshots**: it now says *"Nothing to verify — no
   reference images. Run the update…ScreenshotTest task for this module first."* instead of doing nothing.
4. Press **Verify snapshots**, then immediately arrow to a sibling snapshot row of the same module. The run still
   happens — before this change it was cancelled silently.
5. Corrupt a golden, let the verify fail, then scroll the tree fast over the failing module's rows. The `differs ·
   stale` badge is correct and scrolling is smooth — the source-tree walk no longer runs in the paint callback.
6. Restore the corrupted golden.

Report anything that differs from the above rather than fixing it silently; a gate finding is worth more than a
clean gate.

## Roadmap

After the gate passes, update **H2** in `docs/snapshot-testing-roadmap.md`: mark it shipped (PG21), record what each
of the five items turned out to be, and note which of the two gate checks are now covered by tests as well as
observed. Move **F5's diff half** to the only remaining priority-1 row. Commit as
`[PG21-6] - Record the verify hardening in the roadmap`.
