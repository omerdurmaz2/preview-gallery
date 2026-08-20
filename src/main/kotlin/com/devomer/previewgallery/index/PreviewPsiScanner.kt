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

/**
 * Extracts every directly `@Preview`-annotated function from a single Kotlin file. Also extracts a function that
 * carries `@PreviewTest` even when it has no direct `@Preview` match, since the project's snapshot functions are
 * marked through a custom multipreview that a file-local indexer can never resolve.
 */
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
                val matches = function.annotationEntries.mapNotNull { entry ->
                    val reference = entry.referenceText() ?: return@mapNotNull null
                    PreviewAnnotationMatcher.matchPreview(reference, imports)?.let { entry to it }
                }
                val isSnapshotTest = function.annotationEntries.any { entry ->
                    val reference = entry.referenceText() ?: return@any false
                    PreviewAnnotationMatcher.isPreviewTest(reference, imports)
                }
                // A `FileBasedIndex` indexer cannot resolve across files, so a function whose only preview
                // annotation is a custom multipreview (unresolvable here) still needs to be emitted when it
                // carries `@PreviewTest` directly.
                if (matches.isEmpty() && !isSnapshotTest) return
                val (annotation, kind) = matches.firstOrNull() ?: (null to AnnotationKind.UNKNOWN)
                result += build(
                    function, annotation, kind, matches.size > 1, packageName, file.name, jvmNameOverride, imports,
                    isSnapshotTest,
                )
            }
        })
        return result
    }

    /**
     * Whether [function] carries a preview annotation this plugin recognises — the same rule [scan] indexes by,
     * asked of one function instead of a whole file (PG24-3, for the editor gutter marker).
     *
     * Both halves of that rule, deliberately: a direct `@Preview` **or** a bare `@PreviewTest`, because the
     * reference project marks its snapshot functions through a custom multipreview that no file-local check can
     * resolve — exactly the case [scan]'s own `matches.isEmpty() && !isSnapshotTest` guard exists for. Sharing
     * this function is what keeps the gutter from marking a different set of functions than the tree lists.
     *
     * Reads the containing file's import list on every call. That is affordable only because the caller filters
     * down to a function's own name identifier first; called per PSI leaf it would not be.
     */
    fun isPreviewFunction(function: KtNamedFunction): Boolean {
        val imports = function.containingKtFile.importDirectives.mapNotNull { directive ->
            val fqn = directive.importedFqName?.asString() ?: return@mapNotNull null
            ImportInfo(fqn, directive.aliasName, directive.isAllUnder)
        }
        return function.annotationEntries.any { entry ->
            val reference = entry.referenceText() ?: return@any false
            PreviewAnnotationMatcher.matchPreview(reference, imports) != null ||
                PreviewAnnotationMatcher.isPreviewTest(reference, imports)
        }
    }

    private fun build(
        function: KtNamedFunction,
        annotation: KtAnnotationEntry?,
        kind: AnnotationKind,
        hasMultiplePreviews: Boolean,
        packageName: String,
        fileName: String,
        jvmNameOverride: String?,
        imports: List<ImportInfo>,
        isSnapshotTest: Boolean,
    ): IndexedPreview {
        val container = containerOf(function)
        val functionName = function.name ?: ""
        // For an unsupported container this falls back to the file facade; `unsupportedReason` marks the entry,
        // so the value is never authoritative.
        val jvmClassName = JvmFqnResolver.jvmClassName(
            packageName = packageName,
            fileName = fileName,
            jvmNameOverride = jvmNameOverride,
            containerObjectName = (container as? Container.InObject)?.name,
        )
        val name = if (hasMultiplePreviews || annotation == null) {
            // No single config's name represents a function carrying several @Preview annotations, and a
            // snapshot-only function (no @Preview match at all) has no config to read either — both fall back
            // to the function name. v1 renders nothing, so the configs need not become separate entries.
            functionName
        } else {
            namedString(annotation, "name") ?: positionalString(annotation, 0) ?: functionName
        }
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
            previewGroup = annotation?.let { namedString(it, "group") },
            unsupportedReason = (container as? Container.Unsupported)?.reason,
            isSnapshotTest = isSnapshotTest,
            targets = TargetExtractor.extract(function),
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
                    // Only an object declared directly in the file has a plain JVM name. A nested one would need
                    // `$` separators, which v1 does not derive.
                    val isTopLevelObject = current.parent is KtFile
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
