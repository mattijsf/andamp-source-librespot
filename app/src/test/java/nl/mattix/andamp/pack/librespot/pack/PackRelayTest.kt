// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.pack.common.PackRelay
import nl.mattix.andamp.pack.librespot.EngineEvent
import nl.mattix.andamp.pack.librespot.FakeEngine
import nl.mattix.andamp.pack.librespot.LibrespotBackend
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a listening player is told: the state the real backend reached, over the fake engine. */
class PackRelayTest {
    @Test
    fun `a listener hears the queue and the transport the backend reached`() =
        runTest {
            val said = mutableListOf<PackState>()
            val backend = relayed(said)

            backend.play()
            runCurrent()

            val last = said.last()
            assertEquals(Transport.Playing.name, last.transport)
            assertEquals(listOf("Hexagons", "Cryogen"), last.queue.map { it.title })
            assertEquals(0, last.currentIndex)
        }

    @Test
    fun `a skip reaches the listener as the row it landed on`() =
        runTest {
            val said = mutableListOf<PackState>()
            val backend = relayed(said)

            backend.play()
            backend.next()
            runCurrent()

            val last = said.last()
            assertEquals(1, last.currentIndex)
            assertEquals("Cryogen", last.queue[last.currentIndex].title)
        }

    @Test
    fun `the bitrate the engine said reaches the listener as the stream's`() =
        runTest {
            val said = mutableListOf<PackState>()
            val engine = FakeEngine(backgroundScope, tracks)
            val backend = relayed(said, engine)

            backend.play()
            engine.say(EngineEvent.Bitrate(320))
            runCurrent()

            assertEquals(320, said.last().streamBitrateKbps)
            assertEquals("the stream sample rate is 44 kHz", 44, said.last().streamSampleRateKhz)
        }

    private val tracks =
        listOf(
            Track(id = "1", artist = "Muse", title = "Hexagons", durationMs = 200_000, uri = "spotify:track:1"),
            Track(id = "2", artist = "Muse", title = "Cryogen", durationMs = 200_000, uri = "spotify:track:2"),
        )

    /** A backend over [engine], with everything it says collected into [said]. */
    private fun TestScope.relayed(
        said: MutableList<PackState>,
        engine: FakeEngine = FakeEngine(backgroundScope, tracks),
    ): LibrespotBackend {
        val backend = LibrespotBackend(tracks, backgroundScope, engine)
        backgroundScope.launch { PackRelay.relay(backend.state, said::add) }
        runCurrent()
        return backend
    }
}
