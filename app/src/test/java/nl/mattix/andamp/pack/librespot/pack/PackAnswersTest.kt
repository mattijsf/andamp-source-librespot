// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest.Shelf
import nl.mattix.andamp.core.playback.BrowseSourceContractTest.ShelfAlbum
import nl.mattix.andamp.core.playback.BrowseSourceContractTest.ShelfArtist
import nl.mattix.andamp.core.playback.BrowseSourceContractTest.ShelfPlaylist
import nl.mattix.andamp.core.playback.SourceUnreachable
import nl.mattix.andamp.pack.common.PackAnswers
import nl.mattix.andamp.pack.librespot.FakeBrowseResponder
import nl.mattix.andamp.pack.librespot.LibrespotBrowseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the player is told when it asks this pack about the library.
 *
 * The routing and the paging go over the real source, with the fake service
 * behind it. The cases about a source that cannot answer use a stand-in.
 *
 * Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackAnswersTest {
    @Test
    fun `an artists question comes back as artists and nothing else`() =
        runTest {
            val answer = over(shelf()).to(PackQuestion(PackQuestion.ARTISTS))

            assertEquals(listOf("Muse", "Portishead"), answer.artists.map { it.name })
            assertEquals(emptyList<Any>(), answer.tracks)
            assertEquals(emptyList<Any>(), answer.albums)
            assertFalse("a shelf read whole is not a failure", answer.failed)
            assertFalse(answer.more)
        }

    @Test
    fun `a page carries what was asked for, and says whether there is more behind it`() =
        runTest {
            val answers = over(shelf())

            val first = answers.to(PackQuestion(PackQuestion.ARTISTS, offset = 0, limit = 1))
            val second = answers.to(PackQuestion(PackQuestion.ARTISTS, offset = 1, limit = 1))

            assertEquals(listOf("Muse"), first.artists.map { it.name })
            assertTrue("the first page says there is more", first.more)
            assertEquals(listOf("Portishead"), second.artists.map { it.name })
            assertFalse("the last page says there is no more", second.more)
        }

    @Test
    fun `an offset past the end of a shelf is the end of it, not a failure`() =
        runTest {
            val answer = over(shelf()).to(PackQuestion(PackQuestion.ARTISTS, offset = 50, limit = 10))

            assertEquals(emptyList<Any>(), answer.artists)
            assertFalse(answer.more)
            assertFalse("an offset past the end is not a failure", answer.failed)
        }

    @Test
    fun `an album is asked for by the id its own shelf gave out`() =
        runTest {
            val answers = over(shelf())
            val albums = answers.to(PackQuestion(PackQuestion.ALBUMS))
            val dummy = albums.albums.first { it.title == "Dummy" }

            val tracks = answers.to(PackQuestion(PackQuestion.TRACKS, id = dummy.id))

            assertEquals(listOf("Mysterons", "Sour Times"), tracks.tracks.map { it.title })
        }

    @Test
    fun `a list's tracks keep the order the list keeps them in`() =
        runTest {
            val answers = over(shelf())
            val lists = answers.to(PackQuestion(PackQuestion.PLAYLISTS))
            val driving = lists.playlists.first { it.name == "Driving" }

            val tracks = answers.to(PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = driving.id))

            assertEquals(listOf("Space Debris", "Mysterons", "Cryogen"), tracks.tracks.map { it.title })
        }

    @Test
    fun `a search reaches far enough to fill the page that was asked for`() =
        runTest {
            val library = Watched()

            PackAnswers { library }.to(PackQuestion(PackQuestion.SEARCH, query = "muse", offset = 20, limit = 10))

            assertEquals("the search reaches the offset plus the limit", 30, library.reached)
        }

    @Test
    fun `a pack nobody is signed in to answers failed`() =
        runTest {
            val answer = PackAnswers { null }.to(PackQuestion(PackQuestion.ARTISTS))

            assertTrue("a pack with nobody signed in answers failed", answer.failed)
        }

    @Test
    fun `a library that cannot answer yet is not an empty one`() =
        runTest {
            val answer = PackAnswers { Watched(available = false) }.to(PackQuestion(PackQuestion.ARTISTS))

            assertTrue(answer.failed)
        }

    @Test
    fun `a library that threw answers failed with no rows`() =
        runTest {
            val answer = PackAnswers { Watched(throws = true) }.to(PackQuestion(PackQuestion.ARTISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.artists)
        }

    @Test
    fun `a library that says it could not be reached is a failed answer, not an empty shelf`() =
        runTest {
            val answer = PackAnswers { Watched(unreachable = true) }.to(PackQuestion(PackQuestion.ARTISTS))

            assertTrue("an unreachable source answers failed", answer.failed)
        }

    /** Cancellation is rethrown and not turned into an answer. */
    @Test
    fun `a question withdrawn while the library answers is withdrawn, not failed`() =
        runTest {
            val thrown = runCatching { PackAnswers { Watched(withdrawn = true) }.to(PackQuestion(PackQuestion.ARTISTS)) }

            assertTrue("cancellation is rethrown: $thrown", thrown.exceptionOrNull() is CancellationException)
        }

    @Test
    fun `a question this pack has never heard of is not answered with an empty shelf`() =
        runTest {
            val answer = over(shelf()).to(PackQuestion("constellations"))

            assertTrue(answer.failed)
        }

    /** The real source, over the fake service. */
    private fun over(shelf: Shelf): PackAnswers {
        val source = LibrespotBrowseSource(FakeBrowseResponder(shelf), Dispatchers.Unconfined)
        return PackAnswers { source }
    }

    /** Two artists, two lists, and enough rows that a page of one leaves something behind. */
    private fun shelf() =
        Shelf(
            artists =
                listOf(
                    ShelfArtist(
                        "Muse",
                        listOf(
                            ShelfAlbum("The Wow! Signal", 2014, listOf("Cryogen" to 4, "Space Debris" to 10)),
                            ShelfAlbum("Absolution", 2003, listOf("Stockholm Syndrome" to 1)),
                        ),
                    ),
                    ShelfArtist("Portishead", listOf(ShelfAlbum("Dummy", 1994, listOf("Mysterons" to 1, "Sour Times" to 2)))),
                ),
            playlists = listOf(ShelfPlaylist("Driving", listOf("Space Debris", "Mysterons", "Cryogen"))),
        )

    /**
     * A library that is unavailable, throws, says it could not be reached or
     * is cancelled, as it is told to, and records the limit a search was given.
     * Everything else answers empty.
     */
    private class Watched(
        override val available: Boolean = true,
        private val throws: Boolean = false,
        private val unreachable: Boolean = false,
        private val withdrawn: Boolean = false,
    ) : BrowseSource {
        override val capabilities = BrowseCapabilities()

        /** The limit the last search was given. */
        var reached: Int = 0
            private set

        override suspend fun artists(): List<LibraryArtist> {
            check(!throws) { "the library is not answering" }
            if (unreachable) throw SourceUnreachable("the server is down")
            if (withdrawn) throw CancellationException("nobody is waiting any more")
            return emptyList()
        }

        override suspend fun albums(artistId: String?): List<LibraryAlbum> = emptyList()

        override suspend fun tracks(albumId: String): List<Track> = emptyList()

        override suspend fun search(
            query: String,
            limit: Int,
        ): List<Track> {
            reached = limit
            return emptyList()
        }
    }
}
