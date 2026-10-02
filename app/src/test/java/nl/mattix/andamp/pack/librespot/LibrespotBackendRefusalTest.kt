// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the backend does about tracks that will not play, and a source that
 * will not play anything.
 *
 * The counting is [Playability]'s and is tested there. This asserts which row
 * the cursor is left on, whether the engine was asked to reconnect, and
 * whether the listener is told.
 */
class LibrespotBackendRefusalTest {
    private fun tracks(vararg durationsSec: Int) =
        durationsSec.mapIndexed { i, sec ->
            Track("t$i", "Artist $i", "Title $i", sec * 1000L, uri = "spotify:track:t$i")
        }

    /** One refused track is skipped without a notice. */
    @Test
    fun `a track the engine refuses is skipped rather than ending playback`() =
        runTest {
            val rows = tracks(10, 20, 30)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refuses += "spotify:track:t0"
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals("a refused track is skipped", 1, backend.state.value.currentIndex)
            assertNull("one refusal raises no notice", backend.state.value.notice)
        }

    /**
     * A session that became invalid refuses every track until it is reopened.
     * A reopen that works raises no notice.
     */
    @Test
    fun `a connection that has died is reopened rather than announced`() =
        runTest {
            val rows = tracks(10, 20, 30, 40)
            val engine = FakeEngine(backgroundScope, rows)
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertTrue("the engine is asked to reconnect: ${engine.told}", engine.told.contains("reopen()"))
            assertNull("a reconnect that works raises no notice", backend.state.value.notice)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `a source that refuses on a fresh connection too stops with a notice`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertEquals(Transport.Stopped, backend.state.value.transport)
        }

    /**
     * Guards against a failed stop being answered with another stop. The
     * fake's event buffer would overflow in that loop.
     */
    @Test
    fun `a stop that fails on an engine that never opened does not ask for another stop`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.neverOpened = true
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertTrue(
                "fewer than four stops are sent (${engine.told.size} calls in all)",
                engine.told.count { it == "stop()" } < 4,
            )
        }

    @Test
    fun `audio is what takes the notice back`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()

            engine.refuses.clear()
            engine.refusesSurviveReopen = false
            backend.play()
            runCurrent()
            engine.say(EngineEvent.Playing(500))
            runCurrent()

            assertNull(backend.state.value.notice)
        }

    /** After the notice the listener's next press is a fresh attempt and reopens the connection again. */
    @Test
    fun `after saying so, the next press is given a fresh connection again`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            engine.told.clear()

            backend.play()
            runCurrent()

            assertTrue("the press after the notice reopens the connection: ${engine.told}", "reopen()" in engine.told)
        }

    /** A second notice gets a higher `noticeSeq`, so a watcher can tell it from the first. */
    @Test
    fun `a second notice after the next press has a higher sequence number`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            engine.refuses += "spotify:track:t0"
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            val first = backend.state.value

            backend.play()
            runCurrent()

            val second = backend.state.value
            assertEquals(BackendNotice.SourceCannotPlay, second.notice)
            assertEquals(Transport.Stopped, second.transport)
            assertTrue("the second notice has a higher sequence number: $first, $second", second.noticeSeq > first.noticeSeq)
        }

    /** A queue that ends before the count can decide: its end stands in for the rest of the run. */
    @Test
    fun `a queue of one on a cut-off account stops with a notice`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            engine.refuses += "spotify:track:t0"
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertTrue("a fresh connection is tried first: ${engine.told}", "reopen()" in engine.told)
        }

    @Test
    fun `a short queue on a cut-off account says so too`() =
        runTest {
            val rows = tracks(10, 20, 30, 40)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertEquals(Transport.Stopped, backend.state.value.transport)
        }

    @Test
    fun `a press on the last row of a long queue on a cut-off account says so too`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refusesSurviveReopen = true
            rows.forEach { engine.refuses += it.uri!! }
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.playAt(6)
            runCurrent()

            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertEquals(6, backend.state.value.currentIndex)
        }

    @Test
    fun `a last row that plays on a fresh connection is not news`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            engine.refuses += "spotify:track:t0"
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(Transport.Playing, backend.state.value.transport)
            assertNull(backend.state.value.notice)
            assertEquals(2, engine.told.count { it == "load(spotify:track:t0, true, 0)" })
        }

    /**
     * A failed connect is not the row's fault: the cursor stays, and the
     * reconnect schedule runs before the notice; see
     * [LibrespotBackendReconnectTest].
     */
    @Test
    fun `a connection that will not open keeps the row the listener pressed`() =
        runTest {
            val rows = tracks(10, 20, 30, 40, 50, 60, 70)
            val engine = FakeEngine(backgroundScope, rows)
            engine.failsToConnect = "could not connect: no route to host"
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.playAt(2)
            runCurrent()
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertTrue("the row is shown as reconnecting", backend.state.value.connecting)
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals("the cursor stays on the pressed row", 2, backend.state.value.currentIndex)
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertTrue("the connection is tried again: ${engine.told}", "reopen()" in engine.told)
            assertTrue(engine.told.filter { it.startsWith("load") }.all { it.startsWith("load(spotify:track:t2") })
        }

    /** A fresh connection does not change an account that is not Premium. */
    @Test
    fun `an account that is not Premium is said at once`() =
        runTest {
            val rows = tracks(10, 20, 30)
            val engine = FakeEngine(backgroundScope, rows)
            engine.failsToConnect = EngineLine.NOT_PREMIUM
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertFalse("a non-Premium account gets no reconnect: ${engine.told}", "reopen()" in engine.told)
        }

    @Test
    fun `a uri the engine cannot read is that row's hole`() =
        runTest {
            val rows = tracks(10, 20, 30)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()

            engine.say(EngineEvent.Failed(EngineLine.NOT_A_TRACK))
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertFalse(engine.told.contains("reopen()"))
        }

    /** The engine can still be answering for the last track as a skip lands. */
    @Test
    fun `a refusal for a track already skipped past is not charged to the next one`() =
        runTest {
            val rows = tracks(10, 20, 30, 40)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            backend.next()

            repeat(3) { engine.say(EngineEvent.Unavailable("spotify:track:t0")) }
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertFalse("stale refusals are not counted as a run: ${engine.told}", engine.told.contains("reopen()"))
        }

    /** A refusal the backend cannot place is not ignored. */
    @Test
    fun `a refusal that names no row in the queue is taken as the playing one's`() =
        runTest {
            val rows = tracks(10, 20, 30)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()

            engine.say(EngineEvent.Unavailable("spotify:track:written-some-other-way"))
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }
}
