// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The real engine behind [PlaybackEngine]: librespot, over JNI.
 *
 * Verbs go down as calls and events come up as lines that [EngineLine] reads.
 * There is no callback into the JVM. The events are pulled, so the engine's
 * queue holds what is said before anybody is listening, which
 * [PlaybackEngine.events] requires.
 */
internal class LibrespotEngine(
    private val cacheDir: String,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val lines: EngineEvents = processEvents,
) : PlaybackEngine {
    // unlimited, so the hand-over never waits and never drops an event
    private val inbox = Channel<EngineEvent>(capacity = Channel.UNLIMITED)
    override val events = inbox.receiveAsFlow()

    /** This engine's reader, which [release] ends. */
    private val pump: Job

    init {
        // attached before anything is asked, so this engine hears what its own
        // open and loads say, and nothing said before it existed
        lines.attach(inbox)
        Librespot.nativeOpen(cacheDir)
        // a blocking read on a background thread
        pump = scope.launch(io) { while (isActive) lines.deliverOne(WAIT_MS) }
    }

    override fun load(
        trackUri: String,
        startPlaying: Boolean,
        positionMs: Long,
    ) {
        // opening first: a live session that is still the stored credential's
        // is kept, and after a sign-out, a pairing or an invalid session this
        // is what connects
        Librespot.nativeOpen(cacheDir)
        Librespot.nativeLoad(trackUri, startPlaying, positionMs)
    }

    override fun play() = Librespot.nativePlay()

    override fun pause() = Librespot.nativePause()

    override fun stop() = Librespot.nativeStop()

    override fun seekTo(positionMs: Long) = Librespot.nativeSeek(positionMs)

    override fun setVolume(fraction: Float) = Librespot.nativeSetVolume(fraction)

    // opening replaces an invalid session and keeps one that is still good
    override fun reopen() {
        Librespot.nativeOpen(cacheDir)
    }

    /**
     * Lets go of the engine: its reader stops, nothing more reaches its inbox,
     * and the track stops.
     *
     * The reader must stop here. It reads the process's one queue, so a reader
     * left running would take lines meant for the next engine.
     */
    override fun release() {
        pump.cancel()
        lines.detach(inbox)
        Librespot.nativeStop()
        inbox.close()
    }

    private companion object {
        /** How long one read of the queue waits, and so how soon a released engine lets go of its thread. */
        const val WAIT_MS = 1_000L

        /** The process's one event queue; see [EngineEvents]. */
        val processEvents =
            EngineEvents(
                claim = { Librespot.nativeClaimEvents() },
                next = { timeoutMs -> Librespot.nativeNextEvent(timeoutMs) },
            )
    }
}

/**
 * The engine's one event queue, read for whichever engine is current.
 *
 * The queue belongs to the process. A line goes to the engine attached when
 * it arrives, or to nobody, and it is read by one reader at a time, so a
 * released engine's reader finishing its last wait and the next engine's
 * reader cannot take lines out of order.
 *
 * [claim] tells the engine a new reader has the queue, so lines said before
 * it are dropped; [next] waits up to its argument for one line, and answers
 * empty for none. Both are handed in, so this is tested without the library.
 */
internal class EngineEvents(
    private val claim: () -> Unit,
    private val next: (Long) -> String,
) {
    private val current = AtomicReference<SendChannel<EngineEvent>?>(null)
    private val reading = Any()

    /**
     * From now on what the engine says goes to [sink], and nothing said before
     * reaches it.
     *
     * Claimed before [sink] is put in place, so a line an earlier reader takes
     * in between is one said before the claim, which the engine has already
     * dropped, or one said after it, which is nobody's until [sink] is here.
     */
    fun attach(sink: SendChannel<EngineEvent>) {
        claim()
        current.set(sink)
    }

    /** [sink] hears nothing more. A line already on its way goes to whoever is attached next. */
    fun detach(sink: SendChannel<EngineEvent>) {
        current.compareAndSet(sink, null)
    }

    /**
     * Waits up to [timeoutMs] for one line and hands it to the engine attached
     * when it arrives.
     *
     * The hand-over never suspends and never throws: `trySend` to a closed
     * inbox fails without an exception.
     */
    fun deliverOne(timeoutMs: Long) {
        synchronized(reading) {
            val event = EngineLine.parse(next(timeoutMs)) ?: return
            current.get()?.trySend(event)
        }
    }
}
