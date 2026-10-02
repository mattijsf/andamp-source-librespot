// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Reads the engine's answers.
 *
 * Every question comes back as text: the service's own JSON, as one object or
 * one per line, or a line beginning `error` when it could not be had. That
 * rule and the fields every message names the same way are read here.
 */
internal object LibrespotAnswer {
    /** Whether an answer, or one line of one, is the engine saying it could not be had. */
    fun failed(answer: String): Boolean = answer.isBlank() || answer.startsWith(ERROR)

    /** The answer as JSON; null when it failed or is not JSON at all. */
    fun json(answer: String): JSONObject? = if (failed(answer)) null else runCatching { JSONObject(answer) }.getOrNull()

    /** Every line [read] could make something of; a line it could not is dropped. */
    fun <T : Any> lines(
        answer: String,
        read: (String) -> T?,
    ): List<T> = answer.lineSequence().mapNotNull(read).toList()

    /**
     * Whether a batch answer came back whole: as many lines as the [asked]
     * uris, and not every one of them a failure.
     *
     * The engine prints a line per entry the server answered with, and a line
     * beginning `error` for one it could not read. Another number of lines
     * means the server left some out or the batch failed as a whole, and a
     * batch of nothing but failures is taken as a lost session.
     */
    fun cameBack(
        answer: String,
        asked: Int,
    ): Boolean {
        val lines = answer.lines()
        return lines.size == asked && !lines.all(::failed)
    }

    /** The objects in an array, skipping anything that is not one; empty for no array at all. */
    fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)

    /** The year a message's `date` names, or null when it names none. */
    fun year(of: JSONObject?): Int? = of?.optJSONObject("date")?.optInt("year")?.takeIf { it > 0 }

    /** The first artist a message credits, which is the one it is filed under. */
    fun firstArtist(of: JSONObject): JSONObject? = of.optJSONArray("artist")?.optJSONObject(0)

    private const val ERROR = "error"
}

/** The service's uris this app builds or tells apart by prefix. */
internal object LibrespotUris {
    const val ARTIST = "spotify:artist:"
    const val ALBUM = "spotify:album:"
    const val PLAYLIST = "spotify:playlist:"

    /** Liked Songs, which the context resolver answers under the listener's own name. */
    fun likedSongs(username: String): String = "spotify:user:$username:collection"

    /**
     * A search for [terms], the words escaped.
     *
     * The engine puts a context's uri into the request's path as it is, so a
     * `#`, a `/` or a `%` in the terms would change the request. Form encoding
     * escapes those and the letters outside ASCII, and writes a space as `+`.
     */
    fun search(terms: String): String = "spotify:search:" + URLEncoder.encode(terms, Charsets.UTF_8.name())
}
