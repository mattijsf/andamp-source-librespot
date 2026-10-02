// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An invalid session is replaced by a fresh one.
 *
 * A session that loses its dispatch loop fails every later request, and
 * librespot does not reconnect it. [LibrespotQueries.nativeBreakSession]
 * invalidates the session on purpose and this asserts that the next open
 * replaces it. In the app, [Playability] counts refusals and asks for that
 * open.
 *
 * Needs a credential; see [DeviceCredential].
 */
@RunWith(AndroidJUnit4::class)
class LibrespotHealsTest {
    @Test
    fun aDeadSessionIsReplacedRatherThanReused() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        assertTrue("the engine connects a session", DeviceCredential.awaitSession())
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue("the library has albums before the session is broken", runBlocking { source.albums(null) }.isNotEmpty())

        LibrespotQueries.nativeBreakSession()
        // the same call the backend makes when it has counted enough refusals
        Librespot.nativeOpen(cache.absolutePath)
        assertTrue("a new session opens", DeviceCredential.awaitSession())

        val after = runBlocking { LibrespotBrowseSource(LibrespotBrowse).albums(null) }
        assertTrue("the new session answers", after.isNotEmpty())
    }

    @Test
    fun openingTwiceOnALiveSessionKeepsTheOne() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        assertTrue(DeviceCredential.awaitSession())
        val who = LibrespotQueries.nativeUsername()

        Librespot.nativeOpen(cache.absolutePath)

        // a second connect would be a second device on the account
        assertTrue("a second open keeps the session", LibrespotQueries.nativeUsername() == who)
    }
}
