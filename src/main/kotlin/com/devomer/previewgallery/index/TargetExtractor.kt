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
 *
 * ## What it does not see
 *
 * A statement is a target only when it is *itself* a [KtCallExpression]. Every other statement shape is skipped
 * silently, and a body made only of those yields no targets at all — which the coverage join reads as "never
 * matched" (spec's error table), not as an error. The shapes deliberately left out:
 *
 * - **Qualified calls** — `Foo.Bar()` and `foo.Bar()` are `KtDotQualifiedExpression`s, `foo?.Bar()` a
 *   `KtSafeQualifiedExpression`; none of them is a `KtCallExpression`, so none is a target. Every sampled body
 *   calls its composable unqualified, which is the Compose convention.
 * - **Parenthesized calls** — `(Bar())`.
 * - **Expression bodies that are not a call** — `= return X()` cannot occur, but a block body shaped
 *   `{ return X() }` yields nothing (`KtReturnExpression`), as does `{ if (flag) X() else Y() }`
 *   (`KtIfExpression`), a `when`, a `val x = X()` (`KtProperty`), or a call wrapped in `remember { … }`'s
 *   argument list.
 * - **Non-trailing lambdas** — descent follows `lambdaArguments.last()` only, so `Foo(content = { Bar() })` is
 *   an argument-list lambda and is not entered (by design: that is the same rule that keeps `state = FakeState()`
 *   out).
 *
 * Widening any of these is a heuristic change, not a bug fix: each one also admits shapes that are not the
 * composable under test. Measure against the corpus before adding one.
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
