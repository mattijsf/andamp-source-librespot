// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.flow.Flow

/**
 * What the backend needs of librespot.
 *
 * An interface, so the backend is tested on the JVM against a fake with no
 * engine, no account and no device.
 *
 * The samples have to be read. The engine pushes back when nobody reads them
 * (a full buffer blocks its decoder), so playback stalls at position zero
 * unless whoever opens an engine drains it.
 *
 * One track at a time. librespot's player holds one track and has no queue,
 * shuffle or repeat; those are in
 * [nl.mattix.andamp.core.playback.TransportRules] above this.
 */
interface PlaybackEngine {
    /**
     * What the engine has to say, in the order it says it, losing nothing.
     *
     * An engine can refuse a track from inside the call that loads it, before
     * the backend's collector has started. So an implementation buffers (a
     * channel, not a shared flow) and every event reaches the one collector.
     */
    val events: Flow<EngineEvent>

    /**
     * Start this track. [positionMs] is where to begin, which a resumed session
     * uses and a fresh play leaves at zero.
     */
    fun load(
        trackUri: String,
        startPlaying: Boolean,
        positionMs: Long,
    )

    fun play()

    fun pause()

    /** Stop and let go of the track; the next play is a fresh [load]. */
    fun stop()

    fun seekTo(positionMs: Long)

    /** 0..1, whatever the engine's own scale is underneath. */
    fun setVolume(fraction: Float)

    /**
     * Throw away whatever connection this has and make a fresh one.
     *
     * A connection can become unusable, and then every later request fails
     * the same way. The caller counts failures and asks for this.
     *
     * Fire and forget like every other verb: what happened arrives as events,
     * and a [load] issued while this is still reconnecting is applied when it
     * lands.
     */
    fun reopen()

    fun release()
}

/**
 * What the engine says. Position arrives through these events and nowhere
 * else: the backend has no clock of its own.
 */
sealed interface EngineEvent {
    data class Playing(
        val positionMs: Long,
    ) : EngineEvent

    data class Paused(
        val positionMs: Long,
    ) : EngineEvent

    /** The tick, at whatever interval the engine was asked for. */
    data class PositionChanged(
        val positionMs: Long,
    ) : EngineEvent

    /**
     * This track is over; what follows is decided by the transport rules.
     *
     * Said when the decoder reaches the end, which is before the speaker does:
     * what was decoded last is still on its way to be heard.
     */
    data object EndOfTrack : EngineEvent

    /**
     * The track that just started plays from a file of [kbps].
     *
     * Said once a track's file is open. It can differ from the quality the
     * listener chose: a track with no file at that quality plays the one
     * librespot falls back to.
     */
    data class Bitrate(
        val kbps: Int,
    ) : EngineEvent

    /**
     * The track could not be decrypted or is not available here.
     *
     * One event for two causes: the engine cannot tell them apart. The caller
     * counts them to tell an account that is refused from one missing track.
     */
    data class Unavailable(
        val trackUri: String,
    ) : EngineEvent

    /**
     * The engine could not do what it was asked, in its own words.
     *
     * Usually the connection: no network, nobody signed in, a connect that was
     * refused. That is not the row's fault, so it is not answered the way
     * [Unavailable] is. Two reasons change what to do and are named in
     * [EngineLine]: a uri that is not a track, and an account that is not
     * Premium. The rest are for the log.
     */
    data class Failed(
        val reason: String,
    ) : EngineEvent
}
