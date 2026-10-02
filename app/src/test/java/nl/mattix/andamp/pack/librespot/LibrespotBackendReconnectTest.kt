// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.StreamReconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A connection that drops under a track is recovered on the SDK's reconnect
 * schedule, with nothing tried while there is no network, and the song resumes
 * at the position last heard.
 *
 * The schedule is [StreamReconnect]'s and is tested there. This asserts that
 * the backend follows it: which row, which position, when the engine is asked,
 * and what the listener is shown meanwhile.
 */
class LibrespotBackendReconnectTest {
    private val rows =
        listOf(
            Track("t0", "Artist 0", "Title 0", 200_000L, uri = "spotify:track:t0"),
            Track("t1", "Artist 1", "Title 1", 200_000L, uri = "spotify:track:t1"),
        )

    /** A network the test switches off and on, and can see being watched. */
    private class Network(
        override var online: Boolean = true,
    ) : NetworkWatch {
        var hear: ((Boolean) -> Unit)? = null

        override fun watch(onChange: (online: Boolean) -> Unit) {
            hear = onChange
        }

        override fun unwatch() {
            hear = null
        }

        fun goes() {
            online = false
            hear?.invoke(false)
        }

        fun comesBack() {
            online = true
            hear?.invoke(true)
        }
    }

    /** A random source that always returns the middle, so every wait is its rung of the schedule. */
    private object Middle : Random() {
        override fun nextBits(bitCount: Int) = 1 shl (bitCount - 1)
    }

    private val network = Network()

    private fun TestScope.backendOn(engine: FakeEngine) =
        LibrespotBackend(
            rows,
            backgroundScope,
            engine,
            network = network,
            clock = { testScheduler.currentTime },
            jitter = Middle,
        )

    /** Played ten seconds in, then the connection drops and will not open again until the test says. */
    private fun TestScope.droppedTenSecondsIn(): Pair<FakeEngine, LibrespotBackend> {
        val engine = FakeEngine(backgroundScope, rows)
        val backend = backendOn(engine)
        backend.play()
        advanceTimeBy(10_001)
        runCurrent()
        engine.failsToConnect = "could not connect: no route to host"
        engine.told.clear()
        engine.dropMidTrack()
        runCurrent()
        return engine to backend
    }

    private fun FakeEngine.loads() = told.count { it.startsWith("load") }

    @Test
    fun `a drop mid-track keeps the row playing and connecting rather than skipping`() =
        runTest {
            val (engine, backend) = droppedTenSecondsIn()

            val state = backend.state.value
            assertEquals("a drop keeps the current row", 0, state.currentIndex)
            assertEquals(Transport.Playing, state.transport)
            assertTrue("the listener is shown it is reconnecting", state.connecting)
            assertNull(state.notice)
            assertEquals("no try comes before the first wait: ${engine.told}", 0, engine.loads())
            assertFalse(engine.told.any { it.contains("t1") })
        }

    @Test
    fun `tries follow the backoff, from a second doubling to thirty`() =
        runTest {
            val (engine, _) = droppedTenSecondsIn()
            // each wait is counted from the failure before it, and a failure
            // comes back inside the load that was tried
            val waits = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)

            waits.forEachIndexed { i, wait ->
                advanceTimeBy(wait - 1)
                runCurrent()
                assertEquals("try ${i + 1} waits ${wait}ms", i, engine.loads())
                advanceTimeBy(1)
                runCurrent()
                assertEquals("try ${i + 1} comes after its ${wait}ms wait", i + 1, engine.loads())
            }
            assertTrue("a try reopens the connection: ${engine.told}", "reopen()" in engine.told)
        }

    @Test
    fun `a track that comes back resumes where it was heard to`() =
        runTest {
            val (engine, backend) = droppedTenSecondsIn()
            engine.failsToConnect = null

            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(listOf("reopen()", "load(spotify:track:t0, true, 10000)"), engine.told)
            advanceTimeBy(1_000)
            runCurrent()
            val state = backend.state.value
            assertEquals(Transport.Playing, state.transport)
            assertFalse("connecting ends once the music is back", state.connecting)
            assertTrue(state.positionMs > 10_000)
            assertNull("the network is not watched once the track resumes", network.hear)
        }

    @Test
    fun `nothing is tried while there is no network, and one try comes when it is back`() =
        runTest {
            network.online = false
            val (engine, backend) = droppedTenSecondsIn()

            advanceTimeBy(30 * 60_000L)
            runCurrent()

            assertEquals("nothing is tried without a network: ${engine.told}", 0, engine.loads())
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertTrue(backend.state.value.connecting)
            assertNull("time offline does not spend the budget", backend.state.value.notice)

            engine.failsToConnect = null
            network.comesBack()
            advanceTimeBy(StreamReconnect.SETTLE_MS - 1)
            runCurrent()
            assertEquals(0, engine.loads())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("reopen()", "load(spotify:track:t0, true, 10000)"), engine.told)
        }

    @Test
    fun `the network going away mid-wait calls the timer off`() =
        runTest {
            val (engine, _) = droppedTenSecondsIn()

            network.goes()
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals("no wait runs out while the network is gone: ${engine.told}", 0, engine.loads())
        }

    @Test
    fun `a hand-over to another network is tried promptly`() =
        runTest {
            val (engine, _) = droppedTenSecondsIn()
            advanceTimeBy(1_001)
            runCurrent()
            // the second wait is two seconds; a new network does not wait it out
            assertEquals(1, engine.loads())

            network.comesBack()
            advanceTimeBy(StreamReconnect.SETTLE_MS + 1)
            runCurrent()

            assertEquals(2, engine.loads())
        }

    @Test
    fun `a pause calls the reconnect off`() =
        runTest {
            val (engine, backend) = droppedTenSecondsIn()

            backend.pause()
            advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals("a pause stops the tries: ${engine.told}", 0, engine.loads())
            assertEquals(Transport.Paused, backend.state.value.transport)
            assertFalse(backend.state.value.connecting)
            assertNull("a pause stops watching the network", network.hear)
            assertNull(backend.state.value.notice)
        }

    @Test
    fun `a queue edited while reconnecting does not bring the try forward`() =
        runTest {
            val (engine, backend) = droppedTenSecondsIn()

            backend.enqueue(listOf(Track("t2", "Artist 2", "Title 2", 200_000L, uri = "spotify:track:t2")))
            runCurrent()

            assertEquals(0, engine.loads())
            assertTrue(backend.state.value.connecting)
        }

    @Test
    fun `a spent budget stops the row and says so`() =
        runTest {
            val (engine, backend) = droppedTenSecondsIn()

            advanceTimeBy(10 * 60_000L)
            runCurrent()

            val state = backend.state.value
            assertEquals(Transport.Stopped, state.transport)
            assertEquals(BackendNotice.SourceCannotPlay, state.notice)
            assertFalse(state.connecting)
            assertEquals("the cursor stays on the row the listener was on", 0, state.currentIndex)
            assertEquals("the tries stop at the budget: ${engine.told}", StreamReconnect.MAX_ATTEMPTS, engine.loads())
            assertNull(network.hear)
        }

    @Test
    fun `a refusal offline keeps the row and waits for the network`() =
        runTest {
            val engine = FakeEngine(backgroundScope, rows)
            val backend = backendOn(engine)
            network.online = false
            engine.refuses += "spotify:track:t0"

            backend.play()
            runCurrent()

            assertEquals("a refusal offline keeps the current row", 0, backend.state.value.currentIndex)
            assertTrue(backend.state.value.connecting)
            assertFalse("no reconnect is tried offline: ${engine.told}", "reopen()" in engine.told)
        }

    @Test
    fun `a refusal of a row that was already playing is the connection`() =
        runTest {
            val engine = FakeEngine(backgroundScope, rows)
            val backend = backendOn(engine)
            backend.play()
            advanceTimeBy(5_001)
            runCurrent()
            // a session whose socket died unnoticed: the key times out
            engine.refuses += "spotify:track:t0"
            engine.say(EngineEvent.Unavailable("spotify:track:t0"))
            runCurrent()

            assertEquals(0, backend.state.value.currentIndex)
            assertTrue(backend.state.value.connecting)
        }

    @Test
    fun `an end at the row's duration moves on to the next row`() =
        runTest {
            val short = listOf(rows[0].copy(durationMs = 20_000L), rows[1])
            val engine = FakeEngine(backgroundScope, short)
            val backend =
                LibrespotBackend(
                    short,
                    backgroundScope,
                    engine,
                    network = network,
                    clock = { testScheduler.currentTime },
                )
            backend.play()

            advanceTimeBy(20_001)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertFalse(backend.state.value.connecting)
        }
}
