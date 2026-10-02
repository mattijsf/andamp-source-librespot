// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine's one event queue and the engines that attach to and detach from
 * it. The queue is two functions a test writes, so nothing here needs the
 * library.
 *
 * A sign-out releases an engine and a sign-in builds another; lines must go to
 * the engine attached when they arrive and to no other.
 */
class EngineEventsTest {
    @Test
    fun aReleasedEngineHearsNothingAndTheNextHearsEverything() {
        val said = ArrayDeque<String>()
        val events = EngineEvents(claim = {}, next = { said.removeFirstOrNull().orEmpty() })
        val first = Channel<EngineEvent>(Channel.UNLIMITED)
        events.attach(first)
        events.detach(first)
        first.close()
        val second = Channel<EngineEvent>(Channel.UNLIMITED)
        events.attach(second)

        said += listOf("playing 0", "position 250", "endOfTrack")
        repeat(3) { events.deliverOne(0) }

        assertEquals(
            listOf(EngineEvent.Playing(0), EngineEvent.PositionChanged(250), EngineEvent.EndOfTrack),
            List(3) { second.tryReceive().getOrNull() },
        )
    }

    @Test
    fun aLineForAClosedInboxIsDroppedRatherThanThrown() {
        val events = EngineEvents(claim = {}, next = { "endOfTrack" })
        val closed = Channel<EngineEvent>(Channel.UNLIMITED).also { it.close() }
        events.attach(closed)

        // trySend to a closed inbox must not throw
        events.deliverOne(0)
    }

    @Test
    fun aLateReleaseLeavesTheCurrentEngineAttached() {
        val events = EngineEvents(claim = {}, next = { "endOfTrack" })
        val old = Channel<EngineEvent>(Channel.UNLIMITED)
        val current = Channel<EngineEvent>(Channel.UNLIMITED)
        events.attach(old)
        events.attach(current)

        // the old engine is released after the new one was built
        events.detach(old)
        events.deliverOne(0)

        assertEquals(EngineEvent.EndOfTrack, current.tryReceive().getOrNull())
        assertEquals(null, old.tryReceive().getOrNull())
    }

    @Test
    fun attachingClaimsTheQueueBeforeTheInboxHearsAnything() {
        val order = mutableListOf<String>()
        val events =
            EngineEvents(
                claim = { order += "claim" },
                next = {
                    order += "read"
                    "endOfTrack"
                },
            )

        events.attach(Channel(Channel.UNLIMITED))
        events.deliverOne(0)

        assertEquals(listOf("claim", "read"), order)
    }

    @Test
    fun nothingHeardIsNothingDelivered() {
        val events = EngineEvents(claim = {}, next = { "" })
        val inbox = Channel<EngineEvent>(Channel.UNLIMITED)
        events.attach(inbox)

        events.deliverOne(0)

        assertEquals(null, inbox.tryReceive().getOrNull())
    }
}
