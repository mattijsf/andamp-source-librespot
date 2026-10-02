// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.QueuePatch
import nl.mattix.andamp.core.playback.StreamReconnect
import nl.mattix.andamp.core.playback.TransportRules
import kotlin.random.Random

/**
 * The service as a playback backend: Winamp's queue and rules above,
 * librespot's one track below.
 *
 * [PlaybackEngine] knows one track at a time. The queue, shuffle, repeat and
 * what a skip means come from [nl.mattix.andamp.core.playback.TransportRules].
 * This class applies a rule, compares the state before and after, and tells
 * the engine what changed. It takes the engine as a parameter, so the SDK's
 * contract suite runs against a fake.
 *
 * When the connection drops: librespot does not reconnect by itself. A
 * session whose socket dies is marked invalid, and the next thing that asks
 * the engine to open, which a load does, makes a fresh one. What the listener
 * meets depends on where in a track the drop lands:
 *
 * - Mid-track. What was fetched ahead plays out, then librespot gives up on
 *   the read and reports the track as ended. An end that comes well short of
 *   the row's duration is read as a drop ([cutShort]).
 * - At a load. A connect that fails says `failed could not connect`. A load
 *   on a session whose socket is dead but not yet noticed has its audio key
 *   time out and says the track is unavailable. Offline, an unavailable track
 *   is taken as the connection too.
 *
 * Each of those goes to [TrackRedial], on the SDK's [StreamReconnect] policy:
 * the row stays, the transport stays Playing with `connecting` set, tries are
 * spaced out with none while there is no network, and a spent budget stops
 * with [BackendNotice.SourceCannotPlay]. A try reconnects and loads the row
 * from the last position the engine reported.
 *
 * [Playability] handles what is not the connection: a refusal while online on
 * a row not yet heard, and the two reasons the engine names.
 */
@Suppress("TooManyFunctions") // implements all of PlaybackBackend and translates it into one engine's verbs
class LibrespotBackend(
    tracks: List<Track>,
    private val scope: CoroutineScope,
    private val engine: PlaybackEngine,
    /**
     * Where the samples are rendered.
     *
     * Null means the backend drives transport and metadata with nothing
     * rendering, which is what the JVM tests do. It also decides the
     * capability flags below.
     */
    private val out: AudioOut? = null,
    /** What the engine's samples are, when there is an [out] to take them. */
    private val pcm: PcmProvider? = null,
    startIndex: Int = 0,
    private val random: Random = Random.Default,
    /**
     * Whether there is a network to reconnect over; see [TrackRedial]. Assumed
     * online where nothing can say, and retries then run on the clock alone.
     */
    private val network: NetworkWatch = NetworkWatch.Assumed,
    /** The clock a reconnect's budget is kept on. Monotonic; a test hands in its virtual one. */
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    /** What spreads the waits between tries; see [StreamReconnect]. */
    jitter: Random = Random.Default,
) : PlaybackBackend {
    private val _state =
        MutableStateFlow(
            BackendState(
                queue = tracks,
                currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
            ),
        )
    override val state: StateFlow<BackendState> = _state

    /**
     * What this backend can do. The equalizer, the balance and the rack are
     * true only when there is an [out] holding them.
     */
    override val capabilities =
        if (out != null) RENDERING else RENDERING.copy(hasEqualizer = false, hasBalance = false, hasDsp = false)

    /** Non-null once samples are flowing. */
    override val audioTap get() = out?.tap

    /**
     * Whether librespot has a track it can seek in or resume.
     *
     * The rules decide by row and position, as if the engine always had one.
     * It lets go of a track once the track has ended, and never has one it
     * refused; a seek or a play then does nothing. With no track in the
     * engine, the only verb that plays is a load.
     */
    private var engineHoldsTrack = false

    /** Winamp's Stop after current track, as the menu last set it; see [ended]. */
    private var stopAfterCurrent = false

    /** Whose volume the slider is; see [setVolumeMode]. */
    private var volumeMode = VolumeMode.DEVICE

    /** Paused by a call or a prompt and not by the listener, so its end resumes playback. */
    private var pausedForNow = false

    /** The output being let go of once the last of a finished queue has been heard; see [letGoAfterTheTail]. */
    private var lettingGo: Job? = null

    /** How many tracks have refused in a row, and what that means; see [Playability]. */
    private val failures = Playability()

    /** The row whose audio has been heard since it was loaded, by uri; see [lostConnection]. */
    private var heardUri: String? = null

    /** Recovers a dropped connection; see the class KDoc. */
    private val redial =
        TrackRedial(
            scope = scope,
            network = network,
            policy = StreamReconnect(clock, jitter),
            retry = ::redialNow,
            gaveUp = ::tell,
            changed = ::redialMoved,
        )

    override fun setEqualizer(settings: nl.mattix.andamp.core.model.EqSettings) {
        out?.setEqualizer(settings)
    }

    override fun setBalance(balance: Float) {
        out?.setBalance(balance)
    }

    override fun setDsp(rack: nl.mattix.andamp.core.model.RackSettings) {
        out?.setDsp(rack)
    }

    override fun setPlugins(sources: List<String>) {
        out?.setPlugins(sources)
    }

    init {
        // a call, a prompt, another app or headphones pulled out reach the
        // output; what they mean for the transport is decided here
        out?.setInterruptions(::interrupted)
        scope.launch {
            engine.events.collect(::heard)
        }
    }

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) = obey { TransportRules.setQueue(it, tracks, startIndex) }

    // not a press about the music: a reconnect under way carries on
    override fun enqueue(tracks: List<Track>) = obey(pressed = false) { TransportRules.enqueue(it, tracks) }

    override fun patchTracks(patched: List<Track>) {
        // see QueuePatch
        _state.value = _state.value.copy(queue = QueuePatch.apply(_state.value.queue, patched))
    }

    override fun play() = obey(TransportRules::play)

    override fun pause() = obey(TransportRules::pause)

    override fun stop() = obey(TransportRules::stop)

    override fun next() = obey(TransportRules::next)

    override fun previous() = obey(TransportRules::previous)

    override fun playAt(index: Int) = obey { TransportRules.playAt(it, index) }

    override fun seekTo(positionMs: Long) {
        val before = _state.value
        val after = TransportRules.seekTo(before, positionMs)
        _state.value = after
        // a press while reconnecting ends the wait: this is a fresh try from
        // where the listener asked
        if (redial.pending) {
            redial.cancel()
            if (after.transport == Transport.Playing) reload(after)
            return
        }
        // with no track in the engine there is nothing to move: a seek while
        // stopped, or after a track has ended, is where the next load starts,
        // and the load reads it from the state
        if (after.positionMs == before.positionMs || !engineHoldsTrack) return
        engine.seekTo(after.positionMs)
        // what was decoded from the old position would otherwise be heard first
        out?.discard()
    }

    override fun setVolume(fraction: Float) {
        _state.value = _state.value.copy(volumeFraction = fraction.coerceIn(0f, 1f))
        applyGain()
    }

    /**
     * Whose volume the slider moves, and so whether this backend attenuates.
     *
     * In [VolumeMode.DEVICE] the slider is the phone's media volume, which the
     * player moves. Attenuating here as well would apply the volume twice, so
     * this renders at full gain. In [VolumeMode.APP] the slider is this
     * backend's own gain.
     */
    override fun setVolumeMode(mode: VolumeMode) {
        volumeMode = mode
        applyGain()
    }

    override fun setShuffle(enabled: Boolean) {
        _state.value = _state.value.copy(shuffle = enabled)
    }

    override fun setRepeat(enabled: Boolean) {
        _state.value = _state.value.copy(repeat = enabled)
    }

    /**
     * Winamp's Stop after current track: the track playing when it ends is the
     * last one. Read at the end of every track. A refused track still moves
     * on.
     */
    override fun setStopAfterCurrent(on: Boolean) {
        stopAfterCurrent = on
    }

    override fun release() {
        redial.cancel()
        lettingGo?.cancel()
        out?.setInterruptions(null)
        out?.stop()
        engine.release()
    }

    /**
     * Applies one of Winamp's rules and then tells the engine what changed.
     *
     * The instruction is the difference between the state before and after,
     * not the verb that was called: a rule that landed on a new track is a
     * load, and a pause that toggled back into playing is a resume. So next,
     * previous and playAt need no handling of their own. A track that ends on
     * its own is handled in [ended].
     */
    private fun obey(rule: (BackendState) -> BackendState) = obey(pressed = true, rule = rule)

    /**
     * [obey], saying whether this is a press about the music. Every one is,
     * and ends a reconnect under way, except a queue edited around the track.
     */
    private fun obey(
        pressed: Boolean,
        rule: (BackendState) -> BackendState,
    ) {
        val before = _state.value
        val ruled = rule(before)
        // a new press to play is a new attempt, so the notice from the last
        // one is cleared with it
        val after =
            if (ruled.transport == Transport.Playing &&
                before.transport != Transport.Playing
            ) {
                ruled.copy(notice = null)
            } else {
                ruled
            }
        _state.value = after
        val moved = movedTrack(before, after)
        // a press of the listener's own replaces a pause a call had made, so
        // the call's end does not undo it; a queue edit is not a press
        if (moved || after.transport != before.transport) pausedForNow = false
        // a press ends a reconnect: whatever it asks for is what happens now
        val reconnecting = redial.pending && !pressed
        if (redial.pending && pressed) redial.cancel()
        when {
            after.transport != Transport.Playing -> {
                quiet(after)
            }

            // a queue edited around a track that is reconnecting: the try
            // comes when it was going to
            reconnecting -> {
                Unit
            }

            moved || before.transport == Transport.Stopped || !engineHoldsTrack -> {
                reload(after)
            }

            restarted(before, after) -> {
                restart(from = before.transport)
            }

            before.transport == Transport.Paused -> {
                resume()
            }

            // playing on as it was: a queue edited around the track, which the
            // engine has no need to hear about
            else -> {
                Unit
            }
        }
    }

    private fun quiet(after: BackendState) {
        redial.silent()
        if (after.transport == Transport.Paused) {
            engine.pause()
            // the output too: a paused engine only stops decoding, and what it
            // had decoded ahead would play on
            out?.pause()
        } else {
            engine.stop()
            engineHoldsTrack = false
            heardUri = null
            notStreaming()
            // stopping the output lets go of the device; a stop the listener
            // asked for does it at once
            lettingGo?.cancel()
            out?.stop()
        }
    }

    /**
     * Loads the row [after] is on into the engine, from the position [after]
     * says.
     *
     * [keepTheTail] is for a track that ended on its own: what it decoded last
     * is still on its way to the speaker, so the next track goes in behind it.
     * Otherwise what is still waiting is discarded.
     */
    private fun reload(
        after: BackendState,
        keepTheTail: Boolean = false,
    ) {
        // the readouts are the last track's file; this one's are said once it
        // opens
        notStreaming()
        val uri = after.currentTrack?.uri
        if (uri == null) {
            engine.stop()
            engineHoldsTrack = false
            return
        }
        rendering()
        engine.load(uri, startPlaying = true, positionMs = after.positionMs)
        engineHoldsTrack = true
        if (!keepTheTail) out?.discard()
    }

    /**
     * Back to the top of what is already loaded, by a seek.
     *
     * From a pause the output starts again as well: a row picked again, or a
     * skip in a queue of one, is a press to hear it.
     */
    private fun restart(from: Transport) {
        engine.seekTo(0)
        out?.discard()
        if (from == Transport.Paused) resume()
    }

    private fun resume() {
        rendering()
        engine.play()
    }

    /**
     * Starts taking samples, before asking for any.
     *
     * The engine pushes back when nobody reads, so a load issued before
     * anything is draining stalls its decoder at position zero.
     */
    private fun rendering() {
        // a press inside the tail of a finished queue keeps the device it was
        // about to let go of
        lettingGo?.cancel()
        lettingGo = null
        val out = out ?: return
        val pcm = pcm ?: return
        out.start(pcm)
        out.resume()
    }

    /**
     * Lets go of the device once the last of a finished queue has been heard.
     *
     * The engine says a track has ended when it has decoded the end, and what
     * it decoded last is still queued for the speaker. The output is stopped
     * [TAIL_MS] later.
     */
    private fun letGoAfterTheTail() {
        val out = out ?: return
        lettingGo?.cancel()
        lettingGo =
            scope.launch {
                delay(TAIL_MS)
                out.stop()
            }
    }

    /** The slider's level where it is this player's to apply, and full gain where it is the phone's. */
    private fun applyGain() {
        val gain = if (volumeMode == VolumeMode.APP) _state.value.volumeFraction else 1f
        // whoever renders attenuates; the engine's mixer is used only when
        // nothing renders here
        if (out != null) out.setVolume(gain) else engine.setVolume(gain)
    }

    /**
     * Whether the rule landed on another track, and not on the same one at
     * another row of an edited queue.
     *
     * By id, because an edit that keeps the playing track (rows removed above
     * it, a sort) moves its row and not the music. A changed row with the
     * queue otherwise unchanged is a skip onto a second copy of the same
     * track, which starts from the top.
     */
    private fun movedTrack(
        before: BackendState,
        after: BackendState,
    ): Boolean {
        if (before.currentTrack?.id != after.currentTrack?.id) return true
        if (before.currentIndex == after.currentIndex) return false
        return before.queue.map { it.id } == after.queue.map { it.id }
    }

    /**
     * Back to the top of the same track: play pressed while playing, which is
     * Winamp's restart, or the row picked again - or a skip in a queue of one -
     * while paused.
     */
    private fun restarted(
        before: BackendState,
        after: BackendState,
    ) = after.positionMs == 0L && before.positionMs != 0L

    /** What the engine said, turned into state or into the next rule. */
    private fun heard(event: EngineEvent) {
        when (event) {
            is EngineEvent.Playing -> {
                at(event.positionMs)
            }

            is EngineEvent.Paused -> {
                at(event.positionMs)
            }

            is EngineEvent.PositionChanged -> {
                at(event.positionMs)
            }

            is EngineEvent.Bitrate -> {
                streaming(event.kbps)
            }

            // what follows is decided by the transport rules, unless the end
            // came long before the row's end, which is a read that gave up
            EngineEvent.EndOfTrack -> {
                if (cutShort()) dropped() else ended()
            }

            // a track that would not play is skipped, unless the refusal is
            // for another row or is the connection
            is EngineEvent.Unavailable -> {
                when {
                    leftBehind(event.trackUri) -> Unit
                    lostConnection() -> dropped()
                    else -> refused()
                }
            }

            // a uri the engine could not read is that row's refusal; a connect
            // that failed is a drop to wait out; anything else is counted
            is EngineEvent.Failed -> {
                when {
                    event.reason == EngineLine.NOT_A_TRACK -> refused()
                    event.reason.startsWith(EngineLine.COULD_NOT_CONNECT) -> dropped()
                    else -> unreachable(event.reason)
                }
            }
        }
    }

    /**
     * Whether a refusal names another row of the queue than the one playing:
     * a track already skipped past, which the engine was still answering for.
     * Such a refusal is ignored.
     *
     * A uri that names no row at all is taken as this row's, so the engine
     * writing a uri differently from the queue cannot hide a refusal.
     */
    private fun leftBehind(trackUri: String): Boolean {
        val now = _state.value
        return trackUri != now.currentTrack?.uri && now.queue.any { it.uri == trackUri }
    }

    /**
     * The engine reached the end of the track by itself.
     *
     * The last of this track is still on its way to the speaker and is kept:
     * the next track goes in behind it, and a queue that ends here lets it
     * finish before letting go of the device.
     */
    private fun ended() {
        // whatever follows, the engine has nothing left to seek in or resume
        engineHoldsTrack = false
        // a stop has already let the track go, and a pause keeps the listener
        // where they were: the press that ends the pause loads from there
        if (_state.value.transport != Transport.Playing) return
        val after = TransportRules.trackEnded(_state.value, random::nextInt, stopAfterCurrent = stopAfterCurrent)
        _state.value = after
        if (after.transport == Transport.Playing) {
            reload(after, keepTheTail = true)
        } else {
            engine.stop()
            notStreaming()
            letGoAfterTheTail()
        }
    }

    /**
     * A track the engine would not play: on to the next, and a notice if it
     * keeps happening, including when the queue runs out before the count
     * does ([Playability.ranOut]).
     */
    private fun refused() {
        // whatever the engine had, it does not have this one
        engineHoldsTrack = false
        // only a track being reached for can refuse: a failure that arrives
        // while nothing is asked to play is not counted
        if (_state.value.transport != Transport.Playing) return
        // asked without spending a shuffle's pick: under shuffle it never stops
        val onward = TransportRules.trackEnded(_state.value, pickShuffled = { 0 })
        val answer = if (onward.transport == Transport.Playing) failures.failed() else failures.ranOut()
        when (answer) {
            Playability.Answer.CarryOn -> obey { TransportRules.trackEnded(it, random::nextInt) }
            Playability.Answer.Reopen -> reopen()
            Playability.Answer.Tell -> tell()
        }
    }

    /**
     * The engine failed for a reason that is not the row's and that no wait
     * changes: nobody signed in, no cache, an engine that was closed.
     *
     * The cursor stays on the row the listener pressed. The same row is tried
     * again on a fresh connection until the count says to tell the listener.
     * An account that is not Premium is told at once. A connect that failed
     * on the network goes to [redial] instead.
     */
    private fun unreachable(reason: String) {
        engineHoldsTrack = false
        // the same guard as a refusal, for the same reason
        if (_state.value.transport != Transport.Playing) return
        val answer = if (reason == EngineLine.NOT_PREMIUM) failures.refusedForGood() else failures.failed()
        when (answer) {
            Playability.Answer.CarryOn, Playability.Answer.Reopen -> reopen()
            Playability.Answer.Tell -> tell()
        }
    }

    /**
     * Whether an end of track came well before the row's end: librespot giving
     * up on a read the network stopped feeding, which it reports as an end of
     * track.
     *
     * [CUT_SHORT_MS] of slack, because the last position heard is a tick
     * behind the decoder and a catalog's duration is not always the audio's.
     * A drop inside that last stretch is taken as the end, and the load of the
     * next row meets the drop.
     */
    private fun cutShort(): Boolean {
        val now = _state.value
        if (now.transport != Transport.Playing) return false
        val duration = now.currentTrack?.durationMs ?: 0
        return duration > CUT_SHORT_MS && now.positionMs < duration - CUT_SHORT_MS
    }

    /**
     * Whether a refusal of the playing row is the connection and not the
     * track: there is no network, or the row was heard playing since it was
     * loaded.
     */
    private fun lostConnection(): Boolean {
        val now = _state.value
        if (now.transport != Transport.Playing) return false
        return !network.online || (heardUri != null && heardUri == now.currentTrack?.uri)
    }

    /**
     * The connection went while the listener was playing: the row and the
     * position stay, and [redial] brings it back.
     */
    private fun dropped() {
        engineHoldsTrack = false
        if (_state.value.transport != Transport.Playing) return
        redial.dropped()
    }

    /** One try of [redial]'s: a fresh connection, and the same row on it from where it was heard to. */
    private fun redialNow() {
        engine.reopen()
        reload(_state.value)
    }

    /** Sets `connecting` to whether a reconnect is pending. */
    private fun redialMoved() {
        if (_state.value.connecting != redial.pending) {
            _state.value = _state.value.copy(connecting = redial.pending)
        }
    }

    /**
     * Stopped, and why, in one state update, so a watcher never sees the stop
     * without its reason. The notice is raised anew each time.
     */
    private fun tell() = obey { TransportRules.stop(it).raising(failures.notice) }

    /**
     * A fresh connection, and the same track again on it, from where it was.
     *
     * The track is retried, not skipped: after a run of failures the track is
     * unlikely to be the cause. Nothing is said to the listener here.
     */
    private fun reopen() {
        engine.reopen()
        reload(_state.value)
    }

    /**
     * An interruption from the output: a call, a prompt, another app.
     *
     * Only a pause for a moment is undone when it ends. Anything the listener
     * pressed in between wins; see [obey].
     */
    private fun interrupted(interruption: AudioOut.Interruption) {
        val playing = _state.value.transport == Transport.Playing
        when (interruption) {
            AudioOut.Interruption.PAUSE -> {
                // for good: a pause from a moment ago does not come back either
                pausedForNow = false
                if (playing) obey(TransportRules::pause)
            }

            AudioOut.Interruption.PAUSE_FOR_NOW -> {
                if (playing) {
                    obey(TransportRules::pause)
                    pausedForNow = true
                }
            }

            AudioOut.Interruption.RESUME -> {
                val ours = pausedForNow && _state.value.transport == Transport.Paused
                pausedForNow = false
                if (ours) obey(TransportRules::play)
            }
        }
    }

    /**
     * The bitrate of the file the engine is playing, for Winamp's kbps readout,
     * and the rate it is decoded at, for the kHz one.
     *
     * Ignored while stopped. Both are kept in the state and not on the row,
     * because they describe this playing of the song.
     */
    private fun streaming(kbps: Int) {
        if (_state.value.transport == Transport.Stopped) return
        _state.value = _state.value.copy(streamBitrateKbps = kbps, streamSampleRateKhz = SAMPLE_RATE_KHZ)
    }

    /** No file is playing, so the readouts go back to whatever the row itself carries. */
    private fun notStreaming() {
        if (_state.value.streamBitrateKbps == null && _state.value.streamSampleRateKhz == null) return
        _state.value = _state.value.copy(streamBitrateKbps = null, streamSampleRateKhz = null)
    }

    /**
     * Time comes from the engine, and only while the transport is Playing: a
     * position arriving after a pause or a stop was already in flight.
     */
    private fun at(positionMs: Long) {
        if (_state.value.transport != Transport.Playing) return
        // audio being heard clears the failure count and the notice
        if (positionMs > 0) {
            failures.played()
            heardUri = _state.value.currentTrack?.uri
            redial.sounding()
        }
        _state.value =
            _state.value.copy(
                positionMs = positionMs,
                notice = if (positionMs > 0) null else _state.value.notice,
            )
    }

    internal companion object {
        /**
         * The rate every song of the service is heard at, as Winamp's readout
         * rounds it. librespot decodes every file at 44.1 kHz
         * (`librespot_playback::SAMPLE_RATE`), whatever the quality.
         */
        const val SAMPLE_RATE_KHZ = PcmProvider.SAMPLE_RATE_HZ / 1_000

        /**
         * What this backend can do when it renders its own samples.
         *
         * A constant, so the pack can describe itself to the player when it is
         * bound without building a backend, which would open a session.
         */
        val RENDERING =
            Capabilities(
                canSeek = true,
                canEditQueue = true,
                hasEqualizer = true,
                hasBalance = true,
                hasDsp = true,
                canAttenuate = true,
            )

        /**
         * How long the end of a finished queue is given to be heard: room for
         * the packets in the engine's channel, one read in the chain and the
         * device's own buffer.
         */
        const val TAIL_MS = 2_000L

        /** How far short of a row's duration an end of track is still taken as the end; see [cutShort]. */
        const val CUT_SHORT_MS = 5_000L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
