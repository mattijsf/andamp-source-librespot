// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [EngineLine] read without the engine: every line it knows, and the lines it ignores. */
class EngineLineTest {
    @Test
    fun `a position carries its milliseconds`() {
        assertEquals(EngineEvent.PositionChanged(5678), EngineLine.parse("position 5678"))
    }

    @Test
    fun `playing and paused each carry where they are`() {
        assertEquals(EngineEvent.Playing(0), EngineLine.parse("playing 0"))
        assertEquals(EngineEvent.Paused(1234), EngineLine.parse("paused 1234"))
    }

    @Test
    fun `the end of a track needs nothing else`() {
        assertEquals(EngineEvent.EndOfTrack, EngineLine.parse("endOfTrack"))
    }

    @Test
    fun `a refusal names the track it refused`() {
        assertEquals(
            EngineEvent.Unavailable("spotify:track:t0"),
            EngineLine.parse("unavailable spotify:track:t0"),
        )
    }

    @Test
    fun `a failure keeps the whole reason, spaces and all`() {
        assertEquals(
            EngineEvent.Failed("could not connect: no route to host"),
            EngineLine.parse("failed could not connect: no route to host"),
        )
    }

    /**
     * The two reasons the backend acts on, written out as the engine words
     * them, so a change on either side fails here.
     */
    @Test
    fun `the reasons that change what to do are the engine's own words`() {
        assertEquals(EngineEvent.Failed("not a track uri"), EngineLine.parse("failed not a track uri"))
        assertEquals("not a track uri", EngineLine.NOT_A_TRACK)
        assertEquals(EngineEvent.Failed("not premium"), EngineLine.parse("failed not premium"))
        assertEquals("not premium", EngineLine.NOT_PREMIUM)
    }

    @Test
    fun `a bitrate carries its kbps`() {
        assertEquals(EngineEvent.Bitrate(320), EngineLine.parse("bitrate 320"))
        assertEquals(EngineEvent.Bitrate(96), EngineLine.parse("bitrate 96"))
    }

    @Test
    fun `a bitrate that is not a number of kbps is not a bitrate`() {
        assertNull(EngineLine.parse("bitrate"))
        assertNull(EngineLine.parse("bitrate high"))
        assertNull(EngineLine.parse("bitrate 0"))
    }

    @Test
    fun `a failure with no reason is unknown`() {
        assertEquals(EngineEvent.Failed("unknown"), EngineLine.parse("failed"))
    }

    @Test
    fun `a line nobody recognizes is ignored`() {
        assertNull(EngineLine.parse("somethingNewUpstream 42"))
    }

    @Test
    fun `a position that is not a number is not a position`() {
        assertNull(EngineLine.parse("position soon"))
    }

    @Test
    fun `nothing at all is nothing`() {
        assertNull(EngineLine.parse(""))
        assertNull(EngineLine.parse("   "))
    }
}
