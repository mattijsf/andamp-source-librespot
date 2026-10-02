// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closing or signing out ends the session, and opening one does not write the
 * pairing status.
 *
 * Needs a credential; see [DeviceCredential].
 */
@RunWith(AndroidJUnit4::class)
class LibrespotSessionTest {
    @Test
    fun closingLetsGoOfTheSession() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        assertTrue("the engine connects a session", DeviceCredential.awaitSession())

        Librespot.nativeClose()

        assertEquals("closing ends the session", "", LibrespotQueries.nativeUsername())
        // and asking again opens nothing: the close forgot where the credential is
        assertTrue(LibrespotQueries.nativeBrowse(LIKED_BY_NOBODY).startsWith("error"))
        Thread.sleep(SETTLE_MS)
        assertEquals("a question after the close opens no session", "", LibrespotQueries.nativeUsername())
    }

    @Test
    fun signingOutTakesTheAccountOffThisPhone() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        assertTrue("the engine connects a session", DeviceCredential.awaitSession())

        assertTrue("signing out deletes the credential", LibrespotAccount.forget(cache))

        assertEquals("signing out ends the session", "", LibrespotQueries.nativeUsername())
    }

    @Test
    fun openingASessionLeavesThePairingStatusAlone() {
        val cache = DeviceCredential.cacheOrSkip()
        // closed first, so this open makes a new session
        Librespot.nativeClose()
        val before = LibrespotPairing.nativeStatus()

        Librespot.nativeOpen(cache.absolutePath)
        assertTrue("the engine connects a session", DeviceCredential.awaitSession())

        assertEquals("opening a session leaves the pairing status unchanged", before, LibrespotPairing.nativeStatus())
    }

    private companion object {
        /** Any uri the engine can be asked about. */
        const val LIKED_BY_NOBODY = "spotify:user:nobody:collection"

        /** Long enough for an open that had been asked for to have landed. */
        const val SETTLE_MS = 5_000L
    }
}
