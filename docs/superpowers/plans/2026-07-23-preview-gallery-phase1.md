# Preview Gallery — Bootstrap + Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the unmodified IntelliJ Platform Plugin Template clone into `Compose Preview Gallery` — an Android Studio plugin that indexes every `@Preview` function in a project and presents it in a searchable tool window with navigation to source.

**Architecture:** A custom `FileBasedIndex` over Kotlin files extracts file-local preview facts; a project service joins those facts with the project model (module, file) at query time; a tool window renders a module → package → preview tree with a search field, a detail panel, and a placeholder for the Phase 2 render surface. All logic that can be pure Kotlin (FQN derivation, annotation matching, search, tree grouping, detail fields) lives outside PSI and Swing so it is unit-testable without an IDE fixture.

**Tech Stack:** Kotlin 2.3.21 · IntelliJ Platform 253 (Android Studio Panda 4, local install) · IntelliJ Platform Gradle Plugin 2.16.0 · Gradle 9.5.0 · JUnit 4 · `BasePlatformTestCase`

**Spec:** [2026-07-23-preview-gallery-phase1-design.md](../specs/2026-07-23-preview-gallery-phase1-design.md)

## Global Constraints

- Package and Gradle group: `com.devomer.previewgallery`. Plugin id: `com.devomer.previewgallery`. Plugin name: `Compose Preview Gallery`.
- Platform: `local(providers.gradleProperty("platformLocalPath"))`, `platformLocalPath = /Users/odurmaz/Applications/Android Studio.app` (build `AI-253.32098.37.2534.15336583`).
- `sinceBuild = "253"`, `untilBuild` left open.
- Kotlin JVM toolchain 21.
- Kotlin Gradle plugin `2.3.21`. The template pins `2.1.20`, but Android Studio 253's bundled Kotlin plugin
  ships module metadata `2.3.0`, which a 2.1 compiler refuses to read (`Module was compiled with an
  incompatible version of Kotlin`). 2.3.x is the lowest line that can read it.
- **Never use the Kotlin `!!` operator.** Use `?:`, `requireNotNull`, `checkNotNull`, or an early return.
- **Tests are written in Task 12, not per task.** Tasks 1-11 implement and must compile; Task 12 writes the
  whole test suite and every test must pass before Task 13.
- Commit message format: `[PG-N] - Task name`, where N is the task number in this plan.
- All documentation, code comments, and commit messages in English.
- The indexer must never resolve references outside the file being indexed.
- `moduleName`, file path, and line number are never stored in the index — they are resolved at query time.

---

### Task 1: Bootstrap — retarget the build and strip the template

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Create: `src/main/kotlin/com/devomer/previewgallery/PreviewGalleryBundle.kt`
- Create: `src/main/resources/messages/PreviewGalleryBundle.properties`
- Delete: `src/main/kotlin/org/jetbrains/plugins/template/` (whole tree), `src/test/kotlin/org/jetbrains/plugins/template/`, `src/test/testData/rename/`, `src/main/resources/messages/MyBundle.properties`
- Modify: `README.md`, `CHANGELOG.md`
- Delete: `.github/workflows/build.yml`, `.github/workflows/release.yml`, `.github/workflows/template-verify.yml`, `.github/workflows/template-cleanup.yml`, `.github/template-cleanup/`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.devomer.previewgallery.PreviewGalleryBundle.message(key: String, vararg params: Any): String` — the message bundle every later task uses for user-visible strings.

- [ ] **Step 1: Rename the project in `settings.gradle.kts`**

Change the project name:

```kotlin
rootProject.name = "preview-gallery"
```

and bump the Kotlin plugin inside `pluginManagement` from `2.1.20` to `2.3.21`:

```kotlin
pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}
```

Android Studio 253 bundles a Kotlin plugin whose module metadata is `2.3.0`; a 2.1 compiler refuses to read it
with `Module was compiled with an incompatible version of Kotlin`. Leave the `plugins` and
`dependencyResolutionManagement` blocks untouched.

- [ ] **Step 2: Rewrite `gradle.properties`**

```properties
group = com.devomer.previewgallery
version = 0.1.0

# Android Studio Panda 4 (AI-253.32098.37.2534.15336583) — the plugin is compiled
# against the exact IDE it runs in, which matters for the Phase 2 render APIs.
platformLocalPath = /Users/odurmaz/Applications/Android Studio.app

# Opt-out flag for bundling Kotlin standard library -> https://jb.gg/intellij-platform-kotlin-stdlib
kotlin.stdlib.default.dependency = false

# Enable Gradle Configuration Cache -> https://docs.gradle.org/current/userguide/configuration_cache.html
org.gradle.configuration-cache = true

# Enable Gradle Build Cache -> https://docs.gradle.org/current/userguide/build_cache.html
org.gradle.caching = true
```

The `pluginRepositoryUrl` property is dropped — it only fed the template's README badges.

- [ ] **Step 3: Rewrite `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugins("org.jetbrains.kotlin", "org.jetbrains.android")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // Left open on purpose: an aggressive untilBuild turns a soft
            // rendering failure into a plugin that refuses to load.
            untilBuild = provider { null }
        }
    }
}
```

If `untilBuild = provider { null }` fails to compile because of type inference, use `untilBuild.set(provider { null })` instead.

- [ ] **Step 4: Verify the platform resolves**

Run: `./gradlew dependencies --configuration intellijPlatformDependency`
Expected: BUILD SUCCESSFUL, with an Android Studio artifact listed. If it fails with a path error, confirm `~/Applications/Android Studio.app` exists and that `platformLocalPath` is the `.app` bundle path, not the `Contents` directory.

- [ ] **Step 5: Delete the template sources**

```bash
git rm -r --quiet src/main/kotlin/org/jetbrains src/test/kotlin/org/jetbrains src/test/testData src/main/resources/messages/MyBundle.properties
git rm -r --quiet .github/workflows .github/template-cleanup
```

The workflows are removed because the build now resolves the platform from a local Android Studio install that does not exist on a GitHub Actions runner (spec §2.4). Re-enabling CI is a Phase 2 decision.

- [ ] **Step 6: Create the message bundle**

`src/main/kotlin/com/devomer/previewgallery/PreviewGalleryBundle.kt`:

```kotlin
package com.devomer.previewgallery

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.PreviewGalleryBundle"

object PreviewGalleryBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
```

`src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
toolwindow.title=Compose Gallery
```

- [ ] **Step 7: Rewrite `plugin.xml`**

`src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.devomer.previewgallery</id>
    <name>Compose Preview Gallery</name>
    <vendor>devomer</vendor>
    <description><![CDATA[
        <p><b>Compose Preview Gallery</b> gives you a project-wide, searchable browser for Jetpack Compose
        <code>@Preview</code> functions.</p>
        <p>Android Studio only shows previews for the file currently open in the editor. In a large multi-module
        codebase there is no way to answer <em>"what components do we have?"</em> without knowing the file path in
        advance. This plugin indexes every preview in the project and lets you find it by name.</p>
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.kotlin</depends>
    <depends>org.jetbrains.android</depends>

    <resource-bundle>messages.PreviewGalleryBundle</resource-bundle>
</idea-plugin>
```

- [ ] **Step 8: Reset `README.md` and `CHANGELOG.md`**

`README.md`:

```markdown
# Compose Preview Gallery

An Android Studio plugin that gives you a project-wide, searchable browser for Jetpack Compose `@Preview`
functions.

## Status

Phase 1 — indexing, search and navigation. Preview rendering arrives in Phase 2.

## Requirements

- Android Studio Panda 4 (platform branch 253)
- JDK 21

## Building

The plugin is compiled against a local Android Studio install. Point `platformLocalPath` in `gradle.properties`
at your own install if it is not at `~/Applications/Android Studio.app`, then:

    ./gradlew build       # compile and run the tests
    ./gradlew runIde      # launch a sandbox IDE with the plugin installed

## Documentation

- [Plugin spec](compose-preview-gallery-plugin-spec.md)
- [Phase 1 design](docs/superpowers/specs/2026-07-23-preview-gallery-phase1-design.md)
- [Phase 1 plan](docs/superpowers/plans/2026-07-23-preview-gallery-phase1.md)
```

`CHANGELOG.md`:

```markdown
# Compose Preview Gallery Changelog

## [Unreleased]

### Added

- Initial project skeleton targeting Android Studio 253.
```

- [ ] **Step 9: Verify the build is green**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. There are no tests yet, so the `test` task reports no test sources — that is expected — the suite arrives in Task 12.

- [ ] **Step 10: Verify no template identifiers survive**

Run: `grep -rn "org.jetbrains.plugins.template\|MyBundle\|MyToolWindow\|MyProjectService\|MyProjectActivity" --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle .`
Expected: no output. Any hit must be fixed before committing (acceptance criterion AC9).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "[PG-1] - Bootstrap plugin project"
```

---

### Task 2: Data model and JVM FQN derivation

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/AnnotationKind.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/model/IndexedPreview.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/model/PreviewRow.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/index/JvmFqnResolver.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class AnnotationKind { ANDROIDX, JETBRAINS, UNKNOWN }`
  - `data class IndexedPreview(displayName, functionName, packageName, jvmClassName, composableFqn, offset, annotationKind, isPrivate, hasPreviewParameter, previewGroup, unsupportedReason)` — all `String` except `offset: Int`, `annotationKind: AnnotationKind`, `isPrivate: Boolean`, `hasPreviewParameter: Boolean`, `previewGroup: String?`, `unsupportedReason: String?`
  - `interface PreviewRow { val indexed: IndexedPreview; val moduleName: String }`
  - `JvmFqnResolver.facadeClassName(fileName: String): String`
  - `JvmFqnResolver.jvmClassName(packageName: String, fileName: String, jvmNameOverride: String?, containerObjectName: String?): String`
  - `JvmFqnResolver.composableFqn(jvmClassName: String, functionName: String): String`

- [ ] **Step 1: Write the model classes**

`src/main/kotlin/com/devomer/previewgallery/model/AnnotationKind.kt`:

```kotlin
package com.devomer.previewgallery.model

/** Which `@Preview` annotation a function carries. `UNKNOWN` means the imports were ambiguous. */
enum class AnnotationKind { ANDROIDX, JETBRAINS, UNKNOWN }
```

`src/main/kotlin/com/devomer/previewgallery/model/IndexedPreview.kt`:

```kotlin
package com.devomer.previewgallery.model

/**
 * File-local facts about one `@Preview` function. Everything here is serialized into the index, so it must not
 * depend on the project model: module membership and file paths are resolved at query time instead.
 */
data class IndexedPreview(
    val displayName: String,
    val functionName: String,
    val packageName: String,
    val jvmClassName: String,
    val composableFqn: String,
    val offset: Int,
    val annotationKind: AnnotationKind,
    val isPrivate: Boolean,
    val hasPreviewParameter: Boolean,
    val previewGroup: String?,
    /** Non-null when the preview cannot be rendered, e.g. because it is declared inside a class. */
    val unsupportedReason: String?,
)
```

`src/main/kotlin/com/devomer/previewgallery/model/PreviewRow.kt`:

```kotlin
package com.devomer.previewgallery.model

/**
 * The subset of a preview that search, grouping and detail rendering need. Keeping this free of `VirtualFile`
 * lets those components be unit-tested without an IDE fixture.
 */
interface PreviewRow {
    val indexed: IndexedPreview
    val moduleName: String
}
```

- [ ] **Step 2: Write the FQN resolver**

`src/main/kotlin/com/devomer/previewgallery/index/JvmFqnResolver.kt`:

```kotlin
package com.devomer.previewgallery.index

/**
 * Derives the JVM class that hosts a Kotlin function, following the rules the Kotlin compiler uses for file
 * facades. Pure string logic on purpose — the PSI extraction lives in [PreviewPsiScanner].
 */
object JvmFqnResolver {

    /** `foo.kt` -> `FooKt`, `foo-bar.kt` -> `Foo_barKt`. */
    fun facadeClassName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val sanitized = buildString {
            base.forEachIndexed { index, char ->
                val valid =
                    if (index == 0) Character.isJavaIdentifierStart(char) else Character.isJavaIdentifierPart(char)
                append(if (valid) char else '_')
            }
        }
        return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }

    /**
     * @param containerObjectName the enclosing top-level `object`, or null for a top-level function.
     * @param jvmNameOverride the value of a `@file:JvmName(...)` annotation, if any. Ignored for object members,
     *   because `@file:JvmName` only renames the file facade.
     */
    fun jvmClassName(
        packageName: String,
        fileName: String,
        jvmNameOverride: String?,
        containerObjectName: String?,
    ): String {
        val simpleName = containerObjectName ?: jvmNameOverride ?: facadeClassName(fileName)
        return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
    }

    fun composableFqn(jvmClassName: String, functionName: String): String = "$jvmClassName.$functionName"
}
```

- [ ] **Step 3: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model src/main/kotlin/com/devomer/previewgallery/index
git commit -m "[PG-2] - Preview data model and JVM FQN derivation"
```

---

### Task 3: Import-driven annotation matching

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/index/ImportInfo.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcher.kt`

**Interfaces:**
- Consumes: `AnnotationKind` from Task 2.
- Produces:
  - `data class ImportInfo(val importedFqn: String, val alias: String?, val isAllUnder: Boolean)`
  - `PreviewAnnotationMatcher.matchPreview(reference: String, imports: List<ImportInfo>): AnnotationKind?` — null means the reference is not a `@Preview` at all
  - `PreviewAnnotationMatcher.isPreviewParameter(reference: String, imports: List<ImportInfo>): Boolean`

`reference` is the annotation's type reference exactly as written in source: `Preview`, `P`, or the fully qualified name.

- [ ] **Step 1: Write the implementation**

`src/main/kotlin/com/devomer/previewgallery/index/ImportInfo.kt`:

```kotlin
package com.devomer.previewgallery.index

/**
 * One import statement, flattened to the facts the matcher needs.
 *
 * @param importedFqn for `import a.b.C` this is `a.b.C`; for `import a.b.*` it is the package `a.b`.
 * @param isAllUnder true for star imports.
 */
data class ImportInfo(
    val importedFqn: String,
    val alias: String?,
    val isAllUnder: Boolean,
)
```

`src/main/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcher.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind

/**
 * Identifies `@Preview` and `@PreviewParameter` annotations from the file's import list alone.
 *
 * A `FileBasedIndex` indexer must not resolve references outside the file it is indexing, so this never touches
 * anything but the text of the annotation reference and the imports declared in the same file.
 */
object PreviewAnnotationMatcher {

    const val ANDROIDX_PREVIEW = "androidx.compose.ui.tooling.preview.Preview"
    const val JETBRAINS_PREVIEW = "org.jetbrains.compose.ui.tooling.preview.Preview"
    const val ANDROIDX_PREVIEW_PARAMETER = "androidx.compose.ui.tooling.preview.PreviewParameter"
    const val JETBRAINS_PREVIEW_PARAMETER = "org.jetbrains.compose.ui.tooling.preview.PreviewParameter"

    private const val PREVIEW_SHORT_NAME = "Preview"
    private const val PREVIEW_PARAMETER_SHORT_NAME = "PreviewParameter"

    /** @return the annotation kind, or null when [reference] is not a Compose `@Preview`. */
    fun matchPreview(reference: String, imports: List<ImportInfo>): AnnotationKind? =
        match(reference, imports, PREVIEW_SHORT_NAME, ANDROIDX_PREVIEW, JETBRAINS_PREVIEW)

    fun isPreviewParameter(reference: String, imports: List<ImportInfo>): Boolean =
        match(
            reference,
            imports,
            PREVIEW_PARAMETER_SHORT_NAME,
            ANDROIDX_PREVIEW_PARAMETER,
            JETBRAINS_PREVIEW_PARAMETER,
        ) != null

    private fun match(
        reference: String,
        imports: List<ImportInfo>,
        shortName: String,
        androidxFqn: String,
        jetbrainsFqn: String,
    ): AnnotationKind? {
        kindOf(reference, androidxFqn, jetbrainsFqn)?.let { return it }
        if (reference.contains('.')) return null

        val boundByName = imports.filter { !it.isAllUnder && it.effectiveName() == reference }
        if (boundByName.isNotEmpty()) {
            val kinds = boundByName.mapNotNull { kindOf(it.importedFqn, androidxFqn, jetbrainsFqn) }.distinct()
            return when (kinds.size) {
                1 -> kinds.single()
                0 -> null // the name is bound to something else entirely
                else -> AnnotationKind.UNKNOWN
            }
        }

        if (reference != shortName) return null

        val starKinds = imports.filter { it.isAllUnder }
            .mapNotNull { kindOf("${it.importedFqn}.$shortName", androidxFqn, jetbrainsFqn) }
            .distinct()
        return if (starKinds.size == 1) starKinds.single() else AnnotationKind.UNKNOWN
    }

    private fun ImportInfo.effectiveName(): String = alias ?: importedFqn.substringAfterLast('.')

    private fun kindOf(fqn: String, androidxFqn: String, jetbrainsFqn: String): AnnotationKind? = when (fqn) {
        androidxFqn -> AnnotationKind.ANDROIDX
        jetbrainsFqn -> AnnotationKind.JETBRAINS
        else -> null
    }
}
```

- [ ] **Step 2: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/index
git commit -m "[PG-3] - Import-driven Preview annotation matching"
```

---

### Task 4: PSI scanner — `KtFile` to `IndexedPreview`

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/index/PreviewPsiScanner.kt`

**Interfaces:**
- Consumes: `JvmFqnResolver`, `PreviewAnnotationMatcher`, `ImportInfo`, `IndexedPreview`, `AnnotationKind`.
- Produces: `PreviewPsiScanner.scan(file: KtFile): List<IndexedPreview>`

- [ ] **Step 1: Write the scanner**

`src/main/kotlin/com/devomer/previewgallery/index/PreviewPsiScanner.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Extracts every directly `@Preview`-annotated function from a single Kotlin file. */
object PreviewPsiScanner {

    private const val UNSUPPORTED_IN_CLASS = "declared inside a class"
    private const val UNSUPPORTED_LOCAL = "declared inside a local scope"
    private const val JVM_NAME = "JvmName"

    fun scan(file: KtFile): List<IndexedPreview> {
        val imports = file.importDirectives.mapNotNull { directive ->
            val fqn = directive.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fqn, directive.aliasName, directive.isAllUnder)
        }
        val packageName = file.packageFqName.asString()
        val jvmNameOverride = file.fileAnnotationList?.annotationEntries
            ?.firstOrNull { it.shortName?.asString() == JVM_NAME }
            ?.let { positionalString(it, 0) }

        val result = mutableListOf<IndexedPreview>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val match = function.annotationEntries.firstNotNullOfOrNull { entry ->
                    val reference = entry.referenceText() ?: return@firstNotNullOfOrNull null
                    PreviewAnnotationMatcher.matchPreview(reference, imports)?.let { entry to it }
                } ?: return
                result += build(function, match.first, match.second, packageName, file.name, jvmNameOverride, imports)
            }
        })
        return result
    }

    private fun build(
        function: KtNamedFunction,
        annotation: KtAnnotationEntry,
        kind: AnnotationKind,
        packageName: String,
        fileName: String,
        jvmNameOverride: String?,
        imports: List<ImportInfo>,
    ): IndexedPreview {
        val container = containerOf(function)
        val functionName = function.name ?: ""
        val jvmClassName = JvmFqnResolver.jvmClassName(
            packageName = packageName,
            fileName = fileName,
            jvmNameOverride = jvmNameOverride,
            containerObjectName = (container as? Container.InObject)?.name,
        )
        val name = namedString(annotation, "name") ?: positionalString(annotation, 0) ?: functionName
        return IndexedPreview(
            displayName = name,
            functionName = functionName,
            packageName = packageName,
            jvmClassName = jvmClassName,
            composableFqn = JvmFqnResolver.composableFqn(jvmClassName, functionName),
            offset = function.nameIdentifier?.textOffset ?: function.textOffset,
            annotationKind = kind,
            isPrivate = function.hasModifier(KtTokens.PRIVATE_KEYWORD),
            hasPreviewParameter = function.valueParameters.any { parameter ->
                parameter.annotationEntries.any { entry ->
                    val reference = entry.referenceText() ?: return@any false
                    PreviewAnnotationMatcher.isPreviewParameter(reference, imports)
                }
            },
            previewGroup = namedString(annotation, "group"),
            unsupportedReason = (container as? Container.Unsupported)?.reason,
        )
    }

    private sealed interface Container {
        data object TopLevel : Container
        data class InObject(val name: String) : Container
        data class Unsupported(val reason: String) : Container
    }

    private fun containerOf(function: KtNamedFunction): Container {
        var current = function.parent
        while (current != null) {
            when (current) {
                is KtFile -> return Container.TopLevel
                is KtClass -> return Container.Unsupported(UNSUPPORTED_IN_CLASS)
                is KtNamedFunction -> return Container.Unsupported(UNSUPPORTED_LOCAL)
                is KtObjectDeclaration -> {
                    val name = current.name
                    val isTopLevelObject = current.parent is KtFile ||
                        (current.parent?.parent is KtFile && current.parent !is KtClass)
                    return if (name != null && isTopLevelObject && !current.isCompanion()) {
                        Container.InObject(name)
                    } else {
                        Container.Unsupported(UNSUPPORTED_IN_CLASS)
                    }
                }
            }
            current = current.parent
        }
        return Container.TopLevel
    }

    /** The annotation's type reference as written, with any type arguments stripped. */
    private fun KtAnnotationEntry.referenceText(): String? =
        typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() }

    private fun namedString(entry: KtAnnotationEntry, name: String): String? =
        entry.valueArguments
            .firstOrNull { it.getArgumentName()?.asName?.asString() == name }
            ?.let { literalOf(it.getArgumentExpression()) }

    private fun positionalString(entry: KtAnnotationEntry, index: Int): String? =
        entry.valueArguments
            .filter { it.getArgumentName() == null }
            .getOrNull(index)
            ?.let { literalOf(it.getArgumentExpression()) }

    /** Only plain string literals are read — anything else would require resolution, which indexers must avoid. */
    private fun literalOf(expression: com.intellij.psi.PsiElement?): String? {
        val template = expression as? KtStringTemplateExpression ?: return null
        val single = template.entries.singleOrNull() as? KtLiteralStringTemplateEntry ?: return null
        return single.text
    }
}
```

The object-container check uses `isTopLevelObject` so that an object nested inside a class or another object falls back to `Unsupported` — its JVM name would need `$` separators, which v1 does not derive.

If `KtObjectDeclaration.parent` turns out to be an object's body rather than the object itself, adjust `isTopLevelObject` until Task 12's `object member uses the object name` and `class member is indexed but unsupported` tests both pass. Do not weaken those tests.

- [ ] **Step 2: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/index/PreviewPsiScanner.kt
git commit -m "[PG-4] - Preview PSI scanner"
```

---

### Task 5: The file-based index

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizer.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/index/PreviewIndex.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: `PreviewPsiScanner.scan`, `IndexedPreview`.
- Produces:
  - `PreviewValueExternalizer : DataExternalizer<List<IndexedPreview>>`
  - `PreviewIndex.NAME: ID<String, List<IndexedPreview>>` — the index id later tasks query, keyed by `composableFqn`

- [ ] **Step 1: Write the externalizer**

`src/main/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizer.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.IOUtil
import java.io.DataInput
import java.io.DataOutput

/**
 * Field-by-field serialization of the index values. Any change to the layout requires bumping
 * [PreviewIndex.VERSION], otherwise stale on-disk data is read with the new layout.
 */
object PreviewValueExternalizer : DataExternalizer<List<IndexedPreview>> {

    override fun save(out: DataOutput, value: List<IndexedPreview>) {
        DataInputOutputUtil.writeINT(out, value.size)
        for (preview in value) {
            IOUtil.writeUTF(out, preview.displayName)
            IOUtil.writeUTF(out, preview.functionName)
            IOUtil.writeUTF(out, preview.packageName)
            IOUtil.writeUTF(out, preview.jvmClassName)
            IOUtil.writeUTF(out, preview.composableFqn)
            DataInputOutputUtil.writeINT(out, preview.offset)
            DataInputOutputUtil.writeINT(out, preview.annotationKind.ordinal)
            out.writeBoolean(preview.isPrivate)
            out.writeBoolean(preview.hasPreviewParameter)
            writeNullable(out, preview.previewGroup)
            writeNullable(out, preview.unsupportedReason)
        }
    }

    override fun read(input: DataInput): List<IndexedPreview> {
        val size = DataInputOutputUtil.readINT(input)
        val result = ArrayList<IndexedPreview>(size)
        repeat(size) {
            val displayName = IOUtil.readUTF(input)
            val functionName = IOUtil.readUTF(input)
            val packageName = IOUtil.readUTF(input)
            val jvmClassName = IOUtil.readUTF(input)
            val composableFqn = IOUtil.readUTF(input)
            val offset = DataInputOutputUtil.readINT(input)
            val kindOrdinal = DataInputOutputUtil.readINT(input)
            val isPrivate = input.readBoolean()
            val hasPreviewParameter = input.readBoolean()
            val previewGroup = readNullable(input)
            val unsupportedReason = readNullable(input)
            result += IndexedPreview(
                displayName = displayName,
                functionName = functionName,
                packageName = packageName,
                jvmClassName = jvmClassName,
                composableFqn = composableFqn,
                offset = offset,
                annotationKind = AnnotationKind.entries.getOrElse(kindOrdinal) { AnnotationKind.UNKNOWN },
                isPrivate = isPrivate,
                hasPreviewParameter = hasPreviewParameter,
                previewGroup = previewGroup,
                unsupportedReason = unsupportedReason,
            )
        }
        return result
    }

    private fun writeNullable(out: DataOutput, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) IOUtil.writeUTF(out, value)
    }

    private fun readNullable(input: DataInput): String? =
        if (input.readBoolean()) IOUtil.readUTF(input) else null
}
```

Fields are read into locals before the constructor call so the read order is explicit rather than dependent on argument evaluation order.

- [ ] **Step 2: Write the index**

`src/main/kotlin/com/devomer/previewgallery/index/PreviewIndex.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.PsiDependentIndex
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Persistent, incremental index of every directly `@Preview`-annotated function, keyed by its composable FQN.
 *
 * Only file-local facts are stored. Module membership is a project-model property, so storing it here would let a
 * Gradle sync invalidate correctness without invalidating the index.
 */
class PreviewIndex : FileBasedIndexExtension<String, List<IndexedPreview>>(), PsiDependentIndex {

    override fun getName(): ID<String, List<IndexedPreview>> = NAME

    override fun getVersion(): Int = VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<IndexedPreview>> = PreviewValueExternalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.extension == KOTLIN_EXTENSION }

    override fun getIndexer(): DataIndexer<String, List<IndexedPreview>, FileContent> =
        DataIndexer { content ->
            if (!content.contentAsText.contains(MARKER)) return@DataIndexer emptyMap()
            val ktFile = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
            PreviewPsiScanner.scan(ktFile).groupBy { it.composableFqn }
        }

    companion object {
        val NAME: ID<String, List<IndexedPreview>> = ID.create("com.devomer.previewgallery.PreviewIndex")

        /** Bump on any change to [PreviewValueExternalizer] or to what the scanner produces. */
        const val VERSION = 1

        private const val KOTLIN_EXTENSION = "kt"

        /** Cheap text gate: files that never mention Preview are skipped before PSI is built. */
        private const val MARKER = "Preview"
    }
}
```

- [ ] **Step 3: Register the index in `plugin.xml`**

Add before the closing `</idea-plugin>` tag:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <fileBasedIndex implementation="com.devomer.previewgallery.index.PreviewIndex"/>
    </extensions>
```

- [ ] **Step 4: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/index src/main/resources/META-INF/plugin.xml
git commit -m "[PG-5] - Preview file-based index"
```

---

### Task 6: Query service

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt`

**Interfaces:**
- Consumes: `PreviewIndex.NAME`, `IndexedPreview`, `PreviewRow`.
- Produces:
  - `data class PreviewEntry(override val indexed: IndexedPreview, override val moduleName: String, val file: VirtualFile) : PreviewRow` with `val id: String`
  - `PreviewIndexService.getInstance(project: Project): PreviewIndexService`
  - `PreviewIndexService.findAll(): List<PreviewEntry>` — must be called under a read action, off the EDT
  - `PreviewIndexService.refresh()` — invalidates the cache

- [ ] **Step 1: Write `PreviewEntry`**

`src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt`:

```kotlin
package com.devomer.previewgallery.model

import com.intellij.openapi.vfs.VirtualFile

/** An index value joined with the project-model context resolved at query time. */
data class PreviewEntry(
    override val indexed: IndexedPreview,
    override val moduleName: String,
    val file: VirtualFile,
) : PreviewRow {

    val id: String get() = "${indexed.composableFqn}#${indexed.displayName}"
}
```

- [ ] **Step 2: Write the service**

`src/main/kotlin/com/devomer/previewgallery/service/PreviewIndexService.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.devomer.previewgallery.index.PreviewIndex
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex

/**
 * Reads [PreviewIndex] and joins each value with the module and file it belongs to.
 *
 * Callers must invoke [findAll] under a read action and off the EDT — it touches the index and the project model.
 */
@Service(Service.Level.PROJECT)
class PreviewIndexService(private val project: Project) {

    private val refreshTracker = SimpleModificationTracker()

    fun findAll(): List<PreviewEntry> {
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            {
                CachedValueProvider.Result.create(
                    compute(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    refreshTracker,
                )
            },
            false,
        )
    }

    /** Forces the next [findAll] to recompute, for project-model changes that raise no PSI event. */
    fun refresh() {
        refreshTracker.incModificationCount()
    }

    private fun compute(): List<PreviewEntry> {
        val index = FileBasedIndex.getInstance()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val entries = mutableListOf<PreviewEntry>()

        index.processAllKeys(PreviewIndex.NAME, { key ->
            index.processValues(PreviewIndex.NAME, key, null, { file, values ->
                val module = fileIndex.getModuleForFile(file)
                if (module != null) {
                    values.forEach { entries += PreviewEntry(it, module.name, file) }
                }
                true
            }, scope)
            true
        }, project)

        return entries.sortedWith(
            compareBy({ it.moduleName }, { it.indexed.packageName }, { it.indexed.displayName }),
        )
    }

    companion object {
        private val CACHE_KEY = Key.create<CachedValue<List<PreviewEntry>>>("com.devomer.previewgallery.entries")

        fun getInstance(project: Project): PreviewIndexService = project.service()
    }
}
```

- [ ] **Step 3: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/model/PreviewEntry.kt src/main/kotlin/com/devomer/previewgallery/service
git commit -m "[PG-6] - Preview index query service"
```

---

### Task 7: Search filter and module filter

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/search/PreviewSearchFilter.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/search/PreviewModuleFilter.kt`

**Interfaces:**
- Consumes: `PreviewRow`, `IndexedPreview`, `AnnotationKind`.
- Produces:
  - `PreviewSearchFilter.matches(row: PreviewRow, query: String): Boolean`
  - `PreviewSearchFilter.filter(rows: List<T>, query: String): List<T>` for `T : PreviewRow`
  - `PreviewModuleFilter.apply(rows: List<T>, activeModuleName: String?, enabled: Boolean): List<T>` for `T : PreviewRow`

- [ ] **Step 1: Write the filters**

`src/main/kotlin/com/devomer/previewgallery/search/PreviewSearchFilter.kt`:

```kotlin
package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow

/**
 * Case-insensitive substring search over the preview name, function name and package.
 *
 * A plain scan is enough: the reference corpus is under 1000 entries and the tool window filters below 10k.
 */
object PreviewSearchFilter {

    fun matches(row: PreviewRow, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val indexed = row.indexed
        return indexed.displayName.contains(trimmed, ignoreCase = true) ||
            indexed.functionName.contains(trimmed, ignoreCase = true) ||
            indexed.packageName.contains(trimmed, ignoreCase = true)
    }

    fun <T : PreviewRow> filter(rows: List<T>, query: String): List<T> = rows.filter { matches(it, query) }
}
```

`src/main/kotlin/com/devomer/previewgallery/search/PreviewModuleFilter.kt`:

```kotlin
package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow

/** Restricts rows to the module of the file currently open in the editor. */
object PreviewModuleFilter {

    fun <T : PreviewRow> apply(rows: List<T>, activeModuleName: String?, enabled: Boolean): List<T> = when {
        !enabled -> rows
        activeModuleName == null -> emptyList()
        else -> rows.filter { it.moduleName == activeModuleName }
    }
}
```

- [ ] **Step 2: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/search
git commit -m "[PG-7] - Preview search and module filters"
```

---

### Task 8: Tree model and detail model

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewDetailModel.kt`

**Interfaces:**
- Consumes: `PreviewRow`, `PreviewSearchFilter`.
- Produces:
  - `sealed interface PreviewNode` with `ModuleNode(moduleName, count, packages)`, `PackageNode(packageName, previews)`, `PreviewLeaf(row)`
  - `PreviewTreeModelBuilder.build(rows: List<T>, query: String): List<PreviewNode.ModuleNode>` for `T : PreviewRow`
  - `data class DetailField(val label: String, val value: String)`
  - `PreviewDetailModel.fields(row: PreviewRow, fileName: String, line: Int?): List<DetailField>`

- [ ] **Step 1: Write the node types and the builder**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewNode.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow

/** A Swing-free tree shape, so grouping can be tested without a `JTree`. */
sealed interface PreviewNode {

    data class ModuleNode(
        val moduleName: String,
        val count: Int,
        val packages: List<PackageNode>,
    ) : PreviewNode

    data class PackageNode(
        val packageName: String,
        val previews: List<PreviewLeaf>,
    ) : PreviewNode

    data class PreviewLeaf(val row: PreviewRow) : PreviewNode
}
```

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilder.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.search.PreviewSearchFilter

/** Builds the module -> package -> preview tree. Module counts reflect the filtered result, not the whole project. */
object PreviewTreeModelBuilder {

    fun <T : PreviewRow> build(rows: List<T>, query: String): List<PreviewNode.ModuleNode> =
        PreviewSearchFilter.filter(rows, query)
            .groupBy { it.moduleName }
            .toSortedMap()
            .map { (moduleName, moduleRows) ->
                val packages = moduleRows
                    .groupBy { it.indexed.packageName }
                    .toSortedMap()
                    .map { (packageName, packageRows) ->
                        PreviewNode.PackageNode(
                            packageName = packageName,
                            previews = packageRows
                                .sortedBy { it.indexed.displayName }
                                .map { PreviewNode.PreviewLeaf(it) },
                        )
                    }
                PreviewNode.ModuleNode(moduleName, moduleRows.size, packages)
            }
}
```

- [ ] **Step 2: Write the detail model**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewDetailModel.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.PreviewRow

data class DetailField(val label: String, val value: String)

/** The label/value pairs the detail panel renders. Kept free of Swing so it can be asserted directly. */
object PreviewDetailModel {

    /** @param line zero-based, as `Document.getLineNumber` returns it; displayed one-based. */
    fun fields(row: PreviewRow, fileName: String, line: Int?): List<DetailField> {
        val indexed = row.indexed
        val fields = mutableListOf(
            DetailField("Name", indexed.displayName),
            DetailField("Function", indexed.functionName),
            DetailField("FQN", indexed.composableFqn),
            DetailField("Module", row.moduleName),
            DetailField("File", if (line == null) fileName else "$fileName:${line + 1}"),
            DetailField(
                "Annotation",
                when (indexed.annotationKind) {
                    AnnotationKind.ANDROIDX -> "androidx"
                    AnnotationKind.JETBRAINS -> "org.jetbrains"
                    AnnotationKind.UNKNOWN -> "unknown"
                },
            ),
        )
        if (indexed.isPrivate) fields += DetailField("Private", "yes")
        if (indexed.hasPreviewParameter) fields += DetailField("PreviewParameter", "yes")
        indexed.previewGroup?.let { fields += DetailField("Group", it) }
        indexed.unsupportedReason?.let { fields += DetailField("Unsupported", it) }
        return fields
    }
}
```

- [ ] **Step 3: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui
git commit -m "[PG-8] - Preview tree and detail models"
```

---

### Task 9: Tool window

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryToolWindowFactory.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewDetailPanel.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPlaceholder.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `PreviewIndexService`, `PreviewTreeModelBuilder`, `PreviewDetailModel`, `PreviewSearchFilter`, `PreviewGalleryBundle`.
- Produces:
  - `PreviewGalleryToolWindowFactory.ID: String = "Compose Gallery"`
  - `class PreviewGalleryPanel(project: Project, parentDisposable: Disposable) : JBPanel<PreviewGalleryPanel>` with `fun reload()`, `fun selectEntry(entryId: String)`, and `val state: PreviewGalleryPanel.State`
  - `enum class PreviewGalleryPanel.State { INDEXING, NO_PREVIEWS, NO_MATCH, LOADED }`

- [ ] **Step 1: Add the message keys**

Append to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
state.indexing=Waiting for indexing to finish…
state.noPreviews=No @Preview functions found in this project
state.noMatch=No preview matches ''{0}''
detail.empty=Select a preview to see its details
detail.openFile=Open file
detail.copyFqn=Copy FQN
render.placeholder=Preview rendering arrives in Phase 2
search.hint=Search previews
```

- [ ] **Step 2: Write the placeholder and detail panels**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewRenderPlaceholder.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import java.awt.Graphics

/** The lower half of the tool window. Phase 2 replaces its contents with the rendered image. */
class PreviewRenderPlaceholder : JBPanel<PreviewRenderPlaceholder>() {

    private val emptyText = object : StatusText(this) {
        override fun isStatusVisible(): Boolean = true
    }.apply { text = PreviewGalleryBundle.message("render.placeholder") }

    init {
        border = JBUI.Borders.empty()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        emptyText.paint(this, g)
    }
}
```

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewDetailPanel.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection

class PreviewDetailPanel(private val project: Project) : JBPanel<PreviewDetailPanel>(GridBagLayout()) {

    private var entry: PreviewEntry? = null

    init {
        border = JBUI.Borders.empty(8)
        showEmpty()
    }

    fun show(entry: PreviewEntry?) {
        this.entry = entry
        removeAll()
        if (entry == null) showEmpty() else showEntry(entry)
        revalidate()
        repaint()
    }

    private fun showEmpty() {
        add(JBLabel(PreviewGalleryBundle.message("detail.empty")).apply { foreground = UIUtil.getInactiveTextColor() })
    }

    private fun showEntry(entry: PreviewEntry) {
        val line = runCatching {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getDocument(entry.file)
                ?.getLineNumber(entry.indexed.offset)
        }.getOrNull()

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 8)
        }
        PreviewDetailModel.fields(entry, entry.file.name, line).forEach { field ->
            constraints.gridx = 0
            add(JBLabel("${field.label}:").apply { foreground = UIUtil.getInactiveTextColor() }, constraints)
            constraints.gridx = 1
            add(JBLabel(field.value), constraints)
            constraints.gridy++
        }

        constraints.gridx = 0
        constraints.gridwidth = 2
        add(ActionLink(PreviewGalleryBundle.message("detail.openFile")) { navigate(entry) }, constraints)
        constraints.gridy++
        add(
            ActionLink(PreviewGalleryBundle.message("detail.copyFqn")) {
                CopyPasteManager.getInstance().setContents(StringSelection(entry.indexed.composableFqn))
            },
            constraints,
        )
    }

    private fun navigate(entry: PreviewEntry) {
        OpenFileDescriptor(project, entry.file, entry.indexed.offset).navigate(true)
    }
}
```

- [ ] **Step 3: Write the tree cell renderer**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewTreeCellRenderer.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class PreviewTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject as? PreviewNode ?: return
        when (node) {
            is PreviewNode.ModuleNode -> {
                append(node.moduleName)
                append("  (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is PreviewNode.PackageNode -> append(node.packageName)

            is PreviewNode.PreviewLeaf -> {
                val indexed = node.row.indexed
                append(indexed.displayName)
                if (indexed.displayName != indexed.functionName) {
                    append("  ${indexed.functionName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                val badges = buildList {
                    if (indexed.isPrivate) add("private")
                    if (indexed.hasPreviewParameter) add("@PreviewParameter")
                    if (indexed.unsupportedReason != null) add("unsupported")
                }
                if (badges.isNotEmpty()) {
                    append("  ${badges.joinToString(" · ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Write the panel**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class PreviewGalleryPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JBPanel<PreviewGalleryPanel>(BorderLayout()) {

    enum class State { INDEXING, NO_PREVIEWS, NO_MATCH, LOADED }

    var state: State = State.INDEXING
        private set

    private val searchField = SearchTextField()
    private val treeRoot = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)
    private val detailPanel = PreviewDetailPanel(project)
    private val statusLabel = com.intellij.ui.components.JBLabel()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var entries: List<PreviewEntry> = emptyList()

    /** Set by [PreviewGalleryToolWindowFactory]; consumed by the module filter action in Task 10. */
    var moduleFilter: (List<PreviewEntry>) -> List<PreviewEntry> = { it }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = PreviewTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { detailPanel.show(selectedEntry()) }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) event.consume()
            }
        })

        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ applyFilter() }, SEARCH_DEBOUNCE_MS)
            }
        })

        val treeSide = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        val upper = OnePixelSplitter(false, "PreviewGallery.horizontal", 0.55f).apply {
            firstComponent = treeSide
            secondComponent = JBScrollPane(detailPanel)
        }
        val outer = OnePixelSplitter(true, "PreviewGallery.vertical", 0.6f).apply {
            firstComponent = upper
            secondComponent = PreviewRenderPlaceholder()
        }

        statusLabel.border = JBUI.Borders.empty(8)
        add(outer, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        reload()
    }

    /** Reloads the index off the EDT. Safe to call repeatedly. */
    fun reload() {
        if (DumbService.isDumb(project)) {
            setState(State.INDEXING)
            DumbService.getInstance(project).runWhenSmart { reload() }
            return
        }
        ReadAction.nonBlocking<List<PreviewEntry>> { PreviewIndexService.getInstance(project).findAll() }
            .expireWith(parentDisposable)
            .finishOnUiThread(ModalityState.defaultModalityState()) { loaded ->
                entries = loaded
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun selectEntry(entryId: String) {
        val path = findPath(entryId) ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun applyFilter() {
        val visible = moduleFilter(entries)
        val modules = PreviewTreeModelBuilder.build(visible, searchField.text)
        treeRoot.removeAllChildren()
        modules.forEach { module ->
            val moduleNode = DefaultMutableTreeNode(module)
            module.packages.forEach { pkg ->
                val packageNode = DefaultMutableTreeNode(pkg)
                pkg.previews.forEach { packageNode.add(DefaultMutableTreeNode(it)) }
                moduleNode.add(packageNode)
            }
            treeRoot.add(moduleNode)
        }
        treeModel.reload()
        expandAll()
        detailPanel.show(selectedEntry())

        setState(
            when {
                entries.isEmpty() -> State.NO_PREVIEWS
                modules.isEmpty() -> State.NO_MATCH
                else -> State.LOADED
            },
        )
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun setState(newState: State) {
        state = newState
        statusLabel.text = when (newState) {
            State.INDEXING -> PreviewGalleryBundle.message("state.indexing")
            State.NO_PREVIEWS -> PreviewGalleryBundle.message("state.noPreviews")
            State.NO_MATCH -> PreviewGalleryBundle.message("state.noMatch", searchField.text)
            State.LOADED -> ""
        }
        statusLabel.isVisible = newState != State.LOADED
    }

    private fun selectedEntry(): PreviewEntry? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
    }

    private fun navigateToSelection(): Boolean {
        val entry = selectedEntry() ?: return false
        OpenFileDescriptor(project, entry.file, entry.indexed.offset).navigate(true)
        return true
    }

    private fun findPath(entryId: String): TreePath? {
        val moduleNodes = treeRoot.children().toList().filterIsInstance<DefaultMutableTreeNode>()
        for (moduleNode in moduleNodes) {
            for (packageNode in moduleNode.children().toList().filterIsInstance<DefaultMutableTreeNode>()) {
                for (leafNode in packageNode.children().toList().filterIsInstance<DefaultMutableTreeNode>()) {
                    val entry = (leafNode.userObject as? PreviewNode.PreviewLeaf)?.row as? PreviewEntry
                    if (entry?.id == entryId) return TreePath(leafNode.path)
                }
            }
        }
        return null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150
    }
}
```

- [ ] **Step 5: Write the tool window factory**

`src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryToolWindowFactory.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * `DumbAware` on purpose: the tool window opens during indexing and shows the INDEXING state, then reloads once
 * the IDE is smart again.
 */
class PreviewGalleryToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PreviewGalleryPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Compose Gallery"
    }
}
```

- [ ] **Step 6: Register the tool window in `plugin.xml`**

Inside the existing `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
        <toolWindow id="Compose Gallery"
                    anchor="right"
                    factoryClass="com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory"/>
```

- [ ] **Step 7: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui src/main/resources
git commit -m "[PG-9] - Preview gallery tool window"
```

---

### Task 10: Module filter toggle and refresh action

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ActiveModuleTracker.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/ui/RefreshAction.kt`
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `PreviewGalleryPanel`, `PreviewModuleFilter`, `PreviewIndexService.refresh`.
- Produces:
  - `class ActiveModuleTracker(project: Project, parentDisposable: Disposable, onChange: () -> Unit)` with `val activeModuleName: String?`
  - `PreviewGalleryPanel.isModuleFilterEnabled: Boolean` (persisted via `PropertiesComponent`)

- [ ] **Step 1: Write the tracker**

`src/main/kotlin/com/devomer/previewgallery/ui/ActiveModuleTracker.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/** Tracks the module of the file currently open in the editor. */
class ActiveModuleTracker(
    private val project: Project,
    parentDisposable: Disposable,
    private val onChange: () -> Unit,
) {

    val activeModuleName: String?
        get() {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
            return ModuleUtilCore.findModuleForPsiElement(psiFile)?.name
        }

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = onChange()
            },
        )
    }
}
```

- [ ] **Step 2: Add the message keys**

Append to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
action.moduleFilter.text=Show only the active editor's module
action.refresh.text=Refresh
state.noActiveModule=No file open — the module filter has nothing to show
```

- [ ] **Step 3: Write the actions**

`src/main/kotlin/com/devomer/previewgallery/ui/ModuleFilterToggleAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ide.util.PropertiesComponent

class ModuleFilterToggleAction(
    private val project: Project,
    private val onToggle: () -> Unit,
) : ToggleAction(
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    PreviewGalleryBundle.message("action.moduleFilter.text"),
    AllIcons.General.Filter,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(event: AnActionEvent): Boolean = isEnabled(project)

    override fun setSelected(event: AnActionEvent, selected: Boolean) {
        PropertiesComponent.getInstance(project).setValue(KEY, selected)
        onToggle()
    }

    companion object {
        private const val KEY = "com.devomer.previewgallery.moduleFilter"

        fun isEnabled(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(KEY, false)
    }
}
```

`src/main/kotlin/com/devomer/previewgallery/ui/RefreshAction.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class RefreshAction(
    private val project: Project,
    private val onRefresh: () -> Unit,
) : AnAction(
    PreviewGalleryBundle.message("action.refresh.text"),
    PreviewGalleryBundle.message("action.refresh.text"),
    AllIcons.Actions.Refresh,
),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        PreviewIndexService.getInstance(project).refresh()
        onRefresh()
    }
}
```

- [ ] **Step 4: Wire the actions into the panel**

In `PreviewGalleryPanel`, replace the `moduleFilter` property with a tracker plus a toolbar. Add these imports:

```kotlin
import com.devomer.previewgallery.search.PreviewModuleFilter
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
```

Replace:

```kotlin
    /** Set by [PreviewGalleryToolWindowFactory]; consumed by the module filter action in Task 10. */
    var moduleFilter: (List<PreviewEntry>) -> List<PreviewEntry> = { it }
```

with:

```kotlin
    private val moduleTracker = ActiveModuleTracker(project, parentDisposable) { applyFilter() }
```

In the `init` block, before `add(outer, BorderLayout.CENTER)`, insert:

```kotlin
        val actionGroup = DefaultActionGroup(
            RefreshAction(project) { reload() },
            ModuleFilterToggleAction(project) { applyFilter() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("PreviewGallery", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
```

In `applyFilter`, replace the first line:

```kotlin
        val visible = moduleFilter(entries)
```

with:

```kotlin
        val visible = PreviewModuleFilter.apply(
            entries,
            moduleTracker.activeModuleName,
            ModuleFilterToggleAction.isEnabled(project),
        )
```

- [ ] **Step 5: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/ui src/main/resources/messages
git commit -m "[PG-10] - Module filter toggle and refresh action"
```

---

### Task 11: SearchEverywhere contributor

**Files:**
- Create: `src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributor.kt`
- Create: `src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/PreviewGalleryBundle.properties`

**Interfaces:**
- Consumes: `PreviewIndexService`, `PreviewSearchFilter`, `PreviewEntry`, `PreviewGalleryToolWindowFactory.ID`, `PreviewGalleryPanel.selectEntry`.
- Produces: a `SearchEverywhereContributor<PreviewEntry>` registered on `com.intellij.searchEverywhereContributor`.

- [ ] **Step 1: Add the message key**

Append to `src/main/resources/messages/PreviewGalleryBundle.properties`:

```properties
searcheverywhere.group=Compose Previews
```

- [ ] **Step 2: Write the contributor**

`src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributor.kt`:

```kotlin
package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.search.PreviewSearchFilter
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.ui.PreviewGalleryPanel
import com.devomer.previewgallery.ui.PreviewGalleryToolWindowFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.Processor
import javax.swing.ListCellRenderer

class PreviewSearchEverywhereContributor(private val project: Project) :
    SearchEverywhereContributor<PreviewEntry> {

    override fun getSearchProviderId(): String = javaClass.name

    override fun getGroupName(): String = PreviewGalleryBundle.message("searcheverywhere.group")

    override fun getSortWeight(): Int = SORT_WEIGHT

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = false

    override fun fetchElements(
        pattern: String,
        indicator: ProgressIndicator,
        consumer: Processor<in PreviewEntry>,
    ) {
        if (DumbService.isDumb(project)) return
        val entries = ReadAction.compute<List<PreviewEntry>, RuntimeException> {
            PreviewIndexService.getInstance(project).findAll()
        }
        for (entry in PreviewSearchFilter.filter(entries, pattern)) {
            indicator.checkCanceled()
            if (!consumer.process(entry)) return
        }
    }

    override fun getElementsRenderer(): ListCellRenderer<in PreviewEntry> =
        SimpleListCellRenderer.create("") { entry ->
            "${entry.indexed.displayName}  —  ${entry.moduleName} · ${entry.indexed.packageName}"
        }

    override fun processSelectedItem(selected: PreviewEntry, modifiers: Int, searchText: String): Boolean {
        OpenFileDescriptor(project, selected.file, selected.indexed.offset).navigate(true)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(PreviewGalleryToolWindowFactory.ID)
        toolWindow?.activate({
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? PreviewGalleryPanel }
            panel?.selectEntry(selected.id)
        }, false)
        return true
    }

    override fun getDataForItem(element: PreviewEntry, dataId: String): Any? = null

    private companion object {
        const val SORT_WEIGHT = 900
    }
}
```

`src/main/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorFactory.kt`:

```kotlin
package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent

class PreviewSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<PreviewEntry> {

    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<PreviewEntry> {
        val project = requireNotNull(initEvent.project) { "Preview gallery search requires an open project" }
        return PreviewSearchEverywhereContributor(project)
    }
}
```

If `SimpleListCellRenderer.create` does not accept that lambda shape on platform 253, replace
`getElementsRenderer()` with an explicit renderer:

```kotlin
    override fun getElementsRenderer(): ListCellRenderer<in PreviewEntry> =
        ListCellRenderer { _, value, _, _, _ ->
            com.intellij.ui.components.JBLabel(
                "${value.indexed.displayName}  —  ${value.moduleName} · ${value.indexed.packageName}",
            )
        }
```

- [ ] **Step 3: Register the contributor in `plugin.xml`**

Inside the `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
        <searchEverywhereContributor
            implementation="com.devomer.previewgallery.searcheverywhere.PreviewSearchEverywhereContributorFactory"/>
```

- [ ] **Step 4: Verify it compiles**
Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/devomer/previewgallery/searcheverywhere src/main/resources
git commit -m "[PG-11] - SearchEverywhere contributor"
```

---

### Task 12: Test suite

All tests for Tasks 2–11 are written here, after the implementation is complete and compiling.

**Files:**
- Modify: `src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/index/JvmFqnResolverTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcherTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/index/PreviewIndexTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/index/PreviewPsiScannerTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizerTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/search/PreviewSearchFilterTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/service/PreviewIndexServiceTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/ActiveModuleTrackerTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewDetailModelTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`
- Create: `src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt`

**Interfaces:**
- Consumes: every type produced by Tasks 2–11.
- Produces: `PreviewGalleryPanel.reloadSynchronously()` and `PreviewGalleryPanel.applyQueryForTest(query: String)`, both `@TestOnly`.

- [ ] **Step 1: Add the test-only panel helpers**

`PreviewGalleryPanel` reloads asynchronously and debounces its search field, so tests need synchronous entry
points. Add these two methods to `PreviewGalleryPanel`, along with the import
`org.jetbrains.annotations.TestOnly`:

```kotlin
    /** Synchronous reload for tests — the production path is [reload]. */
    @TestOnly
    fun reloadSynchronously() {
        entries = PreviewIndexService.getInstance(project).findAll()
        applyFilter()
    }

    /** Applies a query directly, bypassing the 150 ms debounce. */
    @TestOnly
    fun applyQueryForTest(query: String) {
        searchField.text = query
        applyFilter()
    }
```

- [ ] **Step 2: Unit tests — `JvmFqnResolver`**

`src/test/kotlin/com/devomer/previewgallery/index/JvmFqnResolverTest.kt`:

```kotlin
package com.devomer.previewgallery.index

import org.junit.Assert.assertEquals
import org.junit.Test

class JvmFqnResolverTest {

    @Test
    fun `top-level function uses the file facade class`() {
        val jvmClass = JvmFqnResolver.jvmClassName(
            packageName = "com.example",
            fileName = "Foo.kt",
            jvmNameOverride = null,
            containerObjectName = null,
        )
        assertEquals("com.example.FooKt", jvmClass)
        assertEquals("com.example.FooKt.BarPreview", JvmFqnResolver.composableFqn(jvmClass, "BarPreview"))
    }

    @Test
    fun `JvmName annotation replaces the facade name`() {
        assertEquals(
            "com.example.Custom",
            JvmFqnResolver.jvmClassName("com.example", "Foo.kt", "Custom", null),
        )
    }

    @Test
    fun `object member uses the object class and ignores JvmName`() {
        assertEquals(
            "com.example.Previews",
            JvmFqnResolver.jvmClassName("com.example", "Foo.kt", "Custom", "Previews"),
        )
    }

    @Test
    fun `default package has no prefix`() {
        assertEquals("FooKt", JvmFqnResolver.jvmClassName("", "Foo.kt", null, null))
    }

    @Test
    fun `file name is capitalized`() {
        assertEquals("FooKt", JvmFqnResolver.facadeClassName("foo.kt"))
    }

    @Test
    fun `invalid identifier characters become underscores`() {
        assertEquals("Foo_barKt", JvmFqnResolver.facadeClassName("foo-bar.kt"))
    }

    @Test
    fun `a leading digit becomes an underscore`() {
        assertEquals("_1FooKt", JvmFqnResolver.facadeClassName("1foo.kt"))
    }
}
```

- [ ] **Step 3: Unit tests — `PreviewAnnotationMatcher`**

`src/test/kotlin/com/devomer/previewgallery/index/PreviewAnnotationMatcherTest.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAnnotationMatcherTest {

    private val androidxImport = ImportInfo("androidx.compose.ui.tooling.preview.Preview", null, false)
    private val jetbrainsImport = ImportInfo("org.jetbrains.compose.ui.tooling.preview.Preview", null, false)
    private val androidxStar = ImportInfo("androidx.compose.ui.tooling.preview", null, true)
    private val jetbrainsStar = ImportInfo("org.jetbrains.compose.ui.tooling.preview", null, true)

    @Test
    fun `fully qualified androidx reference`() {
        assertEquals(
            AnnotationKind.ANDROIDX,
            PreviewAnnotationMatcher.matchPreview("androidx.compose.ui.tooling.preview.Preview", emptyList()),
        )
    }

    @Test
    fun `fully qualified jetbrains reference`() {
        assertEquals(
            AnnotationKind.JETBRAINS,
            PreviewAnnotationMatcher.matchPreview("org.jetbrains.compose.ui.tooling.preview.Preview", emptyList()),
        )
    }

    @Test
    fun `explicit androidx import`() {
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxImport)))
    }

    @Test
    fun `explicit jetbrains import`() {
        assertEquals(AnnotationKind.JETBRAINS, PreviewAnnotationMatcher.matchPreview("Preview", listOf(jetbrainsImport)))
    }

    @Test
    fun `aliased import`() {
        val aliased = ImportInfo("androidx.compose.ui.tooling.preview.Preview", "P", false)
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("P", listOf(aliased)))
        assertNull(PreviewAnnotationMatcher.matchPreview("Preview", listOf(aliased)))
    }

    @Test
    fun `star import`() {
        assertEquals(AnnotationKind.ANDROIDX, PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxStar)))
    }

    @Test
    fun `both packages star-imported is ambiguous`() {
        assertEquals(
            AnnotationKind.UNKNOWN,
            PreviewAnnotationMatcher.matchPreview("Preview", listOf(androidxStar, jetbrainsStar)),
        )
    }

    @Test
    fun `short name with no matching import is unknown`() {
        assertEquals(AnnotationKind.UNKNOWN, PreviewAnnotationMatcher.matchPreview("Preview", emptyList()))
    }

    @Test
    fun `an unrelated Preview import is not a compose preview`() {
        val unrelated = ImportInfo("com.example.Preview", null, false)
        assertNull(PreviewAnnotationMatcher.matchPreview("Preview", listOf(unrelated)))
    }

    @Test
    fun `an unrelated annotation is not a preview`() {
        assertNull(PreviewAnnotationMatcher.matchPreview("Composable", listOf(androidxImport)))
    }

    @Test
    fun `preview parameter is detected through its import`() {
        val parameterImport = ImportInfo("androidx.compose.ui.tooling.preview.PreviewParameter", null, false)
        assertTrue(PreviewAnnotationMatcher.isPreviewParameter("PreviewParameter", listOf(parameterImport)))
        assertFalse(PreviewAnnotationMatcher.isPreviewParameter("Preview", listOf(parameterImport)))
    }
}
```

- [ ] **Step 4: Integration tests — `PreviewPsiScanner`**

`src/test/kotlin/com/devomer/previewgallery/index/PreviewPsiScannerTest.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class PreviewPsiScannerTest : BasePlatformTestCase() {

    private fun scan(fileName: String, text: String): List<IndexedPreview> {
        val file = myFixture.configureByText(fileName, text) as KtFile
        return PreviewPsiScanner.scan(file)
    }

    fun `test androidx top-level preview`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(1, previews.size)
        val preview = previews.single()
        assertEquals("BarPreview", preview.functionName)
        assertEquals("BarPreview", preview.displayName)
        assertEquals("com.example", preview.packageName)
        assertEquals("com.example.FooKt", preview.jvmClassName)
        assertEquals("com.example.FooKt.BarPreview", preview.composableFqn)
        assertEquals(AnnotationKind.ANDROIDX, preview.annotationKind)
        assertFalse(preview.isPrivate)
        assertFalse(preview.hasPreviewParameter)
        assertNull(preview.previewGroup)
        assertNull(preview.unsupportedReason)
    }

    fun `test jetbrains preview`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import org.jetbrains.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.JETBRAINS, previews.single().annotationKind)
    }

    fun `test private preview is indexed and flagged`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            private fun BarPreview() {}
            """.trimIndent(),
        )
        assertTrue(previews.single().isPrivate)
    }

    fun `test name and group arguments`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview(name = "Dark tab", group = "Tabs")
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("Dark tab", previews.single().displayName)
        assertEquals("Tabs", previews.single().previewGroup)
    }

    fun `test positional name argument`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview("Positional")
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("Positional", previews.single().displayName)
    }

    fun `test non-literal name falls back to the function name`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            const val NAME = "Constant"

            @Preview(name = NAME)
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("BarPreview", previews.single().displayName)
    }

    fun `test JvmName renames the facade`() {
        val previews = scan(
            "Foo.kt",
            """
            @file:JvmName("Custom")

            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals("com.example.Custom.BarPreview", previews.single().composableFqn)
    }

    fun `test object member uses the object name`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            object Previews {
                @Preview
                fun BarPreview() {}
            }
            """.trimIndent(),
        )
        assertEquals("com.example.Previews.BarPreview", previews.single().composableFqn)
        assertNull(previews.single().unsupportedReason)
    }

    fun `test class member is indexed but unsupported`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            class Holder {
                @Preview
                fun BarPreview() {}
            }
            """.trimIndent(),
        )
        assertEquals(1, previews.size)
        assertEquals("declared inside a class", previews.single().unsupportedReason)
    }

    fun `test PreviewParameter is flagged`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.tooling.preview.PreviewParameter

            @Preview
            fun BarPreview(@PreviewParameter(Provider::class) value: String) {}
            """.trimIndent(),
        )
        assertTrue(previews.single().hasPreviewParameter)
    }

    fun `test aliased import`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview as P

            @P
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.ANDROIDX, previews.single().annotationKind)
    }

    fun `test both packages star-imported yields UNKNOWN`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.*
            import org.jetbrains.compose.ui.tooling.preview.*

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertEquals(AnnotationKind.UNKNOWN, previews.single().annotationKind)
    }

    fun `test composable without preview is ignored`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.runtime.Composable

            @Composable
            fun Bar() {}
            """.trimIndent(),
        )
        assertTrue(previews.isEmpty())
    }

    fun `test multipreview wrapper is not resolved in v1`() {
        val previews = scan(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview(name = "light")
            @Preview(name = "dark")
            annotation class ThemePreview

            @ThemePreview
            fun BarPreview() {}
            """.trimIndent(),
        )
        assertTrue(previews.none { it.functionName == "BarPreview" })
    }

    fun `test offset points at the function name`() {
        val text = """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
        """.trimIndent()
        val previews = scan("Foo.kt", text)
        assertEquals(text.indexOf("BarPreview"), previews.single().offset)
    }
}
```

Note: `annotation class ThemePreview` in the multipreview test is itself annotated with `@Preview`, so the scanner must only report previews on **functions**, never on annotation classes — that is what makes `previews.none { ... }` meaningful without also asserting an empty list.

- [ ] **Step 5: Unit tests — `PreviewValueExternalizer`**

`src/test/kotlin/com/devomer/previewgallery/index/PreviewValueExternalizerTest.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PreviewValueExternalizerTest {

    private fun roundTrip(values: List<IndexedPreview>): List<IndexedPreview> {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { PreviewValueExternalizer.save(it, values) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { PreviewValueExternalizer.read(it) }
    }

    private fun preview(name: String) = IndexedPreview(
        displayName = name,
        functionName = name,
        packageName = "com.example",
        jvmClassName = "com.example.FooKt",
        composableFqn = "com.example.FooKt.$name",
        offset = 42,
        annotationKind = AnnotationKind.JETBRAINS,
        isPrivate = true,
        hasPreviewParameter = true,
        previewGroup = "Tabs",
        unsupportedReason = "declared inside a class",
    )

    @Test
    fun `round trips a populated entry`() {
        val values = listOf(preview("BarPreview"))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips null fields`() {
        val values = listOf(preview("BarPreview").copy(previewGroup = null, unsupportedReason = null))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips several entries`() {
        val values = listOf(preview("One"), preview("Two"), preview("Three"))
        assertEquals(values, roundTrip(values))
    }

    @Test
    fun `round trips an empty list`() {
        assertEquals(emptyList<IndexedPreview>(), roundTrip(emptyList()))
    }

    @Test
    fun `round trips non-ascii text`() {
        val values = listOf(preview("BarPreview").copy(displayName = "Ödeme ekranı"))
        assertEquals(values, roundTrip(values))
    }
}
```

- [ ] **Step 6: Integration tests — `PreviewIndex`**

`src/test/kotlin/com/devomer/previewgallery/index/PreviewIndexTest.kt`:

```kotlin
package com.devomer.previewgallery.index

import com.devomer.previewgallery.model.IndexedPreview
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex

class PreviewIndexTest : BasePlatformTestCase() {

    private fun allValues(): List<IndexedPreview> {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val result = mutableListOf<IndexedPreview>()
        index.processAllKeys(PreviewIndex.NAME, { key ->
            index.processValues(PreviewIndex.NAME, key, null, { _, values ->
                result += values
                true
            }, scope)
            true
        }, project)
        return result
    }

    fun `test a preview file is indexed`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val values = allValues()
        assertEquals(1, values.size)
        assertEquals("com.example.FooKt.BarPreview", values.single().composableFqn)
    }

    fun `test a file without previews contributes nothing`() {
        myFixture.addFileToProject(
            "Plain.kt",
            """
            package com.example

            fun plain() {}
            """.trimIndent(),
        )
        assertTrue(allValues().isEmpty())
    }

    fun `test the index key is the composable FQN`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val keys = mutableListOf<String>()
        FileBasedIndex.getInstance().processAllKeys(PreviewIndex.NAME, { keys += it; true }, project)
        assertEquals(listOf("com.example.FooKt.BarPreview"), keys)
    }

    fun `test two previews in one file are both indexed`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun OnePreview() {}

            @Preview
            fun TwoPreview() {}
            """.trimIndent(),
        )
        assertEquals(2, allValues().size)
    }
}
```

- [ ] **Step 7: Integration tests — `PreviewIndexService`**

`src/test/kotlin/com/devomer/previewgallery/service/PreviewIndexServiceTest.kt`:

```kotlin
package com.devomer.previewgallery.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewIndexServiceTest : BasePlatformTestCase() {

    fun `test entries carry the resolved module and file`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )

        val entries = PreviewIndexService.getInstance(project).findAll()

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("com.example.FooKt.BarPreview", entry.indexed.composableFqn)
        assertEquals("Foo.kt", entry.file.name)
        assertTrue(entry.moduleName.isNotBlank())
        assertEquals("com.example.FooKt.BarPreview#BarPreview", entry.id)
    }

    fun `test entries are sorted by module then package then display name`() {
        myFixture.addFileToProject(
            "Zeta.kt",
            """
            package com.example.zeta

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun ZPreview() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Alpha.kt",
            """
            package com.example.alpha

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun APreview() {}
            """.trimIndent(),
        )

        val packages = PreviewIndexService.getInstance(project).findAll().map { it.indexed.packageName }

        assertEquals(listOf("com.example.alpha", "com.example.zeta"), packages)
    }

    fun `test an empty project yields no entries`() {
        assertTrue(PreviewIndexService.getInstance(project).findAll().isEmpty())
    }
}
```

- [ ] **Step 8: Unit tests — search and module filters**

`src/test/kotlin/com/devomer/previewgallery/search/TestPreviewRow.kt`:

```kotlin
package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.model.IndexedPreview
import com.devomer.previewgallery.model.PreviewRow

/** A `PreviewRow` with no `VirtualFile`, so pure logic can be tested without an IDE fixture. */
data class TestPreviewRow(
    override val indexed: IndexedPreview,
    override val moduleName: String,
) : PreviewRow

fun testRow(
    displayName: String = "BarPreview",
    functionName: String = "BarPreview",
    packageName: String = "com.example",
    moduleName: String = "app",
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
    ),
    moduleName = moduleName,
)
```

`src/test/kotlin/com/devomer/previewgallery/search/PreviewSearchFilterTest.kt`:

```kotlin
package com.devomer.previewgallery.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSearchFilterTest {

    @Test
    fun `an empty query matches everything`() {
        assertTrue(PreviewSearchFilter.matches(testRow(), ""))
        assertTrue(PreviewSearchFilter.matches(testRow(), "   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabs"), "primustabs"))
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "primustabs"), "PRIMUSTABS"))
    }

    @Test
    fun `matching is substring, not prefix`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabsPreview"), "Tabs"))
    }

    @Test
    fun `matches the function name when the display name differs`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "Dark", functionName = "TabsPreview"), "tabs"))
    }

    @Test
    fun `matches the package name`() {
        assertTrue(PreviewSearchFilter.matches(testRow(packageName = "com.example.tabs"), "tabs"))
    }

    @Test
    fun `does not match unrelated text`() {
        assertFalse(PreviewSearchFilter.matches(testRow(), "zzz"))
    }

    @Test
    fun `the query is trimmed`() {
        assertTrue(PreviewSearchFilter.matches(testRow(displayName = "PrimusTabs"), "  Tabs  "))
    }

    @Test
    fun `filter keeps only matching rows`() {
        val rows = listOf(testRow(displayName = "TabsPreview"), testRow(displayName = "ButtonPreview"))
        assertEquals(listOf(rows.first()), PreviewSearchFilter.filter(rows, "tabs"))
    }

    @Test
    fun `module filter is a no-op when disabled`() {
        val rows = listOf(testRow(moduleName = "app"), testRow(moduleName = "design"))
        assertEquals(rows, PreviewModuleFilter.apply(rows, "app", enabled = false))
    }

    @Test
    fun `module filter keeps only the active module`() {
        val rows = listOf(testRow(moduleName = "app"), testRow(moduleName = "design"))
        assertEquals(listOf(rows.first()), PreviewModuleFilter.apply(rows, "app", enabled = true))
    }

    @Test
    fun `module filter with no active module yields nothing`() {
        val rows = listOf(testRow(moduleName = "app"))
        assertTrue(PreviewModuleFilter.apply(rows, null, enabled = true).isEmpty())
    }
}
```

- [ ] **Step 9: Unit tests — `PreviewTreeModelBuilder`**

`src/test/kotlin/com/devomer/previewgallery/ui/PreviewTreeModelBuilderTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTreeModelBuilderTest {

    @Test
    fun `groups by module then package`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.b", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.c", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("app", "design"), modules.map { it.moduleName })
        assertEquals(listOf("com.a", "com.b"), modules.first().packages.map { it.packageName })
        assertEquals(listOf("com.c"), modules.last().packages.map { it.packageName })
    }

    @Test
    fun `module count is the number of previews below it`() {
        val rows = listOf(
            testRow(displayName = "A", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "B", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "C", packageName = "com.b", moduleName = "app"),
        )

        assertEquals(3, PreviewTreeModelBuilder.build(rows, "").single().count)
    }

    @Test
    fun `everything is sorted alphabetically`() {
        val rows = listOf(
            testRow(displayName = "Zebra", packageName = "com.z", moduleName = "zeta"),
            testRow(displayName = "Apple", packageName = "com.a", moduleName = "alpha"),
            testRow(displayName = "Banana", packageName = "com.a", moduleName = "alpha"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "")

        assertEquals(listOf("alpha", "zeta"), modules.map { it.moduleName })
        assertEquals(
            listOf("Apple", "Banana"),
            modules.first().packages.single().previews.map { it.row.indexed.displayName },
        )
    }

    @Test
    fun `the query prunes branches with no surviving leaves`() {
        val rows = listOf(
            testRow(displayName = "TabsPreview", packageName = "com.a", moduleName = "app"),
            testRow(displayName = "ButtonPreview", packageName = "com.b", moduleName = "design"),
        )

        val modules = PreviewTreeModelBuilder.build(rows, "tabs")

        assertEquals(1, modules.size)
        assertEquals("app", modules.single().moduleName)
        assertEquals(1, modules.single().count)
    }

    @Test
    fun `a query matching nothing yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(listOf(testRow()), "zzz").isEmpty())
    }

    @Test
    fun `an empty input yields no modules`() {
        assertTrue(PreviewTreeModelBuilder.build(emptyList(), "").isEmpty())
    }
}
```

- [ ] **Step 10: Unit tests — `PreviewDetailModel`**

`src/test/kotlin/com/devomer/previewgallery/ui/PreviewDetailModelTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.AnnotationKind
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewDetailModelTest {

    private fun valueOf(fields: List<DetailField>, label: String): String? =
        fields.firstOrNull { it.label == label }?.value

    @Test
    fun `reports identity fields`() {
        val row = testRow(displayName = "Dark tab", functionName = "TabsPreview", moduleName = "design")
        val fields = PreviewDetailModel.fields(row, fileName = "Tabs.kt", line = 41)

        assertEquals("Dark tab", valueOf(fields, "Name"))
        assertEquals("com.example.FooKt.TabsPreview", valueOf(fields, "FQN"))
        assertEquals("design", valueOf(fields, "Module"))
        assertEquals("Tabs.kt:42", valueOf(fields, "File"))
    }

    @Test
    fun `a null line omits the line suffix`() {
        val fields = PreviewDetailModel.fields(testRow(), fileName = "Tabs.kt", line = null)
        assertEquals("Tabs.kt", valueOf(fields, "File"))
    }

    @Test
    fun `reports the annotation kind`() {
        val row = testRow()
        val jetbrains = row.copy(indexed = row.indexed.copy(annotationKind = AnnotationKind.JETBRAINS))
        assertEquals("org.jetbrains", valueOf(PreviewDetailModel.fields(jetbrains, "Foo.kt", null), "Annotation"))
        assertEquals("androidx", valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Annotation"))
    }

    @Test
    fun `optional fields appear only when set`() {
        val row = testRow()
        assertNull(valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Group"))
        assertNull(valueOf(PreviewDetailModel.fields(row, "Foo.kt", null), "Unsupported"))

        val decorated = row.copy(
            indexed = row.indexed.copy(
                previewGroup = "Tabs",
                unsupportedReason = "declared inside a class",
                isPrivate = true,
                hasPreviewParameter = true,
            ),
        )
        val fields = PreviewDetailModel.fields(decorated, "Foo.kt", null)
        assertEquals("Tabs", valueOf(fields, "Group"))
        assertEquals("declared inside a class", valueOf(fields, "Unsupported"))
        assertEquals("yes", valueOf(fields, "Private"))
        assertEquals("yes", valueOf(fields, "PreviewParameter"))
    }
}
```

- [ ] **Step 11: Integration tests — `PreviewGalleryPanel`**

`src/test/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanelTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewGalleryPanelTest : BasePlatformTestCase() {

    private fun panel(): PreviewGalleryPanel {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return PreviewGalleryPanel(project, disposable)
    }

    fun `test an empty project reports NO_PREVIEWS`() {
        val panel = panel()
        panel.reloadSynchronously()
        assertEquals(PreviewGalleryPanel.State.NO_PREVIEWS, panel.state)
    }

    fun `test a project with previews reports LOADED`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        assertEquals(PreviewGalleryPanel.State.LOADED, panel.state)
    }

    fun `test a query matching nothing reports NO_MATCH`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        panel.applyQueryForTest("zzz")
        assertEquals(PreviewGalleryPanel.State.NO_MATCH, panel.state)
    }
}
```

- [ ] **Step 12: Integration tests — `ActiveModuleTracker`**

`src/test/kotlin/com/devomer/previewgallery/ui/ActiveModuleTrackerTest.kt`:

```kotlin
package com.devomer.previewgallery.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActiveModuleTrackerTest : BasePlatformTestCase() {

    fun `test no open file means no active module`() {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        val tracker = ActiveModuleTracker(project, disposable) {}
        assertNull(tracker.activeModuleName)
    }

    fun `test an open file reports its module`() {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        val tracker = ActiveModuleTracker(project, disposable) {}

        val file = myFixture.addFileToProject("Foo.kt", "package com.example")
        FileEditorManager.getInstance(project).openFile(file.virtualFile, true)

        assertNotNull(tracker.activeModuleName)
    }
}
```

- [ ] **Step 13: Integration tests — `PreviewSearchEverywhereContributor`**

`src/test/kotlin/com/devomer/previewgallery/searcheverywhere/PreviewSearchEverywhereContributorTest.kt`:

```kotlin
package com.devomer.previewgallery.searcheverywhere

import com.devomer.previewgallery.model.PreviewEntry
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor

class PreviewSearchEverywhereContributorTest : BasePlatformTestCase() {

    private fun fetch(pattern: String): List<PreviewEntry> {
        val found = mutableListOf<PreviewEntry>()
        PreviewSearchEverywhereContributor(project)
            .fetchElements(pattern, EmptyProgressIndicator(), Processor { found += it; true })
        return found
    }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun TabsPreview() {}

            @Preview
            fun ButtonPreview() {}
            """.trimIndent(),
        )
    }

    fun `test a matching pattern finds the preview`() {
        assertEquals(listOf("TabsPreview"), fetch("tabs").map { it.indexed.displayName })
    }

    fun `test a non-matching pattern finds nothing`() {
        assertTrue(fetch("zzz").isEmpty())
    }

    fun `test an empty pattern returns every preview`() {
        assertEquals(2, fetch("").size)
    }
}
```

- [ ] **Step 14: Run the whole suite**

Run: `./gradlew test`
Expected: PASS. Every test written in this task passes and the output is free of warnings.

Fix any failure at its source: a failing test means the implementation from Tasks 2–11 is wrong, not that the
test should be relaxed.

- [ ] **Step 15: Commit**

```bash
git add src/test src/main/kotlin/com/devomer/previewgallery/ui/PreviewGalleryPanel.kt
git commit -m "[PG-12] - Phase 1 test suite"
```

---

### Task 13: Manual verification against a real project

**Files:**
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything from Tasks 1–12.
- Produces: a verified `0.1.0` build.

This task covers the acceptance criteria that a light test fixture cannot: real multi-module grouping, the debounce, and the Shift-Shift path (spec §6.4).

- [ ] **Step 1: Run the full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL with every test passing.

- [ ] **Step 2: Launch the sandbox IDE**

Run: `./gradlew runIde`
Expected: Android Studio starts with the plugin installed. Open a real multi-module Compose project in it.

- [ ] **Step 3: Verify the tool window (AC1, AC5)**

Open **Compose Gallery** from the right tool window bar. Confirm:
- previews are grouped module → package, with a count on each module node;
- the count matches the number of leaves below the module;
- a preview declared inside a class shows the `unsupported` badge.

- [ ] **Step 4: Verify search and navigation (AC2, AC3)**

- Type a fragment of a known preview name; the tree narrows and expands to the matches.
- Clear the field; the full tree returns.
- Press `Enter` on a leaf; the editor opens that file with the caret on the function name.
- Double-click another leaf; the same happens.

- [ ] **Step 5: Verify annotation kinds (AC4)**

Select one androidx preview and one `org.jetbrains` preview (a Compose Multiplatform `commonMain` module). The detail panel's `Annotation` row reads `androidx` and `org.jetbrains` respectively.

- [ ] **Step 6: Verify the module filter (AC6)**

- Open a file in one module, enable the filter toggle; only that module remains.
- Switch the editor to a file in another module; the tree follows.
- Restart the sandbox IDE (`./gradlew runIde` again); the toggle is still enabled.

- [ ] **Step 7: Verify SearchEverywhere (AC7)**

Press `Shift` twice, type a preview name, confirm it appears under **Compose Previews**, and press `Enter` — the editor navigates and the gallery selects the same node.

- [ ] **Step 8: Verify the log is clean (AC8)**

Run: `grep -i "com.devomer.previewgallery" build/idea-sandbox/*/log/idea.log`
Expected: no `ERROR` or `WARN` lines mentioning the plugin. Investigate anything that appears before continuing.

- [ ] **Step 9: Update the changelog**

`CHANGELOG.md`:

```markdown
# Compose Preview Gallery Changelog

## [Unreleased]

### Added

- Project-wide index of Jetpack Compose `@Preview` functions, persistent and incrementally updated.
- Tool window with a module → package → preview tree, live search, and a detail panel.
- Navigation from a preview to its declaration.
- Toggle to restrict the tree to the active editor's module.
- Previews are reachable from Search Everywhere (Shift-Shift).

### Known limitations

- No preview rendering — the lower panel is a placeholder until Phase 2.
- Multipreview annotations (custom annotations meta-annotated with `@Preview`) are not resolved.
- Previews declared inside a class are listed but marked unsupported.
- CI is disabled: the build resolves the platform from a local Android Studio install.
```

- [ ] **Step 10: Commit**

```bash
git add CHANGELOG.md
git commit -m "[PG-13] - Verify Phase 1 against a real project"
```
