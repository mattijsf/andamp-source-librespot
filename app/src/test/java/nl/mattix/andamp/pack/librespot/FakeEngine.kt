// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.Track

/**
 * A fake of librespot's player as the backend sees it.
 *
 * It holds one track and knows nothing of what follows, its position is its
 * own clock, and once that track has ended, been stopped or been refused it
 * has nothing to act on: a seek, a play or a pause then does nothing, and only
 * a load works. It ticks on the scope it is handed, so a test's virtual clock
 * drives it.
 */
class FakeEngine(
    private val scope: CoroutineScope,
    private val tracks: List<Track>,
    private val tickMs: Long = 100,
    /** Where every call is also written, when a test asserts its order against another fake's calls. */
    private val log: MutableList<String>? = null,
) : PlaybackEngine {
    // a channel, because PlaybackEngine.events must lose nothing: refusing a
    // track happens inside load(), before the backend's collector exists
    private val outbox = Channel<EngineEvent>(capacity = 64)
    override val events = outbox.receiveAsFlow()

    /** Emits [event] as the engine would. */
    fun say(event: EngineEvent) = emit(event)

    /** Every call, in order, so a test can assert what the engine was told. */
    val told = mutableListOf<String>()

    /** The one track it can act on, or null when it has none. */
    private var holding: Track? = null
    private var positionMs = 0L
    private var ticker: Job? = null

    /** Tracks the engine will refuse. */
    val refuses = mutableSetOf<String>()

    /** Refusals stay after a reopen: an account that is refused, not a session that became invalid. */
    var refusesSurviveReopen = false

    /** A connection that will not open: every load is answered with this failure, also after a reopen. */
    var failsToConnect: String? = null

    override fun load(
        trackUri: String,
        startPlaying: Boolean,
        positionMs: Long,
    ) {
        record("load($trackUri, $startPlaying, $positionMs)")
        stopTicking()
        holding = null
        this.positionMs = positionMs
        failsToConnect?.let { reason ->
            emit(EngineEvent.Failed(reason))
            return
        }
        if (trackUri in refuses) {
            emit(EngineEvent.Unavailable(trackUri))
            return
        }
        holding = tracks.firstOrNull { it.uri == trackUri }
        if (startPlaying) startTicking()
        if (startPlaying) emit(EngineEvent.Playing(positionMs))
    }

    override fun play() {
        record("play()")
        if (holding == null) return
        startTicking()
        emit(EngineEvent.Playing(positionMs))
    }

    override fun pause() {
        record("pause()")
        if (holding == null) return
        stopTicking()
        emit(EngineEvent.Paused(positionMs))
    }

    /** Makes every stop answer with a failure. */
    var neverOpened = false

    override fun stop() {
        record("stop()")
        if (neverOpened) emit(EngineEvent.Failed("the engine is not open"))
        stopTicking()
        holding = null
        positionMs = 0
    }

    override fun seekTo(positionMs: Long) {
        record("seekTo($positionMs)")
        if (holding == null) return
        this.positionMs = positionMs
    }

    override fun setVolume(fraction: Float) {
        record("setVolume($fraction)")
    }

    /** A fresh connection: whatever was refusing stops, unless [refusesSurviveReopen] is set. */
    override fun reopen() {
        record("reopen()")
        if (!refusesSurviveReopen) refuses.clear()
    }

    /**
     * The network going mid-track, as librespot reports it: the engine says
     * the track ended and holds nothing.
     */
    fun dropMidTrack() {
        stopTicking()
        holding = null
        emit(EngineEvent.EndOfTrack)
    }

    override fun release() {
        record("release()")
        stopTicking()
    }

    private fun record(call: String) {
        told += call
        log?.add(call)
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (true) {
                    delay(tickMs)
                    val duration = holding?.durationMs ?: return@launch
                    positionMs += tickMs
                    if (positionMs >= duration) {
                        positionMs = duration
                        // the decoder is done with it, and so is the engine
                        holding = null
                        emit(EngineEvent.EndOfTrack)
                        return@launch
                    }
                    emit(EngineEvent.PositionChanged(positionMs))
                }
            }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun emit(event: EngineEvent) {
        check(outbox.trySend(event).isSuccess) { "the fake's event buffer has room" }
    }
}
