package com.devomer.previewgallery.ui

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceFileDisambiguatorTest {

    // LightVirtualFile is a concrete VirtualFile — no mocking framework (this project wires none); distinct
    // instances give us identity to assert on.
    private fun candidate(name: String, hash: Int?): SourceFileDisambiguator.Candidate =
        SourceFileDisambiguator.Candidate(LightVirtualFile(name), hash)

    @Test fun `picks the candidate whose package hash matches the target`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(b.file, SourceFileDisambiguator.pick(22, listOf(a, b)))
    }

    @Test fun `falls back to the first candidate when the target hash is null`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(a.file, SourceFileDisambiguator.pick(null, listOf(a, b)))
    }

    @Test fun `falls back to the first candidate when nothing matches`() {
        val a = candidate("A.kt", 11); val b = candidate("A.kt", 22)
        assertSame(a.file, SourceFileDisambiguator.pick(99, listOf(a, b)))
    }

    @Test fun `returns null for no candidates`() {
        assertNull(SourceFileDisambiguator.pick(1, emptyList()))
    }
}
