// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The states a sign-in has to draw, read without an account or a device. A
 * status line nobody recognizes means still waiting.
 */
class PairingTest {
    @Test
    fun `a code and a page are what the listener is shown`() {
        val state = Pairing.of("VK4GV5\nhttps://spotify.com/pair?code=VK4GV5")

        assertEquals(
            Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair?code=VK4GV5"),
            state,
        )
    }

    @Test
    fun `an empty answer means it never started`() {
        assertEquals(Pairing.Failed("could not start pairing"), Pairing.of(""))
    }

    @Test
    fun `a half answer is not shown as half a sign-in`() {
        assertEquals(Pairing.Failed("could not start pairing"), Pairing.of("VK4GV5"))
    }

    @Test
    fun `approval ends the wait`() {
        val waiting = Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair")

        assertEquals(Pairing.Paired, Pairing.progress(waiting, "paired"))
    }

    @Test
    fun `a failure carries what the engine said`() {
        val waiting = Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair")

        assertEquals(
            Pairing.Failed("expired_token"),
            Pairing.progress(waiting, "pairing failed: expired_token"),
        )
    }

    @Test
    fun `a status nobody recognizes is still waiting`() {
        val waiting = Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair")

        assertEquals(waiting, Pairing.progress(waiting, "something new upstream"))
    }

    /** Every way the engine's pairing can end badly, as the engine writes it, ends the wait. */
    @Test
    fun `every way a pairing fails ends the wait with its reason`() {
        val waiting = Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair")
        val reasons =
            listOf(
                "expired_token",
                "no cache: permission denied",
                "could not connect: connection reset",
                "this Spotify account is not Premium",
            )

        reasons.forEach { reason ->
            assertEquals(Pairing.Failed(reason), Pairing.progress(waiting, "pairing failed: $reason"))
        }
    }

    /** The engine's playback lines do not end or fail a wait. */
    @Test
    fun `what the engine says about anything else is still waiting`() {
        val waiting = Pairing.WaitingForApproval("VK4GV5", "https://spotify.com/pair")

        listOf("waiting for the pairing to be approved", "connected", "sink started", "sink stopped").forEach { line ->
            assertEquals(line, waiting, Pairing.progress(waiting, line))
        }
    }
}
