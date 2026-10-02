// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.StreamReconnect

/**
 * Puts a track whose connection dropped back on, at the position last heard,
 * on the SDK's reconnect schedule.
 *
 * [StreamReconnect] decides when; this keeps the timer and the network watch,
 * and hands the try to [retry]. The engine is fire-and-forget, so a try is
 * "reconnect and load the row again" and its answer arrives later as audio
 * ([sounding]) or as another drop ([dropped]).
 *
 * librespot does not reconnect on its own: a session whose socket dies is
 * marked invalid, and nothing opens a new one until something asks.
 *
 * The backend calls [cancel] for every press (pause, stop, a skip, a new
 * queue, a seek), so a try never starts music the listener did not ask for.
 *
 * The wait is a coroutine delay, which does not wake a sleeping CPU, and this
 * pack holds no wake lock or Wi-Fi lock. Under Doze a wait can stretch.
 *
 * While there is no network no timer is set. The network is watched while a
 * reconnect is pending, and a try is scheduled when one arrives, including a
 * different one taking over.
 */
internal class TrackRedial(
    private val scope: CoroutineScope,
    private val network: NetworkWatch,
    private val policy: StreamReconnect,
    /** Reconnect and load the row again, from where it was heard to. */
    private val retry: () -> Unit,
    /** The budget is spent: the backend stops and says so. */
    private val gaveUp: () -> Unit,
    /** [pending] changed; the backend redraws "connecting". */
    private val changed: () -> Unit,
) {
    /** A drop is being recovered from: waiting for a try, or making one. */
    var pending = false
        private set

    /** A try has been handed to the engine and has not answered yet. */
    private var reaching = false

    private var timer: Job? = null

    /** Audio is being heard: the budget stops being spent, and a pending reconnect has worked. */
    fun sounding() {
        policy.sounding()
        if (pending) settle()
    }

    /** Nothing is being heard: a pause, a stop. */
    fun silent() = policy.silent()

    /**
     * The track in hand lost its connection. The row stays; a try is scheduled,
     * or the network is waited for, or the budget is spent and [gaveUp] called.
     */
    fun dropped() {
        timer?.cancel()
        timer = null
        reaching = false
        val next = policy.failed(network.online)
        if (next == StreamReconnect.Next.GiveUp) {
            cancel()
            gaveUp()
            return
        }
        val began = !pending
        pending = true
        network.watch(::networkChanged)
        if (next is StreamReconnect.Next.RetryIn) tryAfter(next.delayMs)
        if (began) changed()
    }

    /** The listener asked for something else: no retry, and a fresh budget for the next drop. */
    fun cancel() {
        policy.forget()
        if (!pending) return
        pending = false
        reaching = false
        timer?.cancel()
        timer = null
        network.unwatch()
        changed()
    }

    private fun networkChanged(online: Boolean) {
        if (!pending) return
        if (!online) {
            // nothing to try on: the timer goes, and a try already under way
            // fails on its own and comes back as a drop to wait out
            policy.networkLost()
            timer?.cancel()
            timer = null
            return
        }
        if (reaching) return
        tryAfter(policy.networkBack())
    }

    private fun tryAfter(delayMs: Long) {
        timer?.cancel()
        timer =
            scope.launch {
                delay(delayMs)
                timer = null
                if (!pending) return@launch
                reaching = true
                retry()
            }
    }

    /** Heard again: nothing more to wait for, and nothing more to watch. */
    private fun settle() {
        pending = false
        reaching = false
        timer?.cancel()
        timer = null
        network.unwatch()
        changed()
    }
}
