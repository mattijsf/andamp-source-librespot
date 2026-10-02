// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the engine is told when the backend applies a rule.
 *
 * The contract suite asserts the rules. This asserts the instruction, which is
 * the difference between two states and not the verb that was called. Tracks
 * that will not play are in [LibrespotBackendRefusalTest].
 */
class LibrespotBackendEngineTest {
    private fun tracks(vararg durationsSec: Int) =
        durationsSec.mapIndexed { i, sec ->
            Track("t$i", "Artist $i", "Title $i", sec * 1000L, uri = "spotify:track:t$i")
        }

    @Test
    fun `play from stopped loads the track rather than resuming one`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.play()
            runCurrent()

            assertEquals(listOf("load(spotify:track:t0, true, 0)"), engine.told)
        }

    @Test
    fun `pause and pause again resumes rather than reloading`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            engine.told.clear()

            backend.pause()
            backend.pause()
            runCurrent()

            assertEquals(listOf("pause()", "play()"), engine.told)
        }

    @Test
    fun `next while playing loads what it landed on`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            engine.told.clear()

            backend.next()
            runCurrent()

            assertEquals(listOf("load(spotify:track:t1, true, 0)"), engine.told)
        }

    @Test
    fun `a seek that changes nothing is not sent`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            engine.told.clear()

            backend.seekTo(0)

            assertTrue("the engine is told nothing: ${engine.told}", engine.told.isEmpty())
        }

    /** The engine has nothing to seek in while stopped; the next load is where the seek lands. */
    @Test
    fun `a seek while stopped is where the next load starts`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)

            backend.seekTo(4_000)
            backend.play()
            runCurrent()

            assertEquals(listOf("load(spotify:track:t0, true, 4000)"), engine.told)
            assertEquals(4_000L, backend.state.value.positionMs)
        }

    @Test
    fun `a position that arrives after a pause does not move the clock`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            advanceTimeBy(301)
            runCurrent()
            val stoppedAt = backend.state.value.positionMs
            backend.pause()

            engine.say(EngineEvent.PositionChanged(9_000))
            runCurrent()

            assertEquals(stoppedAt, backend.state.value.positionMs)
        }

    /** Winamp's kbps readout reads the state: the file the engine says it opened. */
    @Test
    fun `the bitrate the engine opened the track at is the stream's`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()

            engine.say(EngineEvent.Bitrate(320))
            runCurrent()

            assertEquals(320, backend.state.value.streamBitrateKbps)
            assertEquals("the stream sample rate is 44 kHz", 44, backend.state.value.streamSampleRateKhz)
        }

    @Test
    fun `another track or a stop forgets the last file's bitrate`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            engine.say(EngineEvent.Bitrate(320))
            runCurrent()

            backend.next()
            assertNull(backend.state.value.streamBitrateKbps)
            assertNull(backend.state.value.streamSampleRateKhz)

            engine.say(EngineEvent.Bitrate(160))
            runCurrent()
            assertEquals(160, backend.state.value.streamBitrateKbps)

            backend.stop()
            assertNull(backend.state.value.streamBitrateKbps)
            assertNull("a stop clears the sample rate", backend.state.value.streamSampleRateKhz)
        }

    @Test
    fun `a bitrate that arrives after a stop is not shown`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            backend.stop()

            engine.say(EngineEvent.Bitrate(320))
            runCurrent()

            assertNull(backend.state.value.streamBitrateKbps)
        }

    /** The capability flags follow whether there is anywhere to render. */
    @Test
    fun `without somewhere to render, the equalizer and the rack say so`() =
        runTest {
            val rows = tracks(10)
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows))

            assertEquals(false, backend.capabilities.hasEqualizer)
            assertEquals(false, backend.capabilities.hasDsp)
            assertEquals(null, backend.audioTap)
        }

    @Test
    fun `with somewhere to render, they say that instead`() =
        runTest {
            val rows = tracks(10)
            val backend =
                LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), FakeOut())

            assertEquals(true, backend.capabilities.hasEqualizer)
            assertEquals(true, backend.capabilities.hasDsp)
        }

    /**
     * The engine pushes back when nobody reads, so a load issued before
     * anything drains stalls its decoder. One log for both fakes, so the order
     * across them is the order of the calls.
     */
    @Test
    fun `the samples are being taken before any are asked for`() =
        runTest {
            val rows = tracks(10)
            val log = mutableListOf<String>()
            val engine = FakeEngine(backgroundScope, rows, log = log)
            val out = FakeOut(log)
            val backend = LibrespotBackend(rows, backgroundScope, engine, out, PcmProvider { 0 })

            backend.play()
            runCurrent()

            assertEquals(listOf("out.start", "load(spotify:track:t0, true, 0)"), log)
        }

    @Test
    fun `a stop lets go of the device`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend =
                LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()

            backend.stop()

            assertEquals(listOf("start", "stop"), out.order)
        }

    /**
     * A paused engine only stops decoding; the output is paused too, so what
     * was decoded ahead is not heard.
     */
    @Test
    fun `pause silences the speaker at once and play picks it up again`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()
            out.buffered.clear()

            backend.pause()
            backend.pause()

            assertEquals(listOf("pause", "resume"), out.buffered)
        }

    @Test
    fun `a restart is a seek to the top, and what was decoded ahead is dropped`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, engine, out, PcmProvider { 0 })
            backend.play()
            advanceTimeBy(301)
            runCurrent()
            engine.told.clear()
            out.buffered.clear()

            backend.play()

            assertEquals("a restart seeks to zero", listOf("seekTo(0)"), engine.told)
            assertEquals(listOf("discard"), out.buffered)
            assertEquals(0L, backend.state.value.positionMs)
        }

    /** Winamp restarts a row picked again, paused or not. */
    @Test
    fun `picking the paused row again plays it from the top`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            advanceTimeBy(301)
            runCurrent()
            backend.pause()
            runCurrent()
            engine.told.clear()

            backend.playAt(0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(listOf("seekTo(0)", "play()"), engine.told)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals("the position counts from the top again", 100L, backend.state.value.positionMs)
        }

    @Test
    fun `a skip in a queue of one while paused plays it from the top`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            advanceTimeBy(301)
            runCurrent()
            backend.pause()
            runCurrent()
            engine.told.clear()

            backend.next()
            advanceTimeBy(150)
            runCurrent()

            assertEquals(listOf("seekTo(0)", "play()"), engine.told)
            assertEquals(100L, backend.state.value.positionMs)
        }

    @Test
    fun `a seek and a new track drop what was decoded for the old place`() =
        runTest {
            val rows = tracks(10, 20)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()
            out.buffered.clear()

            backend.seekTo(5_000)
            backend.next()

            assertEquals(listOf("discard", "resume", "discard"), out.buffered)
        }

    /**
     * The engine says a track has ended when it has decoded the end, and the
     * last of it is still on its way to the speaker.
     */
    @Test
    fun `a track that ends on its own is heard to its end before the next one`() =
        runTest {
            val rows = tracks(1, 10)
            val engine = FakeEngine(backgroundScope, rows)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, engine, out, PcmProvider { 0 })
            backend.play()
            runCurrent()
            out.buffered.clear()

            advanceTimeBy(1_001)
            runCurrent()

            assertTrue("the next track is loaded: ${engine.told}", "load(spotify:track:t1, true, 0)" in engine.told)
            assertFalse("the end of the track is not discarded", "discard" in out.buffered)
        }

    @Test
    fun `the last track of a queue is heard to its end before the device is let go of`() =
        runTest {
            val rows = tracks(1)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals("the device is kept while the last second plays", listOf("start"), out.order)

            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf("start", "stop"), out.order)
        }

    @Test
    fun `a press while that last second plays keeps the device`() =
        runTest {
            val rows = tracks(10, 1)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.playAt(1)
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(Transport.Stopped, backend.state.value.transport)

            backend.playAt(0)
            // past the moment the finished queue would have let go of it
            advanceTimeBy(2_500)
            runCurrent()

            assertFalse("the device is kept while a track plays: ${out.order}", "stop" in out.order)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    /**
     * librespot lets go of a track once it has ended, and a seek or a play
     * then does nothing. Only a load plays it again.
     */
    @Test
    fun `a queue of one on repeat loads the track again when it ends`() =
        runTest {
            val rows = tracks(1)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.setRepeat(true)
            backend.play()

            advanceTimeBy(1_001)
            runCurrent()
            advanceTimeBy(301)
            runCurrent()

            assertEquals(2, engine.told.count { it == "load(spotify:track:t0, true, 0)" })
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(300L, backend.state.value.positionMs)
        }

    @Test
    fun `shuffle landing on the track that just ended plays it again`() =
        runTest {
            val rows = tracks(1, 10)
            val engine = FakeEngine(backgroundScope, rows)
            // every pick is the first row, which is the one that ends
            val backend = LibrespotBackend(rows, backgroundScope, engine, random = FirstRow)
            backend.setShuffle(true)
            backend.play()

            advanceTimeBy(1_001)
            runCurrent()
            advanceTimeBy(301)
            runCurrent()

            assertEquals(2, engine.told.count { it == "load(spotify:track:t0, true, 0)" })
            assertEquals(300L, backend.state.value.positionMs)
        }

    @Test
    fun `a play after a refusal that landed while paused loads the track`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            backend.pause()
            engine.say(EngineEvent.Unavailable("spotify:track:t0"))
            runCurrent()
            engine.told.clear()

            backend.pause()
            runCurrent()

            assertEquals(listOf("load(spotify:track:t0, true, 0)"), engine.told)
        }

    /** A queue edit that keeps the playing track does not touch the engine or the audio. */
    @Test
    fun `a queue edit that moves the playing row does not load it again`() =
        runTest {
            val rows = tracks(10, 20, 30)
            val engine = FakeEngine(backgroundScope, rows)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, engine, out, PcmProvider { 0 })
            backend.playAt(1)
            advanceTimeBy(301)
            runCurrent()
            engine.told.clear()
            out.buffered.clear()

            backend.setQueue(listOf(rows[1], rows[2]))
            runCurrent()

            assertTrue("the engine is told nothing: ${engine.told}", engine.told.isEmpty())
            assertTrue("the audio is untouched: ${out.buffered}", out.buffered.isEmpty())
            assertEquals(0, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    /** A changed row with the queue unchanged is a new track: a skip onto another copy of the same one. */
    @Test
    fun `a skip onto a second copy of the same track starts it from the top`() =
        runTest {
            val once = tracks(10).single()
            val rows = listOf(once, once)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            advanceTimeBy(301)
            runCurrent()
            engine.told.clear()

            backend.next()

            assertEquals(listOf("load(spotify:track:t0, true, 0)"), engine.told)
        }

    @Test
    fun `the end of a track the listener paused on waits for the press that ends the pause`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            advanceTimeBy(901)
            runCurrent()
            backend.pause()
            // decoded to its end just before the pause landed
            engine.say(EngineEvent.EndOfTrack)
            runCurrent()
            assertEquals(Transport.Paused, backend.state.value.transport)
            assertEquals(0, backend.state.value.currentIndex)
            engine.told.clear()

            backend.pause()
            runCurrent()

            assertEquals("the track reloads at the paused position", listOf("load(spotify:track:t0, true, 900)"), engine.told)
        }

    @Test
    fun `the end of a track the listener stopped does not move the cursor`() =
        runTest {
            val rows = tracks(10, 20)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.play()
            runCurrent()
            backend.stop()

            engine.say(EngineEvent.EndOfTrack)
            runCurrent()

            assertEquals(0, backend.state.value.currentIndex)
            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertFalse(engine.told.any { it.startsWith("load(spotify:track:t1") })
        }

    @Test
    fun `stop after current stops at the end of that track and loads nothing more`() =
        runTest {
            val rows = tracks(1, 10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.setStopAfterCurrent(true)
            backend.play()

            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(0, backend.state.value.currentIndex)
            assertFalse(engine.told.any { it.startsWith("load(spotify:track:t1") })
        }

    @Test
    fun `stop after current taken off again lets the queue carry on`() =
        runTest {
            val rows = tracks(1, 10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.setStopAfterCurrent(true)
            backend.setStopAfterCurrent(false)
            backend.play()

            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(1, backend.state.value.currentIndex)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    /** In this mode the player moves the device volume; attenuating here too would apply it twice. */
    @Test
    fun `when the slider is the phone's volume, this renders at full gain`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.setVolumeMode(VolumeMode.DEVICE)

            backend.setVolume(0.5f)

            assertEquals(1f, out.volumes.last(), 0f)
            assertEquals("the slider keeps its position", 0.5f, backend.state.value.volumeFraction, 0f)
        }

    /** With an output, the gain goes to the output and not to the engine's mixer. */
    @Test
    fun `when the slider is the app's own, the volume goes to whoever renders`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, engine, out, PcmProvider { 0 })
            backend.setVolumeMode(VolumeMode.APP)

            backend.setVolume(0.5f)

            assertEquals(0.5f, out.volumes.last(), 0f)
            assertEquals(true, engine.told.none { it.startsWith("setVolume") })
        }

    @Test
    fun `switching whose volume it is moves the gain with it`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.setVolumeMode(VolumeMode.APP)
            backend.setVolume(0.4f)

            backend.setVolumeMode(VolumeMode.DEVICE)
            assertEquals(1f, out.volumes.last(), 0f)

            backend.setVolumeMode(VolumeMode.APP)
            assertEquals(0.4f, out.volumes.last(), 0f)
        }

    @Test
    fun `the volume the listener set is what the engine is told`() =
        runTest {
            val rows = tracks(10)
            val engine = FakeEngine(backgroundScope, rows)
            val backend = LibrespotBackend(rows, backgroundScope, engine)
            backend.setVolumeMode(VolumeMode.APP)

            backend.setVolume(1.4f)
            assertEquals(1f, backend.state.value.volumeFraction, 0f)

            backend.setVolume(0.4f)
            assertEquals("setVolume(0.4)", engine.told.last())
        }

    @Test
    fun `a call pauses the music and its end brings it back`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()

            out.interrupt(AudioOut.Interruption.PAUSE_FOR_NOW)
            assertEquals(Transport.Paused, backend.state.value.transport)

            out.interrupt(AudioOut.Interruption.RESUME)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `losing the speaker for good pauses and stays paused`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()

            out.interrupt(AudioOut.Interruption.PAUSE)
            out.interrupt(AudioOut.Interruption.RESUME)

            assertEquals(Transport.Paused, backend.state.value.transport)
        }

    @Test
    fun `a pause the listener chose during a call is not undone when the call ends`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            backend.play()
            runCurrent()
            out.interrupt(AudioOut.Interruption.PAUSE_FOR_NOW)

            backend.pause()
            backend.pause()
            out.interrupt(AudioOut.Interruption.RESUME)

            assertEquals(Transport.Paused, backend.state.value.transport)
        }

    @Test
    fun `a call while nothing plays starts nothing when it ends`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })

            out.interrupt(AudioOut.Interruption.PAUSE_FOR_NOW)
            out.interrupt(AudioOut.Interruption.RESUME)

            assertEquals(Transport.Stopped, backend.state.value.transport)
        }

    @Test
    fun `a released backend stops listening for interruptions`() =
        runTest {
            val rows = tracks(10)
            val out = FakeOut()
            val backend = LibrespotBackend(rows, backgroundScope, FakeEngine(backgroundScope, rows), out, PcmProvider { 0 })
            assertNotNull("the backend listens for interruptions", out.interruptions)

            backend.release()

            assertNull(out.interruptions)
        }

    /** Whatever the queue's size, every pick is its first row. */
    private object FirstRow : Random() {
        override fun nextBits(bitCount: Int) = 0
    }
}
