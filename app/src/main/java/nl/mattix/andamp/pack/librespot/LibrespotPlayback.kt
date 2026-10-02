// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import java.io.File

/**
 * Builds the playback backend for the service, over the real engine.
 *
 * A factory, because the JNI engine and the sample source it assembles are
 * internal. What crosses out is a [PlaybackBackend].
 *
 * [out] is where the samples go. The pack hands in an [AudioOut] that carries
 * them to the player instead of playing them.
 */
object LibrespotPlayback {
    /**
     * Whether the engine is in this process. False on a JVM, and false in a
     * build packaged without the native libraries. Every surface asks this
     * before it draws anything of the service.
     */
    val available: Boolean get() = NativeLibrary.loaded

    /** Which librespot is inside, for a settings page to name; null without the engine. */
    val engineVersion: String? get() = if (available) runCatching { Librespot.nativeVersion() }.getOrNull() else null

    /**
     * Sets the quality every player the engine builds from now on fetches at;
     * see [StreamingQuality]. Does nothing without the engine.
     *
     * The song already playing keeps its quality; the next song started uses
     * the new one.
     */
    fun setStreamingQuality(kbps: Int) {
        if (available) Librespot.nativeSetBitrate(StreamingQuality.of(kbps))
    }

    /**
     * [network] is what a dropped connection waits on before it is tried
     * again; see [TrackRedial]. The budget's clock is the phone's elapsed
     * time.
     */
    fun backend(
        cacheDir: File,
        tracks: List<Track>,
        startIndex: Int,
        scope: CoroutineScope,
        out: AudioOut?,
        network: NetworkWatch = NetworkWatch.Assumed,
    ): PlaybackBackend =
        LibrespotBackend(
            tracks = tracks,
            scope = scope,
            engine = LibrespotEngine(cacheDir.absolutePath, scope),
            out = out,
            pcm = LibrespotPcm,
            startIndex = startIndex,
            network = network,
            clock = android.os.SystemClock::elapsedRealtime,
        )
}
