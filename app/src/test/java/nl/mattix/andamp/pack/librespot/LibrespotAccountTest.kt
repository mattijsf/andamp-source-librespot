// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [LibrespotAccount] on a JVM, which has no engine: the name read from the
 * credential file, signing in and out, and whether the file is there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotAccountTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the name is the one in the credential librespot wrote`() {
        File(folder.root, "credentials.json").writeText("""{"username":"listener","auth_type":1,"auth_data":"c2VjcmV0"}""")

        assertEquals("listener", LibrespotAccount.username(folder.root))
    }

    @Test
    fun `no credential, or one that cannot be read, gives no name`() {
        assertNull(LibrespotAccount.username(folder.root))

        File(folder.root, "credentials.json").writeText("not json")
        assertNull(LibrespotAccount.username(folder.root))
    }

    /** A JVM has no engine, so a native call would throw. */
    @Test
    fun `without the engine, signing in fails with a reason`() =
        runTest {
            assertEquals(Pairing.Failed("this build has no Spotify engine"), LibrespotAccount.begin(folder.root))
        }

    @Test
    fun `without the engine, signing out still takes the credential away`() {
        val credential = File(folder.root, "credentials.json")
        credential.writeText("""{"username":"listener","auth_type":1,"auth_data":"c2VjcmV0"}""")

        assertTrue(LibrespotAccount.forget(folder.root))
        assertFalse(credential.exists())
    }

    /** [LibrespotAccount.hasCredential] does not read the file. */
    @Test
    fun `a credential file counts as a credential whatever is in it`() {
        assertFalse(LibrespotAccount.hasCredential(folder.root))

        File(folder.root, "credentials.json").writeText("not even json")

        assertTrue(LibrespotAccount.hasCredential(folder.root))
    }
}
