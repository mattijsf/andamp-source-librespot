// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rootlist, read.
 *
 * [REAL] is an answer the engine handed over, cut to three entries, with the
 * account name, the account's playlist id and the revision replaced; the rest,
 * the folder marker included, is as it came. "Driving" is a list of the
 * account's own, and "DJ" one of the service's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotPlaylistsTest {
    @Test
    fun `the two lists are paired by position`() {
        val lists = LibrespotPlaylists.parse(REAL, me = ME)

        assertEquals(listOf("Driving", "DJ"), lists.map { it.name })
        assertEquals(
            listOf("spotify:playlist:0examplePlaylist000001", "spotify:playlist:37i9dQZF1EYkqdzj48dyYq"),
            lists.map { it.id },
        )
    }

    @Test
    fun `a folder is not a list of songs`() {
        assertEquals(2, LibrespotPlaylists.parse(REAL, me = ME).size)
    }

    /** A folder is two markers, one where it opens and one where it closes, each with an empty entry beside it. */
    @Test
    fun `a list after a folder is still paired with its own name`() {
        val foldered =
            """{"contents": {"items": [{"uri": "spotify:start-group:aa:Radio"}, {"uri": "spotify:playlist:one"},""" +
                """ {"uri": "spotify:end-group:aa"}, {"uri": "spotify:playlist:two"}],""" +
                """ "metaItems": [{}, {"attributes": {"name": "One"}}, {}, {"attributes": {"name": "Two"}}]}}"""

        val lists = LibrespotPlaylists.parse(foldered, me = ME)

        assertEquals(listOf("spotify:playlist:one" to "One", "spotify:playlist:two" to "Two"), lists.map { it.id to it.name })
    }

    @Test
    fun `what it knows about a list comes with it`() {
        val driving = LibrespotPlaylists.parse(REAL, me = ME).first()

        assertEquals(151, driving.trackCount)
    }

    @Test
    fun `the listener's own list names no maker, and somebody else's does`() {
        val (driving, dj) = LibrespotPlaylists.parse(REAL, me = ME)

        assertNull("the listener's own list names no maker", driving.owner)
        assertEquals("spotify", dj.owner)
    }

    @Test
    fun `a list the answer could not name is not shown`() {
        val unnamed =
            """{"contents": {"items": [{"uri": "spotify:playlist:abc"}], "metaItems": [{}]}}"""

        assertEquals(emptyList<Any>(), LibrespotPlaylists.parse(unnamed, me = ME))
    }

    @Test
    fun `an answer that failed holds no lists`() {
        assertEquals(emptyList<Any>(), LibrespotPlaylists.parse("error no session", me = ME))
        assertEquals(emptyList<Any>(), LibrespotPlaylists.parse("", me = ME))
        assertEquals(emptyList<Any>(), LibrespotPlaylists.parse("{ not json", me = ME))
    }

    private companion object {
        /** Who [REAL] was asked for. */
        const val ME = "listener"

        const val REAL =
            """{"revision": "AAABexampleRevision0000000000001", "length": 3, "contents": {"pos": 0, "truncated": false,""" +
                """ "items": [{"uri": "spotify:start-group:1c1b4a5f:Radio"},""" +
                """ {"uri": "spotify:playlist:0examplePlaylist000001", "attributes": {"timestamp": "1783604809000"}},""" +
                """ {"uri": "spotify:playlist:37i9dQZF1EYkqdzj48dyYq", "attributes": {"timestamp": "1774936455000"}}],""" +
                """ "metaItems": [{},""" +
                """ {"revision": "AAAAAa4E", "attributes": {"name": "Driving"}, "length": 151,""" +
                """ "ownerUsername": "listener", "statusCode": 200},""" +
                """ {"revision": "AAAAAa4F", "attributes": {"name": "DJ", "description": "All kinds of music."},""" +
                """ "length": 0, "ownerUsername": "spotify"}]}}"""
    }
}
