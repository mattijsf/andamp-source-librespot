// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.playback.BrowseSourceContractTest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * The service's answers, made up from a shelf.
 *
 * It writes the JSON the engine hands over: the `Track` protobuf printed as
 * JSON, a context in pages, and for a batch one line per uri in the order
 * asked. Names stand in for gids.
 */
internal class FakeBrowseResponder(
    private val shelf: BrowseSourceContractTest.Shelf,
    private val who: String = "someone",
    /**
     * How many tracks a page of a context holds before it names the next one.
     * Small, so the contract suite walks pages.
     */
    private val pageSize: Int = 2,
) : BrowseResponder {
    /** Uri to its metadata line, in the order a library would meet them. */
    private val rows: Map<String, String> = shelf.artists.flatMap(::linesFor).toMap()

    /** The same, for what the source has but the listener does not. */
    private val catalogue: Map<String, String> = shelf.catalogue.flatMap(::linesFor).toMap()

    /** Every row the source can see, shelved or not: what a search reaches. */
    private val everything: Map<String, String> = rows + catalogue

    /** Every row as the words a search finds it by: who made it, on what, called what. */
    private val words: Map<String, String> = (shelf.artists + shelf.catalogue).flatMap(::wordsFor).toMap()

    /** Title to the uri it was given, since a list names songs by title. */
    private val titled: Map<String, String> =
        shelf.artists
            .flatMap { artist ->
                artist.albums.flatMap { album ->
                    album.tracks.map { (title, _) ->
                        title to
                            trackUri(artist.name, album.title, title)
                    }
                }
            }.toMap()

    /** Playlist uri to the list it names. */
    private val named: Map<String, BrowseSourceContractTest.ShelfPlaylist> =
        shelf.playlists.associateBy { "spotify:playlist:${slug(it.name)}" }

    var contextAsked: String? = null
        private set

    /** How many pages were asked for by their address, which is what says a walk happened. */
    var pagesAsked = 0
        private set

    override fun username() = who

    override fun context(uri: String): String {
        contextAsked = uri
        return JSONObject()
            .put("uri", uri)
            .put("pages", JSONArray().put(page(uri, 0)))
            .toString()
    }

    /** A page after the first, shaped like one of a context's pages. */
    override fun nextPage(url: String): String {
        pagesAsked++
        val (at, uri) = url.removePrefix(PAGES).split('/', limit = 2).takeIf { it.size == 2 } ?: return "error no such page"
        return page(uri, at.toIntOrNull() ?: return "error no such page").toString()
    }

    /** One line per uri, in the order asked, with an `error` line for a uri it does not know. */
    override fun tracks(uris: List<String>) = uris.joinToString("\n") { everything[it] ?: "error no metadata" }

    /**
     * The rootlist's own shape: two arrays that line up by position, one of
     * uris and one of what each is called.
     */
    override fun playlists(): String {
        val items = JSONArray()
        val meta = JSONArray()
        // a rootlist holds the listener's folders between the playlists, so
        // this one has a folder marker too
        items.put(JSONObject().put("uri", "spotify:start-group:abc:Folder"))
        meta.put(JSONObject())
        named.forEach { (uri, list) ->
            items.put(JSONObject().put("uri", uri))
            meta.put(
                JSONObject()
                    .put("attributes", JSONObject().put("name", list.name))
                    .put("length", list.titles.size)
                    .put("ownerUsername", who),
            )
        }
        return JSONObject().put("contents", JSONObject().put("items", items).put("metaItems", meta)).toString()
    }

    /** Every track a context resolves to, before it is cut into pages. */
    private fun entriesOf(uri: String): List<String> =
        when {
            uri.startsWith("spotify:search:") -> matching(uri.removePrefix("spotify:search:"))

            uri == "spotify:user:$who:collection" -> rows.keys.toList()

            // a list's own order, which is the order it was written in
            uri.startsWith("spotify:playlist:") -> named[uri]?.titles.orEmpty().mapNotNull(titled::get)

            // a catalog album that is not on the shelf
            uri.startsWith("spotify:album:") -> tracksOfAlbum(uri.removePrefix("spotify:album:"))

            else -> emptyList()
        }

    /**
     * One page of a context: [pageSize] tracks from [at], and the address of
     * the page after it when there is one. The address is the fake's own.
     */
    private fun page(
        uri: String,
        at: Int,
    ): JSONObject {
        val all = entriesOf(uri)
        val here = all.drop(at * pageSize).take(pageSize)
        val page = JSONObject().put("tracks", JSONArray(here.map { JSONObject().put("uri", it) }))
        if ((at + 1) * pageSize < all.size) page.put("nextPageUrl", "$PAGES${at + 1}/$uri")
        return page
    }

    /**
     * A substring match over the catalog and the shelf. The terms arrive
     * escaped, as the source puts them in a uri, and are decoded here.
     */
    private fun matching(terms: String): List<String> {
        val wanted = URLDecoder.decode(terms, Charsets.UTF_8.name()).lowercase()
        return everything.keys.filter { uri -> words[uri].orEmpty().contains(wanted) }
    }

    /** An album's rows, by track number, wherever it lives. */
    private fun tracksOfAlbum(gid: String): List<String> =
        (shelf.artists + shelf.catalogue)
            .flatMap { artist -> artist.albums.map { artist to it } }
            .firstOrNull { (_, album) -> slug(album.title) == gid }
            ?.let { (artist, album) ->
                album.tracks
                    .sortedBy { (_, number) -> number ?: Int.MAX_VALUE }
                    .map { (title, _) -> trackUri(artist.name, album.title, title) }
            }.orEmpty()

    private fun linesFor(artist: BrowseSourceContractTest.ShelfArtist): List<Pair<String, String>> =
        artist.albums.flatMap { album ->
            album.tracks.map { (title, number) ->
                val uri = trackUri(artist.name, album.title, title)
                uri to line(uri, artist.name, album, title, number)
            }
        }

    private fun wordsFor(artist: BrowseSourceContractTest.ShelfArtist): List<Pair<String, String>> =
        artist.albums.flatMap { album ->
            album.tracks.map { (title, _) ->
                trackUri(artist.name, album.title, title) to "${artist.name} ${album.title} $title".lowercase()
            }
        }

    private fun line(
        uri: String,
        artist: String,
        album: BrowseSourceContractTest.ShelfAlbum,
        title: String,
        number: Int?,
    ): String {
        val artists = JSONArray().put(JSONObject().put("gid", slug(artist)).put("name", artist))
        val albumJson =
            JSONObject()
                .put("gid", slug(album.title))
                .put("name", album.title)
                .put("artist", artists)
        album.year?.let { albumJson.put("date", JSONObject().put("year", it)) }
        albumJson.put(
            "coverGroup",
            JSONObject().put(
                "image",
                JSONArray()
                    .put(JSONObject().put("fileId", "small${slug(album.title)}").put("width", 64))
                    .put(JSONObject().put("fileId", "large${slug(album.title)}").put("width", 640)),
            ),
        )
        return JSONObject()
            .put("name", title)
            .put("album", albumJson)
            .put("artist", artists)
            .put("discNumber", 1)
            .put("duration", 200_000)
            .put("canonicalUri", uri)
            .also { json -> number?.let { json.put("number", it) } }
            .toString()
    }

    /** A file id becomes an address. */
    override fun imageUrl(fileId: String) = if (fileId.isEmpty()) "" else "https://example.test/image/$fileId"

    /** A gid is what a metadata answer carries; a uri is what an endpoint takes. */
    override fun uriOf(
        kind: String,
        gid: String,
    ) = if (gid.isEmpty()) "" else "spotify:$kind:$gid"

    /**
     * One artist and their records, as groups of bare gid references with no
     * title or year, like the service's answer.
     */
    override fun artist(uri: String): String {
        val gid = uri.removePrefix("spotify:artist:")
        val them =
            (shelf.catalogue + shelf.artists).firstOrNull { slug(it.name) == gid }
                ?: return "error no such artist"
        val albums = JSONArray()
        them.albums.forEach { albums.put(JSONObject().put("gid", slug(it.title))) }
        return JSONObject()
            .put("gid", gid)
            .put("name", them.name)
            .put("albumGroup", JSONArray().put(JSONObject().put("album", albums)))
            .toString()
    }

    /** One line per uri, in the order asked, with an `error` line for a uri it does not know. */
    override fun albums(uris: List<String>): String = uris.joinToString("\n") { albumLine(it) ?: "error no metadata" }

    private fun albumLine(uri: String): String? {
        val gid = uri.removePrefix("spotify:album:")
        val found =
            (shelf.artists + shelf.catalogue)
                .flatMap { artist -> artist.albums.map { artist to it } }
                .firstOrNull { (_, album) -> slug(album.title) == gid }
                ?: return null
        val (artist, album) = found
        val one =
            JSONObject()
                .put("gid", gid)
                .put("name", album.title)
                .put("artist", JSONArray().put(JSONObject().put("name", artist.name)))
        album.year?.let { one.put("date", JSONObject().put("year", it)) }
        return one.toString()
    }

    /** Letters and digits only, like an id of the service. */
    private fun slug(text: String) = text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun trackUri(
        artist: String,
        album: String,
        title: String,
    ) = "spotify:track:${slug(artist)}${slug(album)}${slug(title)}"

    private companion object {
        /** Where the fake says its later pages are; the index and the context's uri follow. */
        const val PAGES = "hm://fake/page/"
    }
}
