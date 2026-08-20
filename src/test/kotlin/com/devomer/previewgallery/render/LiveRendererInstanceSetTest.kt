package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.RenderOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

/**
 * [LiveRenderer.combineInstanceRenders] is the pure form of what a `@PreviewParameter` set adds up to (PG24) —
 * see its own KDoc. Everything else in [LiveRenderer] needs layoutlib to run at all; this decision does not, so
 * it is tested directly here, the same split [RenderModelResolverTest] and [ImageDiffTest] already draw for
 * their own pure logic in this package.
 */
class LiveRendererInstanceSetTest {

    private fun success(width: Int = 10, dpi: Int = 420) =
        RenderOutcome.Success(BufferedImage(width, 10, BufferedImage.TYPE_INT_ARGB), emptyList(), dpi)

    private fun failure(message: String) = RenderOutcome.Failure(message, "detail of $message")

    @Test
    fun `every instance that rendered is carried, in order, with its own label`() {
        val combined = LiveRenderer.combineInstanceRenders(
            listOf("Preview (0)" to success(), "Preview (1)" to success(), "Preview (2)" to success()),
            "nothing",
        )

        val multi = combined as RenderOutcome.MultiSuccess
        assertEquals(listOf("Preview (0)", "Preview (1)", "Preview (2)"), multi.renders.map { it.label })
    }

    /** The property the whole branch exists for: one provider value that throws must cost that value, not the
     *  other nine. */
    @Test
    fun `a failing instance does not fail the set`() {
        val combined = LiveRenderer.combineInstanceRenders(
            listOf("Preview (0)" to success(), "Preview (1)" to failure("boom"), "Preview (2)" to success()),
            "nothing",
        )

        val multi = combined as RenderOutcome.MultiSuccess
        assertEquals(listOf("Preview (0)", "Preview (2)"), multi.renders.map { it.label })
    }

    /** The failure that comes back must be layoutlib's own, with its detail intact: a summary invented here would
     *  drop the only text that says why the preview is blank. */
    @Test
    fun `a set where nothing rendered reports the first real failure, detail and all`() {
        val combined = LiveRenderer.combineInstanceRenders(
            listOf("Preview (0)" to failure("first"), "Preview (1)" to failure("second")),
            "nothing",
        )

        assertEquals(failure("first"), combined)
    }

    @Test
    fun `an unsupported instance is not mistaken for a failure to report`() {
        val combined = LiveRenderer.combineInstanceRenders(
            listOf("Preview (0)" to RenderOutcome.Unsupported("no facet"), "Preview (1)" to failure("real")),
            "nothing",
        )

        assertEquals(failure("real"), combined)
    }

    @Test
    fun `an empty set falls back to the caller's own message rather than an empty success`() {
        val combined = LiveRenderer.combineInstanceRenders(emptyList(), "Nothing to render for Foo")

        assertEquals(RenderOutcome.Failure("Nothing to render for Foo", null), combined)
    }

    /** A `MultiSuccess` with no renders would paint an empty strip that looks like a successful render of
     *  nothing, which is the one outcome this function must never produce. */
    @Test
    fun `a multi success is never empty`() {
        val combined = LiveRenderer.combineInstanceRenders(listOf("Preview (0)" to failure("boom")), "nothing")

        assertTrue(combined !is RenderOutcome.MultiSuccess)
    }

    @Test
    fun `the density comes from a render that actually produced one`() {
        val combined = LiveRenderer.combineInstanceRenders(
            listOf("Preview (0)" to success(dpi = 420), "Preview (1)" to failure("boom")),
            "nothing",
        )

        assertEquals(420, (combined as RenderOutcome.MultiSuccess).dpi)
    }

    @Test
    fun `flattenHtml turns a layoutlib problem into one readable line`() {
        assertEquals(
            "Couldn't resolve resource @drawable/foo",
            LiveRenderer.flattenHtml("<b>Couldn't resolve</b><BR/>resource @drawable/foo"),
        )
    }

    /**
     * Tags out first, then entities: unescaping first would promote a composable's own escaped `&lt;b&gt;` to a
     * real tag and then strip it, deleting text its author wrote.
     */
    @Test
    fun `an escaped tag in the message survives flattening`() {
        assertEquals("use <b> here", LiveRenderer.flattenHtml("<i>use &lt;b&gt; here</i>"))
    }

    @Test
    fun `flattening a message with no markup leaves it alone`() {
        assertEquals("No Component provided", LiveRenderer.flattenHtml("No Component provided"))
    }
}
