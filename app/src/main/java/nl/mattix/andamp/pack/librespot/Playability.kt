// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.BackendNotice

/**
 * Tells a track that will not play from a source that will not play, by
 * counting.
 *
 * One refused track is ordinary and playback moves on. An account whose audio
 * keys are refused fails every track the same way, one at a time, so a run of
 * refusals is answered with a fresh connection and then with a notice.
 *
 * A connect that failed on the network is not counted here; the backend waits
 * that out on the reconnect schedule (see [TrackRedial]). The engine's other
 * failures are counted. The engine cannot tell a refused key from a missing
 * track, so this does not either.
 */
internal class Playability(
    private val runToBeSure: Int = RUN,
) {
    /** What to do about a track that would not play. */
    sealed interface Answer {
        /** One track: move on, and say nothing. */
        data object CarryOn : Answer

        /**
         * A run of failures: try a fresh connection first. An invalid session
         * fails every request until it is reopened.
         */
        data object Reopen : Answer

        /** Reopening did not help: tell the listener. */
        data object Tell : Answer
    }

    private var failures = 0

    /** A fresh connection was already tried in this run. */
    private var reopened = false

    fun failed(): Answer {
        failures++
        return when {
            failures == runToBeSure -> reopening()

            // the same run again after a fresh connection
            failures >= runToBeSure * 2 -> told()

            else -> Answer.CarryOn
        }
    }

    /**
     * A track would not play and there is nothing after it to carry on to.
     *
     * The queue ended before the count could decide, so its end stands in for
     * the rest of the run: one fresh connection, and if the refusal comes back
     * on that too, [Answer.Tell].
     */
    fun ranOut(): Answer {
        failures++
        return if (reopened) told() else reopening()
    }

    /**
     * The engine named a reason no count or fresh connection changes, such as
     * an account that is not Premium.
     */
    fun refusedForGood(): Answer = told()

    /** Something played: the count starts over. */
    fun played() {
        failures = 0
        reopened = false
    }

    /** The notice that goes with [Answer.Tell]. */
    val notice: BackendNotice get() = BackendNotice.SourceCannotPlay

    private fun reopening(): Answer {
        reopened = true
        return Answer.Reopen
    }

    /**
     * Answers [Answer.Tell] and starts the count over.
     *
     * The notice stops playback, so the listener's next press is a fresh
     * attempt and gets the whole cycle again, fresh connection included.
     */
    private fun told(): Answer {
        failures = 0
        reopened = false
        return Answer.Tell
    }

    private companion object {
        /**
         * How many refusals in a row count as a run. Two can still be two
         * neighboring rows from the same unavailable release.
         */
        const val RUN = 3
    }
}
