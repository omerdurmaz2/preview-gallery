package com.devomer.previewgallery.ui

import com.devomer.previewgallery.ui.IndexStampGate.Companion.UNKNOWN_STAMP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [IndexStampGate] — the pure half of [IndexingCompletionTracker] (PG11-2), which decides whether an
 * exit-dumb-mode signal is worth a full gallery reload. The message-bus subscription and the debounce around it
 * are exercised by [IndexingCompletionTrackerTest] against a real project.
 */
class IndexStampGateTest {

    @Test
    fun `a moved stamp is accepted`() {
        val gate = IndexStampGate(lastStamp = 1L)

        assertTrue(gate.accept(2L))
    }

    @Test
    fun `an unchanged stamp is rejected`() {
        val gate = IndexStampGate(lastStamp = 1L)

        assertFalse(gate.accept(1L))
    }

    @Test
    fun `the first signal is accepted when there is no baseline yet`() {
        val gate = IndexStampGate(lastStamp = UNKNOWN_STAMP)

        assertTrue(gate.accept(7L))
    }

    @Test
    fun `an unreadable stamp is accepted rather than silently swallowed`() {
        val gate = IndexStampGate(lastStamp = 7L)

        assertTrue(gate.accept(UNKNOWN_STAMP))
    }

    @Test
    fun `an accepted signal becomes the new baseline`() {
        val gate = IndexStampGate(lastStamp = 1L)

        assertTrue(gate.accept(2L))
        assertFalse(gate.accept(2L))
    }

    @Test
    fun `a rejected signal leaves the baseline untouched`() {
        val gate = IndexStampGate(lastStamp = 1L)

        assertFalse(gate.accept(1L))
        assertTrue(gate.accept(2L))
    }

    @Test
    fun `an unreadable stamp does not become a baseline that swallows the next real one`() {
        val gate = IndexStampGate(lastStamp = 1L)

        assertTrue(gate.accept(UNKNOWN_STAMP))
        assertTrue(gate.accept(1L))
    }

    @Test
    fun `every pass of a multi pass indexing round is accepted`() {
        // The hepsi-android failure: PreviewIndex took 3,918 files, then 115,805, then 1,581, then 4 across
        // four passes. Each one moves the stamp, so each one must reach the panel — reloading on only the first
        // is exactly the bug (a tree showing 4 of 40+ modules).
        val gate = IndexStampGate(lastStamp = 100L)

        assertTrue(gate.accept(101L))
        assertTrue(gate.accept(102L))
        assertTrue(gate.accept(103L))
        assertTrue(gate.accept(104L))
    }
}
