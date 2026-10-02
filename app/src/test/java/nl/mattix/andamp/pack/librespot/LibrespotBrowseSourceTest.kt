// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import nl.mattix.andamp.core.playback.SourceUnreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.reflect.KMutableProperty0

/**
 * The library against the SDK's browse contract suite, over a made-up service.
 *
 * Robolectric only for `org.json`. It exercises the parser, the walk through a
 * context's pages, and the grouping of liked tracks into artists and albums.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotBrowseSourceTest : BrowseSourceContractTest() {
    @get:Rule
    val folder = TemporaryFolder()

    override fun createSource(shelf: Shelf): BrowseSource =
        // built the way the app builds it, with a stored credential.
        // Unconfined: every question runs on the test's own thread
        LibrespotLibrary.over(FakeBrowseResponder(shelf), signedIn(), Dispatchers.Unconfined)

    /** Signed in, with every question to the engine failing. */
    override fun createUnreachableSource(shelf: Shelf): BrowseSource =
        LibrespotBrowseSource(ScriptedResponder(FakeBrowseResponder(shelf)).allFailing(), Dispatchers.Unconfined)

    @Test
    fun `nobody signed in is a source that cannot answer`() {
        val source = LibrespotLibrary.over(FakeBrowseResponder(shelf()), folder.newFolder(), Dispatchers.Unconfined)

        assertFalse(source.available)
    }

    /** The source is available while its session is still connecting. */
    @Test
    fun `a stored credential can answer before any session exists`() {
        val source = LibrespotLibrary.over(FakeBrowseResponder(shelf(), who = ""), signedIn(), Dispatchers.Unconfined)

        assertTrue(source.available)
    }

    @Test
    fun `a credential that went away is noticed rather than waited on`() =
        runTest {
            val store = signedIn()
            var opened = 0
            val source = LibrespotLibrary.over(FakeBrowseResponder(shelf(), who = ""), store, Dispatchers.Unconfined) { opened++ }
            File(store, "credentials.json").delete()

            assertUnreachable { source.artists() }
            assertFalse(source.available)
            assertEquals("no session is opened without a credential", 0, opened)
        }

    @Test
    fun `signed in but with nothing liked is empty rather than broken`() =
        runTest {
            val source = LibrespotBrowseSource(FakeBrowseResponder(Shelf(emptyList())), Dispatchers.Unconfined)

            assertEquals(emptyList<Any>(), source.artists())
        }

    /** An engine that answers nothing and one that cannot answer are told apart. */
    @Test
    fun `an engine that errors could not be reached, and one that answers nothing is empty`() =
        runTest {
            val empty = LibrespotBrowseSource(FakeBrowseResponder(Shelf(emptyList())), Dispatchers.Unconfined)
            val erroring =
                LibrespotBrowseSource(ScriptedResponder(FakeBrowseResponder(shelf())).allFailing(), Dispatchers.Unconfined)

            assertEquals(emptyList<Any>(), empty.artists())
            assertEquals(emptyList<Any>(), empty.playlists())
            assertEquals(emptyList<Any>(), empty.search("dark"))
            assertUnreachable { erroring.artists() }
            assertUnreachable { erroring.playlists() }
            assertUnreachable { erroring.search("dark") }
            assertUnreachable { erroring.albums("spotify:artist:Muse") }
        }

    @Test
    fun `the library is asked for once, however many shelves are drawn`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)

            source.artists()
            source.albums(null)
            source.tracks("nothing")

            assertEquals("the library is fetched once", 1, responder.contexts)
        }

    @Test
    fun `a search asks the service, not the shelf`() =
        runTest {
            val fake = FakeBrowseResponder(shelf())
            val source = LibrespotBrowseSource(fake, Dispatchers.Unconfined)

            source.search("dark forest")

            assertEquals("spotify:search:dark+forest", fake.contextAsked)
        }

    @Test
    fun `a search's words are escaped in the uri`() =
        runTest {
            val fake = FakeBrowseResponder(shelf())
            val source = LibrespotBrowseSource(fake, Dispatchers.Unconfined)

            source.search("AC/DC")
            assertEquals("spotify:search:AC%2FDC", fake.contextAsked)
            source.search("#1 Crush")
            assertEquals("spotify:search:%231+Crush", fake.contextAsked)
            source.findArtists("Björk")
            assertEquals("spotify:search:Bj%C3%B6rk", fake.contextAsked)
        }

    @Test
    fun `names with punctuation in them are found`() =
        runTest {
            val odd =
                Shelf(
                    artists = listOf(ShelfArtist("Björk", listOf(ShelfAlbum("Debut", 1993, listOf("Human Behaviour" to 1))))),
                    catalogue =
                        listOf(
                            ShelfArtist("AC/DC", listOf(ShelfAlbum("Back in Black", 1980, listOf("Hells Bells" to 1)))),
                            ShelfArtist("Garbage", listOf(ShelfAlbum("Romeo + Juliet", 1996, listOf("#1 Crush" to 1)))),
                        ),
                )
            val source = LibrespotBrowseSource(FakeBrowseResponder(odd), Dispatchers.Unconfined)

            assertEquals(listOf("AC/DC"), source.findArtists("AC/DC").map { it.name })
            assertEquals(listOf("Björk"), source.findArtists("Björk").map { it.name })
            assertEquals(listOf("#1 Crush"), source.search("#1 Crush").map { it.title })
            // a plus the listener typed is a plus, not the space a search uri writes as one
            assertEquals(listOf("#1 Crush"), source.search("Romeo + Juliet").map { it.title })
        }

    @Test
    fun `Liked Songs past its first page is all on the shelf`() =
        runTest {
            val fake = FakeBrowseResponder(shelf(), pageSize = 1)
            val source = LibrespotBrowseSource(fake, Dispatchers.Unconfined)

            val titles = source.albums(null).map { it.title }

            assertEquals(setOf("The Wow! Signal", "Absolution", "Dummy"), titles.toSet())
            assertEquals("seven liked songs at one per page take six further pages", 6, fake.pagesAsked)
        }

    @Test
    fun `a list longer than a page comes back whole, in its own order`() =
        runTest {
            val source = LibrespotBrowseSource(FakeBrowseResponder(shelf(), pageSize = 1), Dispatchers.Unconfined)
            val driving = source.playlists().first { it.name == "Driving" }

            val titles = source.playlistTracks(driving.id).map { it.title }

            assertEquals(listOf("Space Debris", "Mysterons", "Cryogen"), titles)
        }

    @Test
    fun `a page that fails keeps what was read before it`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)
            val driving = source.playlists().first { it.name == "Driving" }
            responder.pageFailures = Int.MAX_VALUE

            val titles = source.playlistTracks(driving.id).map { it.title }

            // two to a page: the first page, and nothing made up for the second
            assertEquals(listOf("Space Debris", "Mysterons"), titles)
        }

    @Test
    fun `a song a list holds twice is on it twice`() =
        runTest {
            val twice = shelf().copy(playlists = listOf(ShelfPlaylist("Twice", listOf("Sour Times", "Mysterons", "Sour Times"))))
            val source = LibrespotBrowseSource(FakeBrowseResponder(twice), Dispatchers.Unconfined)
            val list = source.playlists().single()

            val titles = source.playlistTracks(list.id).map { it.title }

            assertEquals(listOf("Sour Times", "Mysterons", "Sour Times"), titles)
            assertEquals("the list's track count matches its tracks", list.trackCount, titles.size)
        }

    @Test
    fun `the listener's own lists do not name them as their maker`() =
        runTest {
            val source = LibrespotBrowseSource(FakeBrowseResponder(shelf()), Dispatchers.Unconfined)

            assertEquals(listOf(null, null), source.playlists().map { it.owner })
        }

    @Test
    fun `a library that could not be read is asked for again rather than remembered`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)
            responder.contextFailures = Int.MAX_VALUE

            assertUnreachable { source.artists() }

            responder.contextFailures = 0
            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
        }

    @Test
    fun `names that could not be had are not remembered as the library`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)
            responder.trackFailures = Int.MAX_VALUE

            assertUnreachable { source.artists() }

            responder.trackFailures = 0
            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
        }

    @Test
    fun `a library read part way is shown, but not remembered`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)
            responder.pageFailures = Int.MAX_VALUE

            // the first page is two of Muse's; part of a library beats none
            assertEquals(listOf("Muse"), source.artists().map { it.name })

            responder.pageFailures = 0
            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
        }

    @Test
    fun `a song liked since is on the shelf once the shelf has aged`() =
        runTest {
            var now = 0L
            val responder = ScriptedResponder(FakeBrowseResponder(Shelf(listOf(shelf().artists.first()))))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined, clock = { now })
            assertEquals(listOf("Muse"), source.artists().map { it.name })

            responder.real = FakeBrowseResponder(shelf())
            now += 1_000
            assertEquals("a shelf read a second ago is not read again", listOf("Muse"), source.artists().map { it.name })

            now += 60 * 60 * 1_000
            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
        }

    @Test
    fun `a session still connecting is waited for, and opened once`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            responder.connecting = 5
            var opened = 0
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined, openSession = { opened++ })

            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
            assertEquals(1, opened)
        }

    @Test
    fun `a session that never comes could not be reached, and the next ask tries again`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            responder.connecting = Int.MAX_VALUE
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)

            assertUnreachable { source.artists() }
            assertUnreachable { source.playlists() }

            responder.connecting = 0
            assertEquals(listOf("Muse", "Portishead"), source.artists().map { it.name })
            assertEquals(setOf("Driving", "Quiet"), source.playlists().map { it.name }.toSet())
        }

    /** The engine fails a question asked of an invalid session and starts opening another. */
    @Test
    fun `a question the session died under is asked once more`() =
        runTest {
            val responder = ScriptedResponder(FakeBrowseResponder(shelf()))
            val source = LibrespotBrowseSource(responder, Dispatchers.Unconfined)

            responder.contextFailures = 1
            assertTrue(source.search("dark").any { it.title == "The Dark Forest" })
            responder.playlistFailures = 1
            assertEquals(setOf("Driving", "Quiet"), source.playlists().map { it.name }.toSet())
        }

    private suspend fun assertUnreachable(ask: suspend () -> Any) {
        val outcome = runCatching { ask() }
        assertTrue(
            "the source is unreachable (answer: ${outcome.getOrNull()})",
            outcome.exceptionOrNull() is SourceUnreachable,
        )
    }

    private fun signedIn(): File = folder.newFolder().also { File(it, "credentials.json").writeText("{}") }

    /**
     * Answers with what it wraps, which can be swapped mid-test, counts the
     * contexts asked, and fails as many of the coming asks of each kind as it
     * is told to.
     */
    private class ScriptedResponder(
        var real: BrowseResponder,
    ) : BrowseResponder {
        var contexts = 0
            private set

        /** How many of the coming looks at the session find it still connecting. */
        var connecting = 0
        var contextFailures = 0
        var pageFailures = 0
        var trackFailures = 0
        var playlistFailures = 0
        var artistFailures = 0

        override fun username() = if (spent(::connecting)) "" else real.username()

        override fun context(uri: String): String {
            contexts++
            return if (spent(::contextFailures)) FAILED else real.context(uri)
        }

        override fun nextPage(url: String) = if (spent(::pageFailures)) FAILED else real.nextPage(url)

        override fun tracks(uris: List<String>) = if (spent(::trackFailures)) FAILED else real.tracks(uris)

        override fun playlists() = if (spent(::playlistFailures)) FAILED else real.playlists()

        override fun uriOf(
            kind: String,
            gid: String,
        ) = real.uriOf(kind, gid)

        override fun imageUrl(fileId: String) = real.imageUrl(fileId)

        override fun artist(uri: String) = if (spent(::artistFailures)) FAILED else real.artist(uri)

        override fun albums(uris: List<String>) = real.albums(uris)

        /** Every kind of ask failing from here on. */
        fun allFailing(): ScriptedResponder =
            apply {
                contextFailures = Int.MAX_VALUE
                pageFailures = Int.MAX_VALUE
                trackFailures = Int.MAX_VALUE
                playlistFailures = Int.MAX_VALUE
                artistFailures = Int.MAX_VALUE
            }

        /** Whether one of [left] is still to come, spending it if so. */
        private fun spent(left: KMutableProperty0<Int>): Boolean {
            if (left.get() <= 0) return false
            left.set(left.get() - 1)
            return true
        }

        private companion object {
            /** What the engine answers on an invalid session. */
            const val FAILED = "error channel closed"
        }
    }
}
