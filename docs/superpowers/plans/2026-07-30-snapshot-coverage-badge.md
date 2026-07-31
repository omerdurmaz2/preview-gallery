# Snapshot Coverage Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Index the `@PreviewTest` functions of the Compose Preview Screenshot Testing plugin, badge every preview row with whether the composable it shows has a snapshot, hang each snapshot under the preview it corresponds to, and show the committed reference PNGs side by side when a snapshot row is selected.

**Architecture:** The scanner learns two new file-local facts per function — whether `@PreviewTest` is on it, and which composables its body shows (`targets`, extracted by descending only through trailing lambdas). `PreviewIndexService` joins them at query time: a preview is covered when a snapshot in the same module shares a target. The tree gains snapshot child rows and a per-module orphan branch; the render panel gains a reference-image strip that reads the committed PNGs straight off disk. No snapshot function is ever handed to layoutlib.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install at `platformLocalPath`) · bundled `org.jetbrains.android` + `com.android.tools.design` · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-30-snapshot-coverage-badge-design.md](../specs/2026-07-30-snapshot-coverage-badge-design.md)

## Global Constraints

- **Never use the Kotlin `!!` operator.** Use `?:`, `?.let`, or an explicit null check.
- **All source, comments, docs and test names in English.**
- Commit message pattern: `[PG13-N] - Task name` (this feature's task ids are `PG13-3` … `PG13-12`; `PG13-0`, `PG13-1` and `PG13-2` are the roadmap note, the design spec and this plan).
- Pure-logic tests use plain JUnit 4 (`@Test` + `org.junit.Assert`). Tests needing a project or PSI use `BasePlatformTestCase` with backticked names starting `test `.
- **An indexer must never resolve anything outside the file it is indexing.** Every new fact in `index/` is derived from the file's own text and import list.
- Snapshot functions are **never** passed to `RenderPipeline`, `LiveRenderer` or any `com.android.tools.*` type. This phase reads committed PNGs only.
- No `com.android.tools.*` import outside `render/`. Matching, target extraction, path derivation and variant parsing stay Swing-free and AS-free.
- Sorting is **case-insensitive**, comparing sorted lists rather than comparator-keyed maps (a `TreeMap` under `CASE_INSENSITIVE_ORDER` merges `Buy` and `buy` and silently drops a subtree).
- **Build/test command:** `./gradlew test`. Do **not** run any `./gradlew` task while a `runIde` sandbox is running — kill the sandbox first. Do not run `./gradlew runIde` (the human runs that gate).

---

## File Structure

**Create**

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/devomer/previewgallery/index/TargetExtractor.kt` | Pure: `KtNamedFunction` → the composable names its body shows |
| `src/main/kotlin/com/devomer/previewgallery/model/SnapshotCoverage.kt` | `Covered(count)` / `Uncovered` / `NotApplicable` |
| `src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt` | One reference PNG: variant name + `VirtualFile` |
| `src/main/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolver.kt` | Pure: rows → previews with coverage + orphan snapshots |
| `src/main/kotlin/com/devomer/previewgallery/service/ScreenshotModuleDetector.kt` | Does this module have a `src/screenshotTest` directory? |
| `src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt` | Reference directory derivation, prefix glob, variant parsing |
| `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripView.kt` | Side-by-side images at one shared scale, variant label under each |

**Modify**

| File | Change |
|---|---|
| `index/PreviewAnnotationMatcher.kt` | Recognise `com.android.tools.screenshot.PreviewTest` |
| `index/PreviewPsiScanner.kt` | Populate `isSnapshotTest` and `targets` |
| `index/PreviewValueExternalizer.kt` | Serialize the two new fields |
| `index/PreviewIndex.kt` | `VERSION` bump |
| `model/IndexedPreview.kt` | `isSnapshotTest`, `targets` |
| `model/PreviewRow.kt` | `coverage`, `snapshots` (defaulted) |
| `model/PreviewEntry.kt` | Carry coverage and attached snapshots |
| `service/PreviewIndexService.kt` | Join coverage; expose orphans |
| `ui/PreviewNode.kt` | `SnapshotLeaf`, `OrphanSnapshotBranch`; `PreviewLeaf.snapshots`; `ModuleNode.orphans` |
| `ui/PackageTreeBuilder.kt` | Build leaves with their snapshots |
| `ui/ModuleTreeBuilder.kt` | Route orphan snapshots to their module |
| `ui/PreviewTreeModelBuilder.kt` | Filter previews and orphans separately |
| `ui/PreviewTreeCellRenderer.kt` | Badge text; snapshot and orphan-branch rows |
| `ui/PreviewGalleryPanel.kt` | Add the new nodes to the `JTree`; route snapshot selection |
| `ui/PreviewRenderPanel.kt` | `REFERENCE` and `NO_REFERENCE` states |
| `render/RenderState.kt` | Two new enum constants |

---

### Task 1: Recognise `@PreviewTest`

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcher.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcherTest.kt`

**Interfaces:**
- Consumes: `ImportInfo(importedFqn: String, alias: String?, isAllUnder: Boolean)` — existing.
- Produces: `PreviewAnnotationMatcher.isPreviewTest(reference: String, imports: List<ImportInfo>): Boolean`

- [ ] **Step 1: Write the failing tests**

Append to `PreviewAnnotationMatcherTest.kt`:

```kotlin
@Test
fun `preview test matched through an import`() {
    val imports = listOf(ImportInfo("com.android.tools.screenshot.PreviewTest", null, false))
    assertTrue(PreviewAnnotationMatcher.isPreviewTest("PreviewTest", imports))
}

@Test
fun `preview test matched when fully qualified`() {
    assertTrue(PreviewAnnotationMatcher.isPreviewTest("com.android.tools.screenshot.PreviewTest", emptyList()))
}

@Test
fun `preview test matched through a star import`() {
    val imports = listOf(ImportInfo("com.android.tools.screenshot", null, true))
    assertTrue(PreviewAnnotationMatcher.isPreviewTest("PreviewTest", imports))
}

@Test
fun `unrelated PreviewTest name is not matched`() {
    val imports = listOf(ImportInfo("com.example.PreviewTest", null, false))
    assertFalse(PreviewAnnotationMatcher.isPreviewTest("PreviewTest", imports))
}

@Test
fun `plain Preview is not a preview test`() {
    val imports = listOf(ImportInfo("androidx.compose.ui.tooling.preview.Preview", null, false))
    assertFalse(PreviewAnnotationMatcher.isPreviewTest("Preview", imports))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "*PreviewAnnotationMatcherTest*"`
Expected: FAIL — `Unresolved reference: isPreviewTest`.

- [ ] **Step 3: Implement**

In `PreviewAnnotationMatcher`, add the constant next to the existing FQN constants:

```kotlin
const val PREVIEW_TEST = "com.android.tools.screenshot.PreviewTest"
```

and, next to `PREVIEW_PARAMETER_SHORT_NAME`:

```kotlin
private const val PREVIEW_TEST_SHORT_NAME = "PreviewTest"
```

Then add the public function. The existing private `match` takes two FQNs because `@Preview` has an androidx
and a JetBrains spelling; `@PreviewTest` has one, so both parameters get the same value and a non-null result
means "matched":

```kotlin
/** `@PreviewTest` marks a snapshot function of the Compose Preview Screenshot Testing plugin. */
fun isPreviewTest(reference: String, imports: List<ImportInfo>): Boolean =
    match(reference, imports, PREVIEW_TEST_SHORT_NAME, PREVIEW_TEST, PREVIEW_TEST) != null
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*PreviewAnnotationMatcherTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcher.kt src/test/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcherTest.kt
git commit -m "[PG13-3] - Recognise the PreviewTest annotation"
```

---

### Task 2: Extract the composables a preview body shows

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/index/TargetExtractor.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/index/TargetExtractorTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TargetExtractor.extract(function: KtNamedFunction): List<String>`

The rule (spec D2): start at the function body, descend **only through trailing lambdas**, never into argument
lists, and keep only PascalCase callee names. The deepest lambda body reached contributes its calls; if that body
holds several calls, all of them are targets.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/index/TargetExtractorTest.kt`. This needs PSI, so it is a
`BasePlatformTestCase`:

```kotlin
package com.devomer.previewgallery.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class TargetExtractorTest : BasePlatformTestCase() {

    private fun extract(body: String): List<String> {
        val file = myFixture.configureByText(
            "Snapshots.kt",
            """
            package com.example

            fun Subject() $body
            """.trimIndent(),
        ) as KtFile
        val function = file.declarations.filterIsInstance<KtNamedFunction>().single()
        return TargetExtractor.extract(function)
    }

    fun `test wrapper chain yields the innermost call`() {
        assertEquals(
            listOf("ErrorRetryRow"),
            extract("= PreviewComponent { PrimusTheme { ErrorRetryRow(onRetry = {}) } }"),
        )
    }

    fun `test block body is descended the same way`() {
        assertEquals(
            listOf("ErrorRetryRow"),
            extract("{ PreviewComponent { PrimusTheme { ErrorRetryRow() } } }"),
        )
    }

    fun `test argument lists are not entered`() {
        assertEquals(
            listOf("FavoritesContent"),
            extract("= PreviewComponent { PrimusTheme { FavoritesContent(state = FakeState(), onAction = {}) } }"),
        )
    }

    fun `test several calls in the innermost lambda are all targets`() {
        assertEquals(
            listOf("Header", "Row"),
            extract("= PreviewComponent { PrimusTheme { Column { Header(); Row() } } }"),
        )
    }

    fun `test camel case callees are ignored`() {
        assertEquals(
            listOf("ListRowRenderer"),
            extract("= PreviewComponent { PrimusTheme { fakeScope { ListRowRenderer() } } }"),
        )
    }

    fun `test a body with no call yields nothing`() {
        assertEquals(emptyList<String>(), extract("{ }"))
    }

    fun `test duplicate calls are reported once`() {
        assertEquals(
            listOf("LiteProductCard"),
            extract("= PreviewComponent { Column { LiteProductCard(); LiteProductCard() } }"),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*TargetExtractorTest*"`
Expected: FAIL — `Unresolved reference: TargetExtractor`.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/devomer/previewgallery/index/TargetExtractor.kt`:

```kotlin
package com.devomer.previewgallery.index

import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * The composables a preview body *shows*, used to match a `@Preview` to the `@PreviewTest` that snapshots the
 * same component. Function names do not correspond across the two source sets (`ErrorRetryRowPreview` vs.
 * `ErrorRetryRow_Default_Snapshot`), but both bodies call `ErrorRetryRow`.
 *
 * Descent goes through trailing lambdas only, so wrapper composables (`PreviewComponent`, `PrimusTheme`,
 * `Column`) are consumed by the walk itself and need no configured deny-list. Argument lists are never entered,
 * which keeps `state = FakeState()` out of the result. Only PascalCase callees are kept — the Compose naming
 * convention — which excludes helper calls like `fakeState()`.
 *
 * This runs inside a `FileBasedIndex` indexer, so it resolves nothing: a callee is identified by the text of its
 * name, not by what that name binds to.
 */
object TargetExtractor {

    fun extract(function: KtNamedFunction): List<String> {
        val body = function.bodyExpression ?: return emptyList()
        return descend(callsIn(body))
            .map { it.name }
            .filter { it.isComposableName() }
            .distinct()
    }

    /**
     * Follows the single-call trailing-lambda chain down. A level with no calls at all ends the walk and yields
     * the level above it, so `Column { }` reports `Column` rather than nothing.
     *
     * The walk does not filter by case — a non-composable scope wrapper (`fakeScope { ListRowRenderer() }`) has
     * to be descended through like any other, or its content would be lost. Filtering happens once, at the end.
     */
    private fun descend(calls: List<Call>): List<Call> {
        val single = calls.singleOrNull() ?: return calls
        val lambda = single.trailingLambda ?: return calls
        val inner = lambda.bodyExpression?.let { callsIn(it) }.orEmpty()
        if (inner.isEmpty()) return calls
        return descend(inner)
    }

    /** Direct call children of [expression], never descending into arguments or nested lambdas. */
    private fun callsIn(expression: KtExpression): List<Call> {
        val statements = when (expression) {
            is KtBlockExpression -> expression.statements
            else -> listOf(expression)
        }
        return statements.mapNotNull { statement ->
            val call = statement as? KtCallExpression ?: return@mapNotNull null
            val name = call.calleeExpression?.text ?: return@mapNotNull null
            Call(name, call.lambdaArguments.lastOrNull()?.getLambdaExpression())
        }
    }

    /** PascalCase: Compose composables are capitalised, plain helper functions are not. */
    private fun String.isComposableName(): Boolean = firstOrNull()?.isUpperCase() == true

    private data class Call(val name: String, val trailingLambda: KtLambdaExpression?)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*TargetExtractorTest*"`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/index/TargetExtractor.kt src/test/kotlin/com/devomer/previewgallery/index/TargetExtractorTest.kt
git commit -m "[PG13-4] - Extract the composables a preview body shows"
```

---

### Task 3: Carry the two new facts through the index

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/IndexedPreview.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/index/PreviewPsiScanner.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizer.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/index/PreviewIndex.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/index/PreviewPsiScannerTest.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizerTest.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt`

**Interfaces:**
- Consumes: `PreviewAnnotationMatcher.isPreviewTest` (Task 1), `TargetExtractor.extract` (Task 2).
- Produces: `IndexedPreview.isSnapshotTest: Boolean`, `IndexedPreview.targets: List<String>`.

A snapshot function is `@PreviewTest`-annotated **and** `@Preview`-annotated (directly or through a
multipreview). The scanner's existing loop only emits functions whose annotations match `@Preview`, and the
project's snapshots use the custom `@SnapshotPreviews` multipreview, which is unresolvable file-locally. So the
emit condition changes: a function is emitted when it matches `@Preview` **or** carries `@PreviewTest`.

- [ ] **Step 1: Write the failing tests**

Append to `PreviewPsiScannerTest.kt`:

```kotlin
fun `test preview test function is flagged and its target extracted`() {
    val previews = scan(
        "ComponentsSnapshots.kt",
        """
        package com.example

        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        @SnapshotPreviews
        internal fun ErrorRetryRow_Default_Snapshot() = PreviewComponent {
            PrimusTheme {
                ErrorRetryRow(onRetry = {})
            }
        }
        """.trimIndent(),
    )
    assertEquals(1, previews.size)
    val preview = previews.single()
    assertTrue(preview.isSnapshotTest)
    assertEquals(listOf("ErrorRetryRow"), preview.targets)
    assertEquals("com.example.ComponentsSnapshotsKt", preview.jvmClassName)
}

fun `test ordinary preview is not flagged and still carries targets`() {
    val previews = scan(
        "Components.kt",
        """
        package com.example

        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        private fun ErrorRetryRowPreview() = PreviewComponent {
            ErrorRetryRow(onRetry = {})
        }
        """.trimIndent(),
    )
    val preview = previews.single()
    assertFalse(preview.isSnapshotTest)
    assertEquals(listOf("ErrorRetryRow"), preview.targets)
}
```

Append to `PreviewValueExternalizerTest.kt`:

```kotlin
@Test
fun `snapshot flag and targets survive a round trip`() {
    val original = IndexedPreview(
        displayName = "ErrorRetryRow_Default_Snapshot",
        functionName = "ErrorRetryRow_Default_Snapshot",
        packageName = "com.example",
        jvmClassName = "com.example.ComponentsSnapshotsKt",
        composableFqn = "com.example.ComponentsSnapshotsKt.ErrorRetryRow_Default_Snapshot",
        offset = 7,
        annotationKind = AnnotationKind.ANDROIDX,
        isPrivate = false,
        hasPreviewParameter = false,
        previewGroup = null,
        unsupportedReason = null,
        isSnapshotTest = true,
        targets = listOf("ErrorRetryRow", "ErrorRetryRowHeader"),
    )
    val output = ByteArrayOutputStream()
    PreviewValueExternalizer.save(DataOutputStream(output), listOf(original))
    val read = PreviewValueExternalizer.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
    assertEquals(listOf(original), read)
}

@Test
fun `an empty target list survives a round trip`() {
    val original = IndexedPreview(
        displayName = "BarPreview",
        functionName = "BarPreview",
        packageName = "com.example",
        jvmClassName = "com.example.FooKt",
        composableFqn = "com.example.FooKt.BarPreview",
        offset = 0,
        annotationKind = AnnotationKind.ANDROIDX,
        isPrivate = false,
        hasPreviewParameter = false,
        previewGroup = null,
        unsupportedReason = null,
    )
    val output = ByteArrayOutputStream()
    PreviewValueExternalizer.save(DataOutputStream(output), listOf(original))
    val read = PreviewValueExternalizer.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
    assertEquals(listOf(original), read)
    assertFalse(read.single().isSnapshotTest)
    assertEquals(emptyList<String>(), read.single().targets)
}
```

Imports needed: `java.io.ByteArrayInputStream`, `java.io.ByteArrayOutputStream`, `java.io.DataInputStream`,
`java.io.DataOutputStream`. If the file already has a round-trip helper, use it instead of repeating the
stream plumbing.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "*PreviewPsiScannerTest*" --tests "*PreviewValueExternalizerTest*"`
Expected: FAIL — `No value passed for parameter 'isSnapshotTest'` / unresolved property.

- [ ] **Step 3: Implement**

`IndexedPreview` — add the two fields at the end of the constructor so existing positional callers keep working:

```kotlin
    /** Non-null when the preview cannot be rendered, e.g. because it is declared inside a class. */
    val unsupportedReason: String?,
    /** `@PreviewTest` is written directly on this function: it is a snapshot, not a gallery preview. */
    val isSnapshotTest: Boolean = false,
    /** The composables this body shows, used to match a preview to the snapshot of the same component. */
    val targets: List<String> = emptyList(),
)
```

`PreviewPsiScanner.scan` — inside `visitNamedFunction`, replace the early return so a `@PreviewTest` function is
emitted even when no `@Preview` matches:

```kotlin
override fun visitNamedFunction(function: KtNamedFunction) {
    super.visitNamedFunction(function)
    val matches = function.annotationEntries.mapNotNull { entry ->
        val reference = entry.referenceText() ?: return@mapNotNull null
        PreviewAnnotationMatcher.matchPreview(reference, imports)?.let { entry to it }
    }
    val isSnapshotTest = function.annotationEntries.any { entry ->
        val reference = entry.referenceText() ?: return@any false
        PreviewAnnotationMatcher.isPreviewTest(reference, imports)
    }
    if (matches.isEmpty() && !isSnapshotTest) return
    val (annotation, kind) = matches.firstOrNull() ?: (null to AnnotationKind.UNKNOWN)
    result += build(
        function, annotation, kind, matches.size > 1, packageName, file.name, jvmNameOverride, imports,
        isSnapshotTest,
    )
}
```

`build` takes `annotation: KtAnnotationEntry?` now and gains an `isSnapshotTest: Boolean` parameter. Guard the
two annotation reads and populate the new fields:

```kotlin
val name = if (hasMultiplePreviews || annotation == null) {
    functionName
} else {
    namedString(annotation, "name") ?: positionalString(annotation, 0) ?: functionName
}
```

```kotlin
    previewGroup = annotation?.let { namedString(it, "group") },
    unsupportedReason = (container as? Container.Unsupported)?.reason,
    isSnapshotTest = isSnapshotTest,
    targets = TargetExtractor.extract(function),
)
```

`PreviewValueExternalizer` — write the new fields last in `save`:

```kotlin
            out.writeBoolean(preview.isSnapshotTest)
            DataInputOutputUtil.writeINT(out, preview.targets.size)
            preview.targets.forEach { IOUtil.writeUTF(out, it) }
```

and read them in the same order in `read`:

```kotlin
            val isSnapshotTest = input.readBoolean()
            val targetCount = DataInputOutputUtil.readINT(input)
            val targets = List(targetCount) { IOUtil.readUTF(input) }
```

passing both into the `IndexedPreview(...)` construction.

`PreviewIndex` — bump the version and widen the text gate's comment. `@PreviewTest` contains the substring
`Preview`, so `MARKER` still admits every snapshot file and needs no change; say so:

```kotlin
        /** Bump on any change to [PreviewValueExternalizer] or to what the scanner produces. */
        const val VERSION = 2

        /**
         * Cheap text gate: files that never mention Preview are skipped before PSI is built. `@PreviewTest`
         * contains the same substring, so snapshot files pass this gate unchanged.
         */
        private const val MARKER = "Preview"
```

`TestPreviewRow.kt` — later tasks build snapshot rows and copy coverage onto them, so extend both the class and
the factory now. `coverage` and `snapshots` come from `PreviewRow`'s new defaulted properties, which Task 4 adds;
declare them here as constructor overrides so `copy(...)` works:

```kotlin
data class TestPreviewRow(
    override val indexed: IndexedPreview,
    override val moduleName: String,
    override val coverage: SnapshotCoverage = SnapshotCoverage.NotApplicable,
    override val snapshots: List<TestPreviewRow> = emptyList(),
) : PreviewRow

fun testRow(
    displayName: String = "BarPreview",
    functionName: String = "BarPreview",
    packageName: String = "com.example",
    moduleName: String = "app",
    isSnapshotTest: Boolean = false,
    targets: List<String> = emptyList(),
): TestPreviewRow = TestPreviewRow(
    indexed = IndexedPreview(
        displayName = displayName,
        functionName = functionName,
        packageName = packageName,
        jvmClassName = "$packageName.FooKt",
        composableFqn = "$packageName.FooKt.$functionName",
        offset = 0,
        annotationKind = AnnotationKind.ANDROIDX,
        isPrivate = false,
        hasPreviewParameter = false,
        previewGroup = null,
        unsupportedReason = null,
        isSnapshotTest = isSnapshotTest,
        targets = targets,
    ),
    moduleName = moduleName,
)
```

`SnapshotCoverage` does not exist until Task 4, so in this task add only `isSnapshotTest` and `targets` to
`testRow`, and add the `coverage` / `snapshots` constructor properties as the first step of Task 4.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS. The whole suite runs because `IndexedPreview`'s shape changed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/IndexedPreview.kt src/main/kotlin/com/devomer/previewgallery/index/ src/test/kotlin/com/devomer/previewgallery/index/ src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt
git commit -m "[PG13-5] - Index the snapshot flag and preview targets"
```

---

### Task 4: Resolve coverage from the indexed rows

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/SnapshotCoverage.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/PreviewRow.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolver.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolverTest.kt`

**Interfaces:**
- Consumes: `IndexedPreview.isSnapshotTest`, `IndexedPreview.targets` (Task 3); `testRow(...)` (Task 3).
- Produces:
  - `SnapshotCoverage` — `Covered(val count: Int)`, `Uncovered`, `NotApplicable`
  - `PreviewRow.coverage: SnapshotCoverage` and `PreviewRow.snapshots: List<PreviewRow>`, both defaulted
  - `SnapshotCoverageResolver.resolve(rows: List<T>, modulesWithSnapshots: Set<String>, attach: (T, SnapshotCoverage, List<T>) -> T): Resolved<T>` returning `Resolved(previews: List<T>, orphans: List<T>)`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolverTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.TestPreviewRow
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCoverageResolverTest {

    private fun preview(name: String, targets: List<String>, module: String = "app") =
        testRow(displayName = name, functionName = name, moduleName = module, targets = targets)

    private fun snapshot(name: String, targets: List<String>, module: String = "app") =
        testRow(
            displayName = name,
            functionName = name,
            moduleName = module,
            isSnapshotTest = true,
            targets = targets,
        )

    private fun resolve(rows: List<TestPreviewRow>, modules: Set<String> = setOf("app")) =
        SnapshotCoverageResolver.resolve(rows, modules) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }

    @Test
    fun `a preview sharing a target with a snapshot is covered`() {
        val resolved = resolve(
            listOf(
                preview("ErrorRetryRowPreview", listOf("ErrorRetryRow")),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow")),
            ),
        )
        assertEquals(1, resolved.previews.size)
        assertEquals(SnapshotCoverage.Covered(1), resolved.previews.single().coverage)
        assertEquals(1, resolved.previews.single().snapshots.size)
        assertEquals(emptyList<TestPreviewRow>(), resolved.orphans)
    }

    @Test
    fun `a preview with no matching snapshot is uncovered`() {
        val resolved = resolve(
            listOf(
                preview("MoveProductsBottomSheet_Preview", listOf("MoveProductsBottomSheet")),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow")),
            ),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
        assertEquals("ErrorRetryRow_Default_Snapshot", resolved.orphans.single().indexed.functionName)
    }

    @Test
    fun `snapshots in another module never match`() {
        val resolved = resolve(
            listOf(
                preview("ErrorRetryRowPreview", listOf("ErrorRetryRow"), module = "app"),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow"), module = "other"),
            ),
            modules = setOf("app", "other"),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
    }

    @Test
    fun `several snapshots of one composable are counted`() {
        val resolved = resolve(
            listOf(
                preview("FavoritesContentPreview", listOf("FavoritesContent")),
                snapshot("FavoritesContent_Loading_Snapshot", listOf("FavoritesContent")),
                snapshot("FavoritesContent_Empty_Snapshot", listOf("FavoritesContent")),
            ),
        )
        assertEquals(SnapshotCoverage.Covered(2), resolved.previews.single().coverage)
    }

    @Test
    fun `a module without screenshot testing reports not applicable`() {
        val resolved = resolve(
            listOf(preview("ErrorRetryRowPreview", listOf("ErrorRetryRow"))),
            modules = emptySet(),
        )
        assertEquals(SnapshotCoverage.NotApplicable, resolved.previews.single().coverage)
    }

    @Test
    fun `a preview with no targets is never matched`() {
        val resolved = resolve(
            listOf(
                preview("EmptyPreview", emptyList()),
                snapshot("Whatever_Snapshot", emptyList()),
            ),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
    }
}
```

`testRow` needs `isSnapshotTest` and `targets` parameters and `TestPreviewRow` needs `coverage`/`snapshots`
properties for `copy` to work — extend both in this task if Task 3 left them out.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*SnapshotCoverageResolverTest*"`
Expected: FAIL — `Unresolved reference: SnapshotCoverageResolver`.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/devomer/previewgallery/model/SnapshotCoverage.kt`:

```kotlin
package com.devomer.previewgallery.model

/**
 * Whether the composable a preview shows has a snapshot test.
 *
 * [NotApplicable] is not "unknown": it means the module has no `src/screenshotTest` at all, so the question does
 * not apply and no badge is drawn. Badging those rows would paint a whole project as failing and the signal
 * would be discarded.
 */
sealed interface SnapshotCoverage {
    data class Covered(val count: Int) : SnapshotCoverage
    data object Uncovered : SnapshotCoverage
    data object NotApplicable : SnapshotCoverage
}
```

`model/PreviewRow.kt` — add two defaulted properties so `TestPreviewRow` and the Search Everywhere contributor
are unaffected:

```kotlin
interface PreviewRow {
    val indexed: IndexedPreview
    val moduleName: String

    /** Resolved at query time, not indexed: it is a cross-file relation. */
    val coverage: SnapshotCoverage get() = SnapshotCoverage.NotApplicable

    /** The snapshot rows that show the same composable as this preview. */
    val snapshots: List<PreviewRow> get() = emptyList()
}
```

`src/main/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolver.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * Splits indexed rows into gallery previews and snapshots, and pairs them by the composable both bodies show.
 *
 * Names are deliberately not compared: in the reference corpus `ErrorRetryRowPreview` is snapshotted by
 * `ErrorRetryRow_Default_Snapshot`, and no name-normalisation rule survives all three sampled pairs. Matching is
 * scoped to the module — package equality is too strict, since a module's SDUI renderer snapshots sit in a
 * different package from the composables they render.
 *
 * [attach] is supplied by the caller so this stays free of any concrete row type: production passes a
 * `PreviewEntry.copy`, tests pass a test row's.
 */
object SnapshotCoverageResolver {

    data class Resolved<T : PreviewRow>(val previews: List<T>, val orphans: List<T>)

    fun <T : PreviewRow> resolve(
        rows: List<T>,
        modulesWithSnapshots: Set<String>,
        attach: (row: T, coverage: SnapshotCoverage, snapshots: List<T>) -> T,
    ): Resolved<T> {
        val (snapshots, previews) = rows.partition { it.indexed.isSnapshotTest }
        val matchedSnapshots = HashSet<T>()

        val resolvedPreviews = previews.map { preview ->
            if (preview.moduleName !in modulesWithSnapshots) {
                return@map attach(preview, SnapshotCoverage.NotApplicable, emptyList())
            }
            val targets = preview.indexed.targets.toSet()
            val matching = if (targets.isEmpty()) {
                emptyList()
            } else {
                snapshots.filter { snapshot ->
                    snapshot.moduleName == preview.moduleName &&
                        snapshot.indexed.targets.any { it in targets }
                }
            }
            matchedSnapshots += matching
            val coverage = if (matching.isEmpty()) {
                SnapshotCoverage.Uncovered
            } else {
                SnapshotCoverage.Covered(matching.size)
            }
            attach(preview, coverage, matching)
        }

        return Resolved(resolvedPreviews, snapshots.filter { it !in matchedSnapshots })
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*SnapshotCoverageResolverTest*"`
Expected: PASS, all six tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/ src/main/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolver.kt src/test/kotlin/com/devomer/previewgallery/service/SnapshotCoverageResolverTest.kt src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt
git commit -m "[PG13-6] - Resolve snapshot coverage by shared target"
```

---

### Task 5: Detect screenshot-testing modules and join coverage into the service

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/service/ScreenshotModuleDetector.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/PreviewIndexServiceTest.kt`

**Interfaces:**
- Consumes: `SnapshotCoverageResolver.resolve` (Task 4).
- Produces:
  - `ScreenshotModuleDetector.modulesWithSnapshots(project: Project): Set<String>`
  - `PreviewIndexService.findAll(): List<PreviewEntry>` — gallery previews only, coverage populated
  - `PreviewIndexService.findOrphanSnapshots(): List<PreviewEntry>`

- [ ] **Step 1: Write the failing test**

Append to `PreviewIndexServiceTest.kt` a `BasePlatformTestCase` test that adds a `@Preview` file and a
`@PreviewTest` file whose bodies both call `Widget()`, then asserts:

```kotlin
fun `test snapshot rows do not appear as previews`() {
    myFixture.addFileToProject(
        "src/main/kotlin/com/example/Widgets.kt",
        """
        package com.example

        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        fun WidgetPreview() = PreviewComponent { Widget() }
        """.trimIndent(),
    )
    myFixture.addFileToProject(
        "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
        """
        package com.example

        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
        """.trimIndent(),
    )
    val service = PreviewIndexService.getInstance(project)
    val previews = service.findAll()
    assertEquals(1, previews.size)
    assertEquals("WidgetPreview", previews.single().indexed.functionName)
    assertEquals(1, previews.single().snapshots.size)
}
```

Follow the file's existing conventions for building the fixture and for running under a read action.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*PreviewIndexServiceTest*"`
Expected: FAIL — two rows returned, or `Unresolved reference: snapshots`.

- [ ] **Step 3: Implement**

`ScreenshotModuleDetector`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager

/**
 * The modules that have adopted Compose Preview Screenshot Testing, identified by a `src/screenshotTest`
 * directory under a content root.
 *
 * The directory is looked for on disk rather than in the Gradle model on purpose: the screenshot plugin is
 * applied only under `-Pandroid.experimental.enableScreenshotTest=true`, so a project synced without that flag
 * may not report `screenshotTest` as a source root at all, while the sources are still committed and present.
 */
object ScreenshotModuleDetector {

    private const val SCREENSHOT_TEST_PATH = "src/screenshotTest"

    fun modulesWithSnapshots(project: Project): Set<String> =
        ModuleManager.getInstance(project).modules
            .filter { hasScreenshotTestDirectory(it) }
            .map { it.name }
            .toSet()

    private fun hasScreenshotTestDirectory(module: Module): Boolean =
        ModuleRootManager.getInstance(module).contentRoots.any { root ->
            root.findFileByRelativePath(SCREENSHOT_TEST_PATH)?.isDirectory == true
        }
}
```

`PreviewEntry` — carry the resolved facts:

```kotlin
data class PreviewEntry(
    override val indexed: IndexedPreview,
    override val moduleName: String,
    val file: VirtualFile,
    override val coverage: SnapshotCoverage = SnapshotCoverage.NotApplicable,
    override val snapshots: List<PreviewEntry> = emptyList(),
) : PreviewRow {

    val id: String get() = "${indexed.composableFqn}#${indexed.displayName}"
}
```

`PreviewIndexService` — keep `compute()` as the raw join, add the coverage pass, and cache both halves. Replace
the cached value's type with a small holder:

```kotlin
    private data class Rows(val previews: List<PreviewEntry>, val orphans: List<PreviewEntry>)

    fun findAll(): List<PreviewEntry> = rows().previews

    /** Snapshots that match no preview in their module; the tree shows them under their own branch. */
    fun findOrphanSnapshots(): List<PreviewEntry> = rows().orphans

    private fun rows(): Rows {
        if (DumbService.isDumb(project)) return Rows(emptyList(), emptyList())
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    resolve(compute()),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    private fun resolve(entries: List<PreviewEntry>): Rows {
        val modules = ScreenshotModuleDetector.modulesWithSnapshots(project)
        val resolved = SnapshotCoverageResolver.resolve(entries, modules) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }
        return Rows(resolved.previews, resolved.orphans)
    }
```

`CACHE_KEY`'s type parameter becomes `CachedValue<Rows>`. Sorting stays where it is, in `compute()`, so both
halves come out ordered.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/service/ src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt src/test/kotlin/com/devomer/previewgallery/service/
git commit -m "[PG13-7] - Join snapshot coverage into the index service"
```

---

### Task 6: Give the tree model snapshot rows and an orphan branch

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PackageTreeBuilder.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/ModuleTreeBuilder.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt`

**Interfaces:**
- Consumes: `PreviewRow.snapshots` (Task 4).
- Produces:
  - `PreviewNode.SnapshotLeaf(val row: PreviewRow)`
  - `PreviewNode.OrphanSnapshotBranch(val snapshots: List<SnapshotLeaf>, val count: Int)`
  - `PreviewNode.PreviewLeaf(val row: PreviewRow, val snapshots: List<SnapshotLeaf>)`
  - `PreviewNode.ModuleNode.orphans: OrphanSnapshotBranch?`
  - `PreviewTreeModelBuilder.build(rows: List<T>, orphans: List<T>, query: String): List<ModuleNode>`

- [ ] **Step 1: Write the failing test**

Append to `PreviewTreeModelBuilderTest.kt`:

```kotlin
@Test
fun `a preview carries its snapshots as child leaves`() {
    val snapshot = testRow(
        displayName = "Widget_Default_Snapshot",
        functionName = "Widget_Default_Snapshot",
        isSnapshotTest = true,
        targets = listOf("Widget"),
    )
    val preview = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", targets = listOf("Widget"))
        .copy(snapshots = listOf(snapshot))
    val modules = PreviewTreeModelBuilder.build(listOf(preview), emptyList(), "")
    val leaf = modules.single().branches.single().previews.single()
    assertEquals(1, leaf.snapshots.size)
    assertEquals("Widget_Default_Snapshot", leaf.snapshots.single().row.indexed.functionName)
}

@Test
fun `orphan snapshots land under their module's own branch`() {
    val preview = testRow(moduleName = "app")
    val orphan = testRow(
        displayName = "NoResultRenderer_Snapshot",
        functionName = "NoResultRenderer_Snapshot",
        moduleName = "app",
        isSnapshotTest = true,
        targets = listOf("NoResultRenderer"),
    )
    val modules = PreviewTreeModelBuilder.build(listOf(preview), listOf(orphan), "")
    val orphans = modules.single().orphans
    assertNotNull(orphans)
    assertEquals(1, orphans?.count)
}

@Test
fun `the query filters previews and orphans independently`() {
    val preview = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", moduleName = "app")
    val orphan = testRow(
        displayName = "NoResultRenderer_Snapshot",
        functionName = "NoResultRenderer_Snapshot",
        moduleName = "app",
        isSnapshotTest = true,
    )
    val modules = PreviewTreeModelBuilder.build(listOf(preview), listOf(orphan), "NoResult")
    assertEquals(1, modules.single().orphans?.count)
    assertEquals(0, modules.single().branches.sumOf { it.count })
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*PreviewTreeModelBuilderTest*"`
Expected: FAIL — `No value passed for parameter 'orphans'`.

- [ ] **Step 3: Implement**

`PreviewNode` — add the two node kinds and widen the existing ones. `ModuleNode.count` stays the preview count;
the orphan branch carries its own, so a collapsed module row does not conflate snapshots with previews:

```kotlin
    data class ModuleNode(
        val segment: String,
        val count: Int,
        val modules: List<ModuleNode>,
        val branches: List<PackageBranch>,
        val previews: List<PreviewLeaf>,
        /** Snapshots in this module that match no preview. Null when there are none, so no row is drawn. */
        val orphans: OrphanSnapshotBranch? = null,
    ) : PreviewNode
```

```kotlin
    data class PreviewLeaf(val row: PreviewRow, val snapshots: List<SnapshotLeaf> = emptyList()) : PreviewNode

    /** A `@PreviewTest` function. Selecting it shows reference images, never a live render. */
    data class SnapshotLeaf(val row: PreviewRow) : PreviewNode

    data class OrphanSnapshotBranch(val snapshots: List<SnapshotLeaf>, val count: Int) : PreviewNode
```

`PackageTreeBuilder` — wherever it constructs `PreviewNode.PreviewLeaf(row)`, build the children too:

```kotlin
PreviewNode.PreviewLeaf(row, row.snapshots.map { PreviewNode.SnapshotLeaf(it) })
```

`ModuleTreeBuilder` — take orphans alongside rows, route them by the same segment split, and freeze them onto
the module they belong to:

```kotlin
    fun <T : PreviewRow> build(rows: List<T>, orphans: List<T> = emptyList()): List<PreviewNode.ModuleNode> {
        val roots = LinkedHashMap<String, MutableModule<T>>()

        for (row in rows) {
            moduleFor(roots, row.moduleName)?.rows?.add(row)
        }
        for (orphan in orphans) {
            moduleFor(roots, orphan.moduleName)?.orphans?.add(orphan)
        }

        return stripSharedRoot(freezeAll(roots))
    }

    /** Walks (creating as needed) the segment path of [moduleName]; null when the name has no segments. */
    private fun <T : PreviewRow> moduleFor(
        roots: LinkedHashMap<String, MutableModule<T>>,
        moduleName: String,
    ): MutableModule<T>? {
        val segments = moduleName.split('.', ':').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null // Defensive: a blank module name has no path to nest under.
        var level = roots
        var module: MutableModule<T>? = null
        for (segment in segments) {
            val child = level.getOrPut(segment) { MutableModule(segment) }
            module = child
            level = child.children
        }
        return module
    }
```

`MutableModule` gains `val orphans = mutableListOf<T>()`. In `freeze`, a module holding orphans must not be
compacted away — it needs a row of its own to hang them from — and the branch is built from the sorted orphans:

```kotlin
    private fun <T : PreviewRow> freeze(module: MutableModule<T>, prefix: String): PreviewNode.ModuleNode {
        val label = if (prefix.isEmpty()) module.segment else "$prefix.${module.segment}"
        if (module.rows.isEmpty() && module.orphans.isEmpty() && module.children.size == 1) {
            return freeze(module.children.values.single(), label)
        }

        val modules = freezeAll(module.children)
        val tree = PackageTreeBuilder.build(module.rows)
        val orphanLeaves = module.orphans
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.indexed.displayName })
            .map { PreviewNode.SnapshotLeaf(it) }
        return PreviewNode.ModuleNode(
            segment = label,
            count = module.rows.size + modules.sumOf { it.count },
            modules = modules,
            branches = tree.branches,
            previews = tree.previews,
            orphans = orphanLeaves.takeIf { it.isNotEmpty() }
                ?.let { PreviewNode.OrphanSnapshotBranch(it, it.size) },
        )
    }
```

`stripSharedRoot` also leaves a root alone when it holds orphans: add `if (root.orphans != null) return forest`
next to the existing guards.

`PreviewTreeModelBuilder` — filter both lists with the same query (spec D11: a snapshot name never pulls a
filtered-out preview back in, and orphans are filtered by their own names):

```kotlin
    fun <T : PreviewRow> build(rows: List<T>, orphans: List<T>, query: String): List<PreviewNode.ModuleNode> =
        ModuleTreeBuilder.build(
            PreviewSearchFilter.filter(rows, query),
            PreviewSearchFilter.filter(orphans, query),
        )
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `ModuleTreeBuilderTest` and `PackageTreeBuilderTest` still pass — the new parameters default.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt
git commit -m "[PG13-8] - Model snapshot rows and the orphan branch"
```

---

### Task 7: Draw the badge and the new rows

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt`

**Interfaces:**
- Consumes: `SnapshotCoverage` (Task 4), `PreviewNode.SnapshotLeaf` / `OrphanSnapshotBranch` (Task 6).
- Produces: no new API; rendering only.

Badge copy (spec D5): `· 1 snapshot`, `· 2 snapshots`, `· no snapshot`. Nothing is appended for `NotApplicable`.

- [ ] **Step 1: Write the failing test**

`PreviewTreeCellRendererTest` already has a `render(node): PreviewTreeCellRenderer` helper and a
`fragments(renderer)` helper returning `List<Pair<String, SimpleTextAttributes>>`. Add two more helpers and the
tests:

```kotlin
private fun text(node: PreviewNode): String =
    fragments(render(node)).joinToString("") { it.first }

private fun rowWith(coverage: SnapshotCoverage) = testRow().copy(coverage = coverage)

@Test
fun `a covered preview shows a singular badge`() {
    val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Covered(1))))
    assertTrue(rendered, rendered.contains("· 1 snapshot"))
    assertFalse(rendered, rendered.contains("1 snapshots"))
}

@Test
fun `a covered preview shows a plural badge`() {
    val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Covered(2))))
    assertTrue(rendered, rendered.contains("· 2 snapshots"))
}

@Test
fun `an uncovered preview says so`() {
    val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Uncovered)))
    assertTrue(rendered, rendered.contains("· no snapshot"))
}

@Test
fun `a module without screenshot testing gets no badge`() {
    val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.NotApplicable)))
    assertFalse(rendered, rendered.contains("snapshot"))
}

@Test
fun `a snapshot row shows its function name`() {
    val row = testRow(displayName = "Widget_Default_Snapshot", functionName = "Widget_Default_Snapshot")
    val rendered = text(PreviewNode.SnapshotLeaf(row))
    assertTrue(rendered, rendered.contains("Widget_Default_Snapshot"))
}

@Test
fun `the orphan branch row is labelled and counted`() {
    val leaf = PreviewNode.SnapshotLeaf(testRow())
    val rendered = text(PreviewNode.OrphanSnapshotBranch(listOf(leaf), 1))
    assertTrue(rendered, rendered.contains("Snapshots without a preview"))
    assertTrue(rendered, rendered.contains("(1)"))
}
```

New imports: `com.devomer.previewgallery.model.SnapshotCoverage`, `org.junit.Assert.assertFalse`,
`org.junit.Assert.assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*PreviewTreeCellRendererTest*"`
Expected: FAIL — no badge text, and the `when` is not exhaustive over the new node types.

- [ ] **Step 3: Implement**

In `customizeCellRenderer`, extend the existing `PreviewLeaf` branch after the `private` / `@PreviewParameter`
badges:

```kotlin
                val coverage = coverageText(node.row.coverage)
                if (coverage != null) {
                    append("  $coverage", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
```

and add the two new branches plus the helper:

```kotlin
            is PreviewNode.SnapshotLeaf -> {
                icon = AllIcons.FileTypes.Image
                append(node.row.indexed.functionName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            is PreviewNode.OrphanSnapshotBranch -> {
                icon = AllIcons.Nodes.Folder
                append("Snapshots without a preview", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
```

```kotlin
    /**
     * Text, not an icon alone: a first-time user of the plugin cannot be expected to decode a glyph. Null for
     * [SnapshotCoverage.NotApplicable] — the module has no `src/screenshotTest`, so the row renders as it did
     * before this feature existed.
     */
    private fun coverageText(coverage: SnapshotCoverage): String? = when (coverage) {
        is SnapshotCoverage.Covered ->
            if (coverage.count == 1) "· 1 snapshot" else "· ${coverage.count} snapshots"
        SnapshotCoverage.Uncovered -> "· no snapshot"
        SnapshotCoverage.NotApplicable -> null
    }
```

Both icons must be verified to exist in this SDK before committing — the same discipline the file's own KDoc
records. Compilation is the check that matters here: `AllIcons` is a compile-time constant holder, so a missing
field fails `./gradlew test` at compile time rather than at runtime. If either reference does not compile,
substitute `AllIcons.Nodes.Function` (already used in this file) and note the substitution in the commit message.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*PreviewTreeCellRendererTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRendererTest.kt
git commit -m "[PG13-9] - Draw the coverage badge and snapshot rows"
```

---

### Task 8: Locate the committed reference images

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt`

**Interfaces:**
- Consumes: `IndexedPreview.packageName`, `.jvmClassName`, `.functionName`.
- Produces:
  - `ReferenceImage(val variant: String, val file: VirtualFile)`
  - `ReferenceImageLocator.relativeDirectory(packageName: String, jvmClassName: String): String`
  - `ReferenceImageLocator.variantOf(fileName: String, functionName: String): String?`
  - `ReferenceImageLocator.locate(entry: PreviewEntry, module: Module): List<ReferenceImage>`

Layout, verified against 100 images in the reference corpus:

```
<content root>/src/screenshotTestDebug/reference/<package as dirs>/<JVM facade class>/<function>_<variant>_<hash>_<index>.png
…/favorites/component/ComponentsSnapshotsKt/ErrorRetryRow_Default_Snapshot_phone_eee23ffd_0.png
```

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt` — the two derivation
functions are pure, so plain JUnit 4:

```kotlin
package com.devomer.previewgallery.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceImageLocatorTest {

    @Test
    fun `directory mirrors the package and the facade class`() {
        assertEquals(
            "src/screenshotTestDebug/reference/com/hepsiburada/ui/feature/favorites/component/ComponentsSnapshotsKt",
            ReferenceImageLocator.relativeDirectory(
                packageName = "com.hepsiburada.ui.feature.favorites.component",
                jvmClassName = "com.hepsiburada.ui.feature.favorites.component.ComponentsSnapshotsKt",
            ),
        )
    }

    @Test
    fun `a root package yields no package directories`() {
        assertEquals(
            "src/screenshotTestDebug/reference/SnapshotsKt",
            ReferenceImageLocator.relativeDirectory(packageName = "", jvmClassName = "SnapshotsKt"),
        )
    }

    @Test
    fun `variant is read back out of the file name`() {
        assertEquals(
            "phone",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot_phone_eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
        assertEquals(
            "small",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot_small_72f29e0e_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }

    @Test
    fun `a name belonging to another function is rejected`() {
        assertNull(
            ReferenceImageLocator.variantOf(
                "OtherThing_Default_Snapshot_phone_eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }

    @Test
    fun `a name that does not fit the pattern is rejected rather than mis-parsed`() {
        assertNull(ReferenceImageLocator.variantOf("ErrorRetryRow_Default_Snapshot.png", "ErrorRetryRow_Default_Snapshot"))
        assertNull(ReferenceImageLocator.variantOf("ErrorRetryRow_Default_Snapshot_phone.png", "ErrorRetryRow_Default_Snapshot"))
    }

    @Test
    fun `an unnamed variant is reported as default`() {
        assertEquals(
            "default",
            ReferenceImageLocator.variantOf(
                "ErrorRetryRow_Default_Snapshot__eee23ffd_0.png",
                "ErrorRetryRow_Default_Snapshot",
            ),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*ReferenceImageLocatorTest*"`
Expected: FAIL — `Unresolved reference: ReferenceImageLocator`.

- [ ] **Step 3: Implement**

`model/ReferenceImage.kt`:

```kotlin
package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/** One committed reference PNG of a snapshot: the image the build actually compares against. */
data class ReferenceImage(val variant: String, val file: VirtualFile)
```

`service/ReferenceImageLocator.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager

/**
 * Finds the reference PNGs the Compose Preview Screenshot Testing plugin committed for a snapshot function.
 *
 * The layout is fully derivable from facts the index already holds, so nothing here is stored: the directory
 * mirrors the package and then the JVM facade class, and the file name is
 * `<function>_<variant>_<configuration hash>_<index>.png`. The hash is a property of the `@Preview`
 * configuration, not of the function — `phone` hashes to the same value across every snapshot in the corpus —
 * so it is never computed, only skipped over.
 */
object ReferenceImageLocator {

    private const val REFERENCE_ROOT = "src/screenshotTestDebug/reference"
    private const val PNG_SUFFIX = ".png"
    private const val UNNAMED_VARIANT = "default"

    fun relativeDirectory(packageName: String, jvmClassName: String): String {
        val facade = jvmClassName.substringAfterLast('.')
        val packagePath = packageName.replace('.', '/')
        return if (packagePath.isEmpty()) "$REFERENCE_ROOT/$facade" else "$REFERENCE_ROOT/$packagePath/$facade"
    }

    /**
     * @return the variant segment of [fileName], or null when the name does not belong to [functionName] or does
     * not carry the trailing `_<hash>_<index>` the plugin appends. Rejecting is deliberate: a half-parsed name
     * would label an image with someone else's variant.
     */
    fun variantOf(fileName: String, functionName: String): String? {
        if (!fileName.endsWith(PNG_SUFFIX)) return null
        val stem = fileName.removeSuffix(PNG_SUFFIX)
        val prefix = "${functionName}_"
        if (!stem.startsWith(prefix)) return null
        val rest = stem.removePrefix(prefix).split('_')
        if (rest.size < 3) return null
        return rest.dropLast(2).joinToString("_").ifEmpty { UNNAMED_VARIANT }
    }

    /** Sorted by variant so the strip's left-to-right order is stable across selections. */
    fun locate(entry: PreviewEntry, module: Module): List<ReferenceImage> {
        val relative = relativeDirectory(entry.indexed.packageName, entry.indexed.jvmClassName)
        return ModuleRootManager.getInstance(module).contentRoots
            .mapNotNull { it.findFileByRelativePath(relative) }
            .flatMap { directory -> directory.children.orEmpty().toList() }
            .mapNotNull { file ->
                val variant = variantOf(file.name, entry.indexed.functionName) ?: return@mapNotNull null
                ReferenceImage(variant, file)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.variant })
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*ReferenceImageLocatorTest*"`
Expected: PASS, all six tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/ReferenceImage.kt src/main/kotlin/com/devomer/previewgallery/service/ReferenceImageLocator.kt src/test/kotlin/com/devomer/previewgallery/service/ReferenceImageLocatorTest.kt
git commit -m "[PG13-10] - Locate the committed reference images"
```

---

### Task 9: Show the reference images side by side

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripView.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/ReferenceStripViewTest.kt`

**Interfaces:**
- Consumes: `ReferenceImage` (Task 8), `ZoomMath` (existing — the Phase 12 zoom ladder and fit maths).
- Produces:
  - `ReferenceStripView(images: List<LabelledImage>)` where `LabelledImage(val variant: String, val image: BufferedImage)`
  - `ReferenceStripView.setScale(scale: Double)` / `.fitScale(viewportWidth: Int, viewportHeight: Int): Double`
  - `ReferenceStripView.preferredStripSize(scale: Double): Dimension`

The strip is one component holding every variant at **one shared scale** (spec D7) — per-image scales would make
a 320 dp render and a 411 dp render look identical, which is exactly what the narrow variant exists to catch.
Keep the geometry in testable functions and the painting thin.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/devomer/previewgallery/ui/ReferenceStripViewTest.kt` — geometry only, plain JUnit 4:

```kotlin
package com.devomer.previewgallery.ui

import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceStripViewTest {

    private fun image(width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    private fun strip(vararg sizes: Pair<Int, Int>) = ReferenceStripView(
        sizes.mapIndexed { index, (w, h) ->
            ReferenceStripView.LabelledImage("variant$index", image(w, h))
        },
    )

    @Test
    fun `strip width is the sum of the images plus the gaps`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 1.0)
        assertEquals(100 + 50 + ReferenceStripView.scaledGap(), size.width)
    }

    @Test
    fun `strip height is the tallest image plus the label row`() {
        val size = strip(100 to 200, 50 to 300).preferredStripSize(scale = 1.0)
        assertEquals(300 + ReferenceStripView.scaledLabelHeight(), size.height)
    }

    @Test
    fun `scale multiplies the images but not the label row`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 2.0)
        assertEquals((100 + 50) * 2 + ReferenceStripView.scaledGap(), size.width)
        assertEquals(200 * 2 + ReferenceStripView.scaledLabelHeight(), size.height)
    }

    @Test
    fun `fit uses whichever axis binds`() {
        val wide = strip(1000 to 100)
        assertTrue(wide.fitScale(viewportWidth = 500, viewportHeight = 5000) < 1.0)
        val tall = strip(100 to 1000)
        assertTrue(tall.fitScale(viewportWidth = 5000, viewportHeight = 500) < 1.0)
    }

    @Test
    fun `an empty strip fits at one to one`() {
        assertEquals(1.0, ReferenceStripView(emptyList()).fitScale(100, 100), 0.0001)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*ReferenceStripViewTest*"`
Expected: FAIL — `Unresolved reference: ReferenceStripView`.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripView.kt`. Chrome dimensions — the gap
between images and the label row — go through `JBUI.scale` so the strip is laid out correctly on a HiDPI
display, matching how the rest of the plugin's UI sizes itself. The scaled values are exposed as functions so
the test composes the same geometry the component does rather than restating a hardcoded pixel count. The
images themselves are **not** JBUI-scaled: they are scaled by the user's zoom, which is what `scale` is.

If `JBUI.scale` turns out to need an `Application` and throws in a plain JUnit test, move
`ReferenceStripViewTest` to `BasePlatformTestCase` (backticked `test ` names) rather than dropping the scaling.

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Every reference image of one snapshot, laid out left to right at a single shared scale with its variant name
 * underneath.
 *
 * One scale for the whole strip is the point: the narrow variant exists to catch horizontal overflow, and
 * scaling each image to its own box would render a 320 dp and a 411 dp snapshot at the same apparent width and
 * hide exactly the difference the reader is looking for.
 */
class ReferenceStripView(private val images: List<LabelledImage>) : JComponent() {

    data class LabelledImage(val variant: String, val image: BufferedImage)

    private var scale: Double = 1.0

    fun setScale(scale: Double) {
        this.scale = scale
        preferredSize = preferredStripSize(scale)
        revalidate()
        repaint()
    }

    fun preferredStripSize(scale: Double): Dimension {
        if (images.isEmpty()) return Dimension(0, scaledLabelHeight())
        val width = images.sumOf { (it.image.width * scale).toInt() } + scaledGap() * (images.size - 1)
        val height = images.maxOf { (it.image.height * scale).toInt() } + scaledLabelHeight()
        return Dimension(width, height)
    }

    /** The largest scale at which the whole strip fits [viewportWidth] x [viewportHeight]. */
    fun fitScale(viewportWidth: Int, viewportHeight: Int): Double {
        if (images.isEmpty()) return 1.0
        val naturalWidth = images.sumOf { it.image.width } + scaledGap() * (images.size - 1)
        val naturalHeight = images.maxOf { it.image.height } + scaledLabelHeight()
        if (naturalWidth <= 0 || naturalHeight <= 0) return 1.0
        return minOf(viewportWidth.toDouble() / naturalWidth, viewportHeight.toDouble() / naturalHeight)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            var x = 0
            for (labelled in images) {
                val width = (labelled.image.width * scale).toInt()
                val height = (labelled.image.height * scale).toInt()
                g2.drawImage(labelled.image, x, 0, width, height, null)
                g2.color = JBColor.GRAY
                g2.drawString(labelled.variant, x, height + JBUI.scale(LABEL_BASELINE))
                x += width + scaledGap()
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val GAP = 16
        private const val LABEL_HEIGHT = 20
        private const val LABEL_BASELINE = 14

        /** Horizontal space between two variants, at the display's scale. */
        fun scaledGap(): Int = JBUI.scale(GAP)

        /** Height of the variant-label row under the images, at the display's scale. */
        fun scaledLabelHeight(): Int = JBUI.scale(LABEL_HEIGHT)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*ReferenceStripViewTest*"`
Expected: PASS, all five tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui/ReferenceStripView.kt src/test/kotlin/com/devomer/previewgallery/ui/ReferenceStripViewTest.kt
git commit -m "[PG13-11] - Lay out reference images side by side"
```

---

### Task 10: Wire selection through to the panel

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/render/RenderState.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPanel.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Test: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`

**Interfaces:**
- Consumes: `PreviewNode.SnapshotLeaf` / `OrphanSnapshotBranch` (Task 6), `ReferenceImageLocator.locate` (Task 8), `ReferenceStripView` (Task 9), `PreviewIndexService.findOrphanSnapshots()` (Task 5).
- Produces: no new API; wiring only.

- [ ] **Step 1: Write the failing test**

The panel already exposes `reloadSynchronously()`, `applyQueryForTest(query)` and `visibleRowLabelsForTest()`.
This task adds three more seams in the same style — `childLabelsForTest`, `selectByLabelPathForTest` and
`renderStateForTest` — declared in Step 3. Append to `PreviewGalleryPanelTest.kt`:

```kotlin
private fun projectWithSnapshot() {
    myFixture.addFileToProject(
        "src/main/kotlin/com/example/Widgets.kt",
        """
        package com.example

        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        fun WidgetPreview() = PreviewComponent { Widget() }
        """.trimIndent(),
    )
    myFixture.addFileToProject(
        "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
        """
        package com.example

        import com.android.tools.screenshot.PreviewTest

        @PreviewTest
        fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
        """.trimIndent(),
    )
}

fun `test a snapshot hangs under the preview it corresponds to`() {
    projectWithSnapshot()
    val panel = panel()
    panel.reloadSynchronously()

    val children = panel.childLabelsForTest("WidgetPreview")
    assertEquals(listOf("Widget_Default_Snapshot"), children)
}

fun `test selecting a snapshot without references reports NO_REFERENCE`() {
    projectWithSnapshot()
    val panel = panel()
    panel.reloadSynchronously()

    panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

    // The fixture commits no reference PNGs, and a snapshot is never rendered — so neither RENDERING nor
    // FAILED is correct here.
    assertEquals(RenderState.NO_REFERENCE, panel.renderStateForTest)
}
```

`childLabelsForTest` searches the whole tree for the first node whose label matches, so the test does not depend
on how deeply the module and package levels nest.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*PreviewGalleryPanelTest*"`
Expected: FAIL — no snapshot child node.

- [ ] **Step 3: Implement**

`RenderState` — two new constants:

```kotlin
package com.devomer.previewgallery.render

enum class RenderState { IDLE, RENDERING, LIVE, NEEDS_BUILD, FAILED, UNSUPPORTED, REFERENCE, NO_REFERENCE }
```

`PreviewRenderPanel` — add two state handlers alongside the existing ones:

- `REFERENCE` — replace the view inside the existing `JBScrollPane` with a `ReferenceStripView`, apply
  `fitScale(...)` on the first layout the same way the live render's fit is deferred until the viewport has a
  size (Phase 12), and keep the zoom ladder actions driving `setScale`.
- `NO_REFERENCE` — the message *"No reference images — run `updateDebugScreenshotTest`."* plus the existing
  **Open file** action. Do not offer **Render**: a snapshot is never rendered (spec D8).

Export (Save PNG / Copy) acts on the strip's images the same way it acts on a render: when the active state is
`REFERENCE`, export the currently visible strip.

`PreviewGalleryPanel`:

- `addModule` gains the orphan branch, after the module's own previews:

```kotlin
    private fun addModule(parent: DefaultMutableTreeNode, module: PreviewNode.ModuleNode) {
        val node = DefaultMutableTreeNode(module)
        module.modules.forEach { addModule(node, it) }
        module.branches.forEach { addBranch(node, it) }
        module.previews.forEach { addPreview(node, it) }
        module.orphans?.let { orphans ->
            val orphanNode = DefaultMutableTreeNode(orphans)
            orphans.snapshots.forEach { orphanNode.add(DefaultMutableTreeNode(it)) }
            node.add(orphanNode)
        }
        parent.add(node)
    }
```

- a shared `addPreview` used by both `addModule` and `addBranch`, so a leaf's snapshots hang under it:

```kotlin
    private fun addPreview(parent: DefaultMutableTreeNode, leaf: PreviewNode.PreviewLeaf) {
        val node = DefaultMutableTreeNode(leaf)
        leaf.snapshots.forEach { node.add(DefaultMutableTreeNode(it)) }
        parent.add(node)
    }
```

- the tree rebuild passes orphans through:

```kotlin
PreviewTreeModelBuilder.build(rows, service.findOrphanSnapshots(), query)
```

- the selection listener routes by node type. The existing path reads `PreviewNode.PreviewLeaf` and hands the
  `PreviewEntry` to `RenderPipeline`; add, ahead of it, a `SnapshotLeaf` branch that resolves the module from the
  entry's file via `ProjectFileIndex`, calls `ReferenceImageLocator.locate(...)` off the EDT, and switches the
  render panel to `REFERENCE` (or `NO_REFERENCE` when the list is empty). A `SnapshotLeaf` selection **must not**
  reach `RenderPipeline` — cancel any in-flight render exactly as a preview-to-preview switch does.
- `labelOf` gains the two new node kinds — `SnapshotLeaf` returns `row.indexed.functionName`,
  `OrphanSnapshotBranch` returns `"Snapshots without a preview"` — so label-path lookup and the expansion
  bookkeeping keep working over the widened tree.
- a `PreviewLeaf` with snapshots is no longer a `JTree` leaf. The load-time expansion (module level only) and the
  query-time expansion (everything that survived filtering) must both keep working: a preview row expands to
  reveal its snapshots only when the query expanded its branch, never on plain load.
- selection restore (`findPath`, the id-based capture) keeps matching on `PreviewLeaf` only; a snapshot selection
  is not restored across a rebuild, matching how the panel already treats non-preview rows.
- decoding: convert each `ReferenceImage` to `ReferenceStripView.LabelledImage` with `ImageIO.read(file.inputStream)`
  off the EDT. A variant whose PNG fails to decode is **skipped** and reported in the strip's tooltip — the other
  variants still show (spec's error-handling table). All variants failing is the `NO_REFERENCE` state.
- three test seams, marked with the same `ForTest` suffix convention the file already uses:

```kotlin
    /** Labels of the children of the first node whose own label is [label]; empty when no such node exists. */
    fun childLabelsForTest(label: String): List<String>

    /** Selects the node at the given label path, resolved from the tree root downwards. */
    fun selectByLabelPathForTest(vararg labels: String)

    /** The render panel's current state, so selection routing can be asserted without a live render. */
    val renderStateForTest: RenderState
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/render/RenderState.kt src/main/kotlin/com/devomer/previewgallery/ui/ src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt
git commit -m "[PG13-12] - Show reference images when a snapshot row is selected"
```

---

## Manual verification gate (human)

Automated tests cannot answer the spec's first risk: whether `src/screenshotTest` files are indexed at all when
the consuming project is synced **without** `-Pandroid.experimental.enableScreenshotTest=true`. The fixture in
Task 5 proves the logic, not the real project model.

Run the sandbox against `hepsi-android` and check:

```bash
./gradlew runIde
```

- [ ] `features/favorites/ui` shows coverage badges on its preview rows.
- [ ] `ErrorRetryRowPreview` reads `· 1 snapshot` and has `ErrorRetryRow_Default_Snapshot` as a child row.
- [ ] Selecting that child shows two images side by side, labelled `phone` and `small`.
- [ ] The module's "Snapshots without a preview" branch holds `NoResultRenderer_Snapshot`.
- [ ] A module with no `src/screenshotTest` shows no badges at all.

**This gate was run against `hepsi-android`, and it failed.** No badges appeared anywhere in
`features/favorites/ui` — no snapshot child rows, not even the orphan branch; the whole feature was inert
there. The symptom matched what this note used to predict: the source set was not reaching the index. The
fix is Phase 14, and it goes further than indexing `src/screenshotTest` files by path — it reads and parses
them directly from the VFS, bypassing the index for that source set entirely. See
[2026-07-31-snapshot-source-set-fallback-design.md](../specs/2026-07-31-snapshot-source-set-fallback-design.md)
for the gate's full evidence (including a second, independent module-per-source-set mismatch it also
uncovered), and [2026-07-31-snapshot-source-set-fallback.md](2026-07-31-snapshot-source-set-fallback.md) for
the fix's plan and its own manual verification gate, which repeats this same checklist.
