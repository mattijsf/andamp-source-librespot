// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.Track
import org.json.JSONObject

/**
 * One track as the service describes it, with what [Track] does not carry:
 * which album it is on and which artist made it, as ids a library can open.
 */
internal data class LibrespotRow(
    val track: Track,
    val albumId: String,
    val albumTitle: String,
    val albumYear: Int?,
    val artistId: String,
    val artistName: String,
    val disc: Int,
    val number: Int,
    /**
     * The cover, as the service's own file id. Turning it into an address
     * needs a template that belongs to the account, which the engine holds.
     */
    val coverFileId: String,
)

/**
 * One page of a context: the entries it holds, and the addresses of what it
 * only points at.
 *
 * A page can hold nothing but an address: its own, when it came without its
 * tracks, or the next one's. Following an address is a round trip, which the
 * caller decides on.
 */
internal data class ContextPage(
    /** The track uris on it, in its own order, a repeat kept where it stands. */
    val uris: List<String>,
    /** Addresses still to be read, in the order their tracks belong after these. */
    val further: List<String>,
)

/**
 * The service's own track metadata, read into rows.
 *
 * The JSON is the `Track` protobuf printed as JSON, which the engine hands over
 * unread. Every field is treated as optional: a track with no album, no artist
 * or no number gets a blank.
 *
 * The id is the canonical uri, which is also what is played. A line without
 * one is not a track.
 */
internal object LibrespotRows {
    fun parse(line: String): LibrespotRow? {
        val json = LibrespotAnswer.json(line) ?: return null
        val uri = json.optString("canonicalUri").takeIf { it.isNotEmpty() } ?: return null
        val album = json.optJSONObject("album")
        val artist = LibrespotAnswer.firstArtist(json) ?: album?.let(LibrespotAnswer::firstArtist)
        val artistName = artist?.optString("name").orEmpty()
        val albumTitle = album?.optString("name").orEmpty()
        return LibrespotRow(
            track =
                Track(
                    id = uri,
                    artist = artistName,
                    title = json.optString("name"),
                    durationMs = json.optLong("duration"),
                    uri = uri,
                ),
            // the gid is the service's own handle; the base62 id is not in
            // this payload
            albumId = album?.optString("gid").orEmpty().ifEmpty { albumTitle },
            albumTitle = albumTitle,
            albumYear = LibrespotAnswer.year(album),
            artistId = artist?.optString("gid").orEmpty().ifEmpty { artistName },
            artistName = artistName,
            disc = json.optInt("discNumber", 1).coerceAtLeast(1),
            number = json.optInt("number", 0),
            coverFileId = coverOf(album),
        )
    }

    /** Every row that can be read; a line that cannot is dropped. */
    fun parseAll(lines: String): List<LibrespotRow> = LibrespotAnswer.lines(lines, ::parse)

    /**
     * The pages of a context answer, or of one page asked for by its address;
     * null when the answer failed.
     *
     * A context carries its tracks in `pages`, and a page asked for on its own
     * comes back as the page itself, so an answer with `pages` is read as a
     * context and one without as a single page.
     */
    fun pages(answer: String): List<ContextPage>? {
        val json = LibrespotAnswer.json(answer) ?: return null
        val pages = json.optJSONArray("pages") ?: return listOf(pageOf(json))
        return LibrespotAnswer.objects(pages).map(::pageOf)
    }

    /**
     * One page: its track uris, and the addresses of what it points at.
     *
     * A page's own address is followed only when it came without its tracks,
     * so a page that carries them and names itself is not read twice. Anything
     * on a page that is not a track, such as a podcast episode in a playlist,
     * is not an entry.
     */
    private fun pageOf(page: JSONObject): ContextPage {
        val tracks = LibrespotAnswer.objects(page.optJSONArray("tracks"))
        val own = if (tracks.isEmpty()) address(page, "pageUrl", "page_url") else ""
        return ContextPage(
            uris = tracks.map { it.optString("uri") }.filter(TRACK_URI::matches),
            further = listOf(own, address(page, "nextPageUrl", "next_page_url")).filter(String::isNotEmpty),
        )
    }

    /**
     * An address on a page, under either spelling: the camelCase name the
     * engine prints, or the snake_case one of the protobuf field.
     */
    private fun address(
        page: JSONObject,
        printed: String,
        served: String,
    ): String = page.optString(printed).ifEmpty { page.optString(served) }

    /** The widest cover the answer offers, chosen by its `width`. */
    private fun coverOf(album: JSONObject?): String {
        val images = album?.optJSONObject("coverGroup")?.optJSONArray("image") ?: return ""
        var best = ""
        var widest = -1
        for (at in 0 until images.length()) {
            val image = images.optJSONObject(at) ?: continue
            val id = image.optString("fileId").takeIf { it.isNotEmpty() } ?: continue
            val width = image.optInt("width")
            if (width > widest) {
                widest = width
                best = id
            }
        }
        return best
    }

    private val TRACK_URI = Regex("spotify:track:[A-Za-z0-9]+")
}
