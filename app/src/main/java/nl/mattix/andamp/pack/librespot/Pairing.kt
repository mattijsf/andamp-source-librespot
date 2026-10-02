// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/**
 * What a listener has to be shown to sign in, and where they are in doing it.
 *
 * The device-pair flow hands out a short code and a page to type it on, the
 * listener approves on any device, and a credential is stored here.
 *
 * Data and a pure reading of the engine's status line, so the states are
 * tested without an account or a phone.
 */
sealed interface Pairing {
    /** Nothing has been asked for yet, and nothing is stored. */
    data object NotStarted : Pairing

    /** Show the code, send them to the page, and wait. */
    data class WaitingForApproval(
        val code: String,
        val url: String,
    ) : Pairing

    /** A credential is stored; this device can play. */
    data object Paired : Pairing

    /** It did not work, and this is what the engine said. */
    data class Failed(
        val reason: String,
    ) : Pairing

    companion object {
        /**
         * Reads what [LibrespotPairing.nativeBeginPairing] returned: a code and a url,
         * one per line. An empty answer means the flow never started.
         */
        fun of(begun: String): Pairing {
            val lines = begun.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.size < 2) return Failed("could not start pairing")
            return WaitingForApproval(code = lines[0], url = lines[1])
        }

        /**
         * Where a wait has got to, from the engine's pairing status line.
         *
         * A pairing ends in `paired`, or in `pairing failed: ` followed by
         * why, in words fit to show. Any other line means the wait goes on,
         * so a new status upstream does not become a failure on screen.
         */
        fun progress(
            waiting: WaitingForApproval,
            status: String,
        ): Pairing =
            when {
                status == PAIRED -> Paired
                status.startsWith(FAILED_PREFIX) -> Failed(status.removePrefix(FAILED_PREFIX))
                else -> waiting
            }

        private const val PAIRED = "paired"
        private const val FAILED_PREFIX = "pairing failed: "
    }
}
