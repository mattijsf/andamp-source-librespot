// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Test

/** [Playability]'s three answers from a count: carry on, try a fresh connection, tell the listener. */
class PlayabilityTest {
    @Test
    fun `one track that will not play is a track`() {
        assertEquals(Playability.Answer.CarryOn, Playability().failed())
    }

    @Test
    fun `two in a row could still be two tracks`() {
        val run = Playability()
        run.failed()

        assertEquals(Playability.Answer.CarryOn, run.failed())
    }

    @Test
    fun `three in a row asks for a fresh connection`() {
        val run = Playability()
        run.failed()
        run.failed()

        assertEquals(Playability.Answer.Reopen, run.failed())
    }

    @Test
    fun `after reopening, the next two failures carry on`() {
        val run = Playability()
        repeat(3) { run.failed() }

        assertEquals(Playability.Answer.CarryOn, run.failed())
        assertEquals(Playability.Answer.CarryOn, run.failed())
    }

    @Test
    fun `a second run after reopening tells the listener`() {
        val run = Playability()
        repeat(5) { run.failed() }

        assertEquals(Playability.Answer.Tell, run.failed())
    }

    /** The notice stops playback, so the listener's next press is a fresh attempt with the whole cycle again. */
    @Test
    fun `after saying so, the next attempt is given a fresh connection again`() {
        val run = Playability()
        repeat(6) { run.failed() }

        assertEquals(Playability.Answer.CarryOn, run.failed())
        assertEquals(Playability.Answer.CarryOn, run.failed())
        assertEquals(Playability.Answer.Reopen, run.failed())
    }

    @Test
    fun `a track that played starts the count over`() {
        val run = Playability()
        run.failed()
        run.failed()
        run.played()

        assertEquals("a played track resets the count", Playability.Answer.CarryOn, run.failed())
    }

    /** A queue of one, or a press on the last row. */
    @Test
    fun `a queue that runs out before the count can decide tries a fresh connection first`() {
        assertEquals(Playability.Answer.Reopen, Playability().ranOut())
    }

    @Test
    fun `a queue that runs out again after reopening tells the listener`() {
        val run = Playability()
        run.ranOut()

        assertEquals(Playability.Answer.Tell, run.ranOut())
    }

    @Test
    fun `a run that has already reopened says so when the queue runs out`() {
        val run = Playability()
        repeat(4) { run.failed() }

        assertEquals(Playability.Answer.Tell, run.ranOut())
    }

    @Test
    fun `audio in between means the next run out is given its fresh connection`() {
        val run = Playability()
        run.ranOut()
        run.played()

        assertEquals(Playability.Answer.Reopen, run.ranOut())
    }

    /** Not Premium, for one: no count and no fresh connection changes it. */
    @Test
    fun `what the engine is sure about is said at once, and the next attempt starts over`() {
        val run = Playability()
        run.failed()

        assertEquals(Playability.Answer.Tell, run.refusedForGood())
        assertEquals(Playability.Answer.CarryOn, run.failed())
        assertEquals(Playability.Answer.CarryOn, run.failed())
    }
}
