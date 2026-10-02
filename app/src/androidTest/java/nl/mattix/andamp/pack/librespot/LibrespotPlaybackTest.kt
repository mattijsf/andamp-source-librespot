// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.pack.common.PackAudioOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The backend driving the real engine on a device, decoding audio into the
 * pipe the player reads it from.
 *
 * The pack renders nothing, so the proof is bytes arriving at the far end of a
 * pipe.
 *
 * Needs a credential; see [DeviceCredential]. Without it every test here skips.
 */
@RunWith(AndroidJUnit4::class)
class LibrespotPlaybackTest {
    @Test
    fun theBackendHandsWhatItDecodesToWhoeverReadsThePipe() {
        val cache = DeviceCredential.cacheOrSkip()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipe = ParcelFileDescriptor.createPipe()
        val out = PackAudioOut()
        val crossed = AtomicLong()
        // somebody has to read the far end, as the player does: a pipe nobody
        // drains fills, and the engine's back-pressure then stalls its decoder
        drain(ParcelFileDescriptor.AutoCloseInputStream(pipe[READ_END]), crossed)
        val row = Track("t0", "Whoever", "Whatever", 0, uri = TRACK)
        val backend =
            LibrespotBackend(
                tracks = listOf(row),
                scope = scope,
                engine = LibrespotEngine(cache.absolutePath, scope),
                out = out,
                pcm = LibrespotPcm,
            )
        try {
            out.handOver(ParcelFileDescriptor.AutoCloseOutputStream(pipe[WRITE_END]))
            backend.play()
            val reached = awaitProgress(backend)

            assertEquals("the position moves past zero (transport ${backend.state.value.transport})", true, reached > 0)
            assertTrue("samples reach the player", crossed.get() > 0)
        } finally {
            // released, so its reader stops taking lines meant for the next
            // test's engine; the release also ends the pipe the drainer is on
            backend.release()
            scope.cancel()
        }
    }

    /**
     * A stop pressed while the session is still opening lets go of the track.
     *
     * The load waits for the session, and the stop has to drop it. Read off
     * the engine's own queue, because the backend ignores what arrives while
     * it is stopped.
     */
    @Test
    fun aStopWhileConnectingLetsGoOfTheLoad() {
        val cache = DeviceCredential.cacheOrSkip()
        // a fresh open, so the load has to wait for it
        Librespot.nativeClose()
        Librespot.nativeClaimEvents()
        Librespot.nativeOpen(cache.absolutePath)
        Librespot.nativeLoad(TRACK, true, 0)
        Librespot.nativeStop()

        assertTrue("the engine connects a session", DeviceCredential.awaitSession())
        val heard = linesFor(QUIET_MS)

        assertTrue("the stopped track does not play: $heard", heard.none { it.startsWith("playing") })
    }

    /** Waits for a position past zero, which means audio was decoded and read. */
    private fun awaitProgress(backend: LibrespotBackend): Long {
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        while (System.currentTimeMillis() < giveUp) {
            val state = backend.state.value
            if (state.positionMs > 0) return state.positionMs
            // a refusal stops it, which ends the wait too
            if (state.transport == Transport.Stopped) return 0
            Thread.sleep(POLL_MS)
        }
        return backend.state.value.positionMs
    }

    /**
     * Reads the far end of the pipe on a daemon thread, counting what crosses,
     * until the pipe ends.
     */
    private fun drain(
        from: InputStream,
        crossed: AtomicLong,
    ) {
        Thread({
            val buffer = ByteArray(READ_BYTES)
            runCatching {
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    crossed.addAndGet(read.toLong())
                }
            }
            runCatching { from.close() }
        }, "andamp-pack-audio-test").apply {
            isDaemon = true
            start()
        }
    }

    /** Every line the engine says for [ms], in order. */
    private fun linesFor(ms: Long): List<String> {
        val until = System.currentTimeMillis() + ms
        val heard = mutableListOf<String>()
        while (System.currentTimeMillis() < until) {
            Librespot.nativeNextEvent(POLL_MS).takeIf { it.isNotEmpty() }?.let(heard::add)
        }
        return heard
    }

    private companion object {
        /** Any playable track. */
        const val TRACK = "spotify:track:4JpAqx578F0rpHEsxgHE9D"
        const val PATIENCE_MS = 60_000L
        const val POLL_MS = 250L

        /** What [ParcelFileDescriptor.createPipe] answers with, in its order. */
        const val READ_END = 0
        const val WRITE_END = 1

        /** The buffer the drain reads into. */
        const val READ_BYTES = 16_384

        /**
         * Long enough for a load that was not let go of to have been fetched
         * and started: a key, the first chunk of the file, a decoder.
         */
        const val QUIET_MS = 8_000L
    }
}
