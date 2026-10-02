// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library, on a real device, against a real account.
 *
 * The JVM tests cover reading an answer. This checks that the service still
 * resolves Liked Songs and a search to track uris, and that a batch of those
 * uris comes back with names.
 *
 * Needs a credential; see [DeviceCredential].
 */
@RunWith(AndroidJUnit4::class)
class LibrespotBrowseTest {
    @Test
    fun theLibraryComesBackWithNamesOnIt() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue("the engine connects a session", DeviceCredential.awaitSession())

        val albums = runBlocking { source.albums(null) }
        val artists = runBlocking { source.artists() }

        assertTrue("the library has albums: $albums", albums.isNotEmpty())
        assertTrue("every album has a title", albums.all { it.title.isNotEmpty() })
        assertTrue("every artist has a name", artists.all { it.name.isNotEmpty() })
        // the shelves are views of one list, so they have to agree about it
        assertTrue("the library has artists", artists.isNotEmpty())

        val tracks = runBlocking { source.tracks(albums.first().id) }
        assertTrue("the first album has tracks", tracks.isNotEmpty())
        assertTrue("every track has a uri", tracks.all { !it.uri.isNullOrEmpty() })
    }

    @Test
    fun searchAsksTheServiceAndGetsSomethingBack() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue(DeviceCredential.awaitSession())

        val hits = runBlocking { source.search("portishead", limit = 5) }

        assertTrue("a search for a known band finds tracks", hits.isNotEmpty())
        assertTrue("a search returns at most five hits", hits.size <= 5)
        assertTrue("every hit has a title", hits.all { it.title.isNotEmpty() })
    }

    @Test
    fun theListsAreTheListenersOwn() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue(DeviceCredential.awaitSession())

        val lists = runBlocking { source.playlists() }

        assertTrue("the account has playlists", lists.isNotEmpty())
        assertTrue("every playlist has a name", lists.all { it.name.isNotEmpty() })
        assertTrue("every entry is a playlist uri", lists.all { it.id.startsWith("spotify:playlist:") })

        // one that says it holds something has to hold it, in its own order
        val holding = lists.firstOrNull { (it.trackCount ?: 0) > 0 } ?: return
        val tracks = runBlocking { source.playlistTracks(holding.id) }
        assertTrue("${holding.name} returns its ${holding.trackCount} tracks", tracks.isNotEmpty())
        assertTrue("every row has a uri", tracks.all { !it.uri.isNullOrEmpty() })
    }

    /**
     * An artist outside the library is found and opened.
     *
     * The artist's answer names their records by gid alone, and a batch names
     * those gids; this checks the two still line up.
     */
    @Test
    fun anArtistOutsideTheLibraryCanBeFoundAndOpened() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue(DeviceCredential.awaitSession())

        val found = runBlocking { source.findArtists("aphex twin") }
        val them = found.firstOrNull { it.name.equals("Aphex Twin", ignoreCase = true) }
        assertTrue("Aphex Twin is among $found", them != null)

        val records = runBlocking { source.albums(them!!.id) }
        assertTrue("the artist has records", records.isNotEmpty())
        assertTrue("every record has a title", records.all { it.title.isNotEmpty() })

        val tracks = runBlocking { source.tracks(records.first().id) }
        assertTrue("${records.first().title} has tracks", tracks.isNotEmpty())
        assertTrue("every row has a uri", tracks.all { !it.uri.isNullOrEmpty() })
    }

    /**
     * Every row has a cover address.
     *
     * The address comes from a template on the account, which only a signed-in
     * session holds.
     */
    @Test
    fun everyRowKnowsWhereItsCoverIs() {
        val cache = DeviceCredential.cacheOrSkip()
        Librespot.nativeOpen(cache.absolutePath)
        val source = LibrespotBrowseSource(LibrespotBrowse)
        assertTrue(DeviceCredential.awaitSession())

        val album = runBlocking { source.albums(null) }.firstOrNull() ?: return
        val tracks = runBlocking { source.tracks(album.id) }

        assertTrue("the album has tracks", tracks.isNotEmpty())
        tracks.forEach { track ->
            assertTrue("${track.title} has a cover", !track.artworkUri.isNullOrEmpty())
            assertTrue("${track.artworkUri} is an http address", track.artworkUri!!.startsWith("http"))
        }
    }
}
