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
