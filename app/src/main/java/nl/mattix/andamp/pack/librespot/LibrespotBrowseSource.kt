// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable

/**
 * The listener's library on the service: Liked Songs shelved by artist and
 * album, their playlists, and the service's catalog behind them.
 *
 * Liked Songs is the library. Saved albums and followed artists are behind
 * collection endpoints librespot does not wrap. Every liked track names its
 * artist and album, so the shelves are grouping over that one list, and an
 * album shows the tracks of it that were liked.
 *
 * A search, the listener's playlists and an artist's discography are asked of
 * the service directly, through [BrowseResponder].
 *
 * An answer that failed is never kept as the answer. Every ask knows whether
 * it came back whole, and a shelf that did not is asked for again next time.
 * An ask that failed with nothing to show throws [SourceUnreachable]; one that
 * came back whole and empty is an empty list; one that read part of an answer
 * before failing shows the part. See `Fetched.shown`.
 */
class LibrespotBrowseSource internal constructor(
    private val responder: BrowseResponder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Whether a credential is stored, which is what [available] reports.
     *
     * The session behind a credential takes a moment to connect, and the
     * source is available during that moment.
     *
     * Asked when the source is built and again, on [io], whenever a question
     * finds no session; [available] reads the last answer, so it touches no
     * disk.
     */
    private val canAnswer: () -> Boolean = { true },
    /**
     * Opens a session when a question finds none.
     *
     * Called on [io] by the first question that has to wait, because whoever
     * builds this source can be on the main thread. Opening is idempotent on
     * the engine's side, so the library and the player share one session.
     */
    private val openSession: () -> Unit = {},
    /** Milliseconds from any fixed point, for how long the shelves are kept. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : BrowseSource {
    override val capabilities = SHELVES

    override val available: Boolean get() = credential

    override suspend fun artists(): List<LibraryArtist> =
        shelved()
            .groupBy { it.artistId }
            .map { (id, rows) ->
                LibraryArtist(
                    id = id,
                    name = rows.first().artistName,
                    albumCount = rows.distinctBy { it.albumId }.size,
                    trackCount = rows.size,
                )
            }.sortedBy { it.name.lowercase() }

    /**
     * An artist's records: from the catalog when [artistId] is a uri, which
     * [findArtists] hands out, and from the shelf when it is a gid.
     */
    override suspend fun albums(artistId: String?): List<LibraryAlbum> {
        if (artistId != null && artistId.startsWith(LibrespotUris.ARTIST)) return asked { recordsOf(artistId) }.shown()
        return shelvedAlbums(artistId)
    }

    /**
     * An artist's records from the catalog, each under its album uri, so
     * [tracks] can tell a catalog album from a shelved one by its id.
     */
    private fun recordsOf(artistUri: String): Fetched<LibraryAlbum> {
        val answer = responder.artist(artistUri)
        if (LibrespotAnswer.failed(answer)) return Fetched.failed()
        val uris =
            LibrespotArtist
                .albumGids(answer)
                .mapNotNull { gid -> responder.uriOf("album", gid).takeIf { it.isNotEmpty() } }
        val batches = uris.chunked(BATCH).map { batch -> batch.size to responder.albums(batch) }
        val records =
            batches
                .flatMap { (_, lines) -> LibrespotAlbums.parseAll(lines) }
                .mapNotNull { album ->
                    val uri = responder.uriOf("album", album.id).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    album.copy(id = uri)
                }.distinctBy { it.id }
                .sortedByDescending { it.year ?: 0 }
        return Fetched(records, complete = batches.all { (count, lines) -> LibrespotAnswer.cameBack(lines, count) })
    }

    private suspend fun shelvedAlbums(artistId: String?): List<LibraryAlbum> =
        shelved()
            .filter { artistId == null || it.artistId == artistId }
            .groupBy { it.albumId }
            .map { (id, rows) ->
                LibraryAlbum(
                    id = id,
                    title = rows.first().albumTitle,
                    artist = rows.first().artistName,
                    year = rows.first().albumYear,
                    trackCount = rows.size,
                )
            }.sortedBy { it.title.lowercase() }

    /**
     * An album's tracks: from the catalog when [albumId] is an album uri, and
     * from the shelf otherwise.
     */
    override suspend fun tracks(albumId: String): List<Track> {
        if (albumId.startsWith(LibrespotUris.ALBUM)) return asked { rowsOf(walk(albumId)) }.shown().map { it.track }
        return shelved()
            .filter { it.albumId == albumId }
            // the album's own order, by number
            .sortedWith(compareBy({ it.disc }, { it.number }))
            .map { it.track }
    }

    /**
     * Artists in the service's catalog.
     *
     * Derived from a track search, which is the search this engine can make:
     * the artists behind the matching tracks.
     */
    override suspend fun findArtists(
        query: String,
        limit: Int,
    ): List<LibraryArtist> {
        val terms = query.trim()
        if (terms.isEmpty()) return emptyList()
        return asked {
            val rows = rowsOf(walk(LibrespotUris.search(terms), pages = 1).distinct())
            Fetched(artistsIn(rows.items, limit), rows.complete)
        }.shown()
    }

    /**
     * The artists behind some matches, each under a uri [albums] can open,
     * ordered by how often they turn up in the matches.
     */
    private fun artistsIn(
        rows: List<LibrespotRow>,
        limit: Int,
    ): List<LibraryArtist> =
        rows
            .filter { it.artistId.isNotEmpty() && it.artistName.isNotEmpty() }
            .groupBy { it.artistId }
            .entries
            .sortedByDescending { it.value.size }
            .take(limit)
            .mapNotNull { (gid, matched) ->
                val uri = responder.uriOf("artist", gid).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LibraryArtist(id = uri, name = matched.first().artistName)
            }

    /**
     * A search of the service's catalog, not of the shelf. One page of it: a
     * search answers with its best matches first.
     */
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> {
        val terms = query.trim()
        if (terms.isEmpty()) return emptyList()
        val hits =
            asked {
                val found = walk(LibrespotUris.search(terms), pages = 1)
                rowsOf(Fetched(found.items.distinct().take(limit), found.complete))
            }
        return hits.shown().map { it.track }
    }

    /** The listener's own playlists. Not kept with the shelves: asked for each time. */
    override suspend fun playlists(): List<LibraryPlaylist> =
        asked { who ->
            val answer = responder.playlists()
            if (LibrespotAnswer.failed(answer)) {
                Fetched.failed()
            } else {
                Fetched(LibrespotPlaylists.parse(answer, me = who), complete = true)
            }
        }.shown()

    /** One list's tracks, in the list's own order, with repeats kept. */
    override suspend fun playlistTracks(id: String): List<Track> {
        if (!id.startsWith(LibrespotUris.PLAYLIST)) return emptyList()
        return asked { rowsOf(walk(id)) }.shown().map { it.track }
    }

    /**
     * Liked Songs, kept for [SHELF_LIFE_MS]: every shelf is a view of it.
     *
     * Kept only when it was read whole. A read that stopped part way is shown
     * and one that got nothing throws [SourceUnreachable], and neither is
     * kept, so the next shelf asks again.
     */
    private suspend fun shelved(): List<LibrespotRow> {
        held?.takeIf(::fresh)?.let { return it.rows }
        return gate.withLock { held?.takeIf(::fresh)?.rows ?: reshelved() }
    }

    private suspend fun reshelved(): List<LibrespotRow> {
        val read = asked(::liked)
        if (read.complete) {
            held = Held(read.items, clock())
            return read.items
        }
        // a library read whole earlier is preferred to one read part way now
        return held?.rows ?: read.shown()
    }

    private fun fresh(shelf: Held): Boolean = clock() - shelf.at < SHELF_LIFE_MS

    /** Every liked song, every page of them, without repeats. */
    private fun liked(who: String): Fetched<LibrespotRow> = rowsOf(walk(LibrespotUris.likedSongs(who)).distinct())

    /** Walked uris as rows, whole only when both the walk and the naming were. */
    private fun rowsOf(walked: Fetched<String>): Fetched<LibrespotRow> {
        val named = named(walked.items)
        return Fetched(named.items, walked.complete && named.complete)
    }

    /**
     * Every entry a context holds, its pages followed to the end or to [pages]
     * answers, whichever comes first.
     *
     * A context answers with its first page and says where the rest are: as a
     * page carrying only its own address, or as the address of the page after
     * it. A page that fails ends the walk with what was read before it, marked
     * as partial. An address already read is not read again.
     */
    private fun walk(
        uri: String,
        pages: Int = MAX_PAGES,
    ): Fetched<String> {
        val first = LibrespotRows.pages(responder.context(uri)) ?: return Fetched.failed()
        val walk = Walk(budget = pages - 1)
        val complete = follow(first, walk)
        return Fetched(walk.entries, complete)
    }

    /** Reads [pages] into [walk] in order, each followed by what it points at; false when one could not be read. */
    private fun follow(
        pages: List<ContextPage>,
        walk: Walk,
    ): Boolean =
        pages.all { page ->
            walk.entries += page.uris
            page.further.all { url -> followed(url, walk) }
        }

    private fun followed(
        url: String,
        walk: Walk,
    ): Boolean {
        // an address read already is a loop and one past the budget is the end
        // chosen here; neither is a failure
        if (walk.budget <= 0 || !walk.asked.add(url)) return true
        walk.budget--
        val more = LibrespotRows.pages(responder.nextPage(url)) ?: return false
        return follow(more, walk)
    }

    /**
     * Uris turned into rows, a batch at a time, each back in the place it was
     * asked for.
     *
     * A uri is asked about once however often it appears, and its row goes
     * back everywhere it stood.
     */
    private fun named(uris: List<String>): Fetched<LibrespotRow> {
        val found = HashMap<String, LibrespotRow>()
        val whole =
            uris
                .distinct()
                .chunked(BATCH)
                .map { batch -> nameInto(found, batch) }
                .all { it }
        return Fetched(uris.mapNotNull(found::get), whole)
    }

    /**
     * One batch's rows, filed under the uri each was asked for; false when the
     * batch did not come back whole.
     *
     * When the answer has as many lines as the batch has uris, the lines are
     * taken to be in the order asked and each row is filed by position: a row
     * names its canonical uri, which may not be the one asked for. The engine
     * passes on the server's order and does not guarantee this. An answer with
     * another number of lines is filed by each row's own uri.
     */
    private fun nameInto(
        found: MutableMap<String, LibrespotRow>,
        batch: List<String>,
    ): Boolean {
        val answer = responder.tracks(batch)
        val lines = answer.lines()
        if (lines.size == batch.size) {
            batch.zip(lines).forEach { (uri, line) -> LibrespotRows.parse(line)?.let { found[uri] = addressed(it) } }
        } else {
            LibrespotRows.parseAll(answer).forEach { found[it.track.id] = addressed(it) }
        }
        return LibrespotAnswer.cameBack(answer, batch.size)
    }

    /**
     * The row with its cover's address on it.
     *
     * The address is not in the answer: the engine substitutes the file id
     * into a template that belongs to the account. No network is involved.
     */
    private fun addressed(row: LibrespotRow): LibrespotRow {
        if (row.coverFileId.isEmpty()) return row
        val url = responder.imageUrl(row.coverFileId).takeIf { it.isNotEmpty() } ?: return row
        return row.copy(track = row.track.copy(artworkUri = url))
    }

    /**
     * A question put to the engine once there is a session, and put once more
     * if what came back failed or stopped short.
     *
     * A session that becomes invalid fails the question asked of it, and the
     * engine starts opening another as it answers, so the second ask waits
     * for that. The fuller of the two answers is kept.
     *
     * The question runs on [io] and blocks there; the waiting suspends.
     */
    private suspend fun <T> asked(question: (who: String) -> Fetched<T>): Fetched<T> {
        var best = Fetched.failed<T>()
        repeat(ATTEMPTS) {
            val who = session() ?: return best
            val fetched = withContext(io) { question(who) }
            if (fetched.complete) return fetched
            if (fetched.items.size >= best.items.size) best = fetched
        }
        return best
    }

    /**
     * Who is signed in, waited for up to [PATIENCE_MS]; null when no
     * credential is stored or the wait ran out.
     *
     * The session connects on the engine's own thread, so a question can
     * arrive before it is connected. The wait suspends.
     */
    private suspend fun session(): String? {
        repeat((PATIENCE_MS / POLL_MS).toInt()) { look ->
            val who = withContext(io) { signedInAs(firstLook = look == 0) } ?: return null
            if (who.isNotEmpty()) return who
            delay(POLL_MS)
        }
        return null
    }

    /**
     * One look at the session: who it is for, empty while it connects, and null
     * when no credential is stored.
     *
     * The first look of a wait opens a session when there is none. The engine
     * reopens by itself for a question that finds its session gone, once it
     * knows where the credential is; in a process where nothing has asked yet,
     * this tells it.
     */
    private fun signedInAs(firstLook: Boolean): String? {
        val who = responder.username()
        if (who.isNotEmpty()) return who
        credential = canAnswer()
        if (!credential) return null
        if (firstLook) openSession()
        return ""
    }

    /** The last answer [canAnswer] gave. */
    @Volatile
    private var credential: Boolean = canAnswer()

    private val gate = Mutex()

    /** Liked Songs as last read whole, and when; null until then. */
    @Volatile
    private var held: Held? = null

    /** What an ask got back, and whether that is all of it. */
    private class Fetched<T>(
        val items: List<T>,
        val complete: Boolean,
    ) {
        fun distinct(): Fetched<T> = Fetched(items.distinct(), complete)

        /**
         * What a listener is shown of this: the items, unless there are none
         * because the ask failed, which throws. A partial read shows what it
         * read, and a whole answer with nothing in it is an empty list.
         */
        fun shown(): List<T> {
            if (!complete && items.isEmpty()) throw SourceUnreachable("the engine could not answer")
            return items
        }

        companion object {
            fun <T> failed(): Fetched<T> = Fetched(emptyList(), complete = false)
        }
    }

    /** Where a walk through a context has got to: what it read, where it went, how far it may still go. */
    private class Walk(
        var budget: Int,
    ) {
        val entries = mutableListOf<String>()
        val asked = mutableSetOf<String>()
    }

    private class Held(
        val rows: List<LibrespotRow>,
        val at: Long,
    )

    internal companion object {
        /**
         * Which shelves this library has. A constant, so the pack can describe
         * itself before anybody is signed in and a source exists.
         */
        val SHELVES =
            BrowseCapabilities(
                hasArtists = true,
                hasAlbums = true,
                // the service's own search, over its whole catalog
                canSearch = true,
                hasPlaylists = true,
                hasCatalogue = true,
            )

        const val BATCH = 200

        /** How long a question waits for a session to connect. */
        const val PATIENCE_MS = 15_000L
        const val POLL_MS = 100L

        /** The ask and one retry; see [asked]. */
        const val ATTEMPTS = 2

        /** The most pages one walk follows, a bound on a far end that keeps handing out addresses. */
        const val MAX_PAGES = 500

        /** How long Liked Songs is kept before it is read again. */
        const val SHELF_LIFE_MS = 120_000L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}
