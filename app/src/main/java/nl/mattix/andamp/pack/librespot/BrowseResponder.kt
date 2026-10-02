// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/**
 * The questions this app can ask the service.
 *
 * An interface, so the library code above it is tested against a fake with no
 * account, no device and no Rust. The answers are text: the service's own JSON
 * crosses unread and the parsers read it. A question that could not be
 * answered comes back beginning `error`, which [LibrespotAnswer] reads.
 */
internal interface BrowseResponder {
    /** Who is signed in; part of the uri that names Liked Songs. Empty before a session. */
    fun username(): String

    /**
     * The tracks a uri of the service resolves to, as the context JSON.
     *
     * `spotify:user:<id>:collection` is Liked Songs and `spotify:search:<terms>`
     * is a search. The answer carries uris and little else; [tracks] names
     * them. A long list comes as its first page; [nextPage] follows the rest.
     */
    fun context(uri: String): String

    /**
     * A page a context pointed at, as the page JSON.
     *
     * [url] is an address a context answer handed out: a page's own, when it
     * came without its tracks, or the page after it.
     */
    fun nextPage(url: String): String

    /**
     * Metadata for those uris: one JSON object per line for each track the
     * server answered, in the server's order, and a line beginning `error` for
     * one it could not read. The engine does not put the lines in the order of
     * the uris asked for.
     */
    fun tracks(uris: List<String>): String

    /** The listener's own playlists, as the rootlist JSON. */
    fun playlists(): String

    /**
     * A gid as the uri naming the same thing; [kind] is "artist" or "album".
     *
     * The service's metadata answers carry gids and its endpoints take uris;
     * librespot does the conversion.
     */
    fun uriOf(
        kind: String,
        gid: String,
    ): String

    /**
     * Where a cover can be fetched, from the file id a row carries.
     *
     * The address is made from a template on the account. Empty when there is
     * no session to ask.
     */
    fun imageUrl(fileId: String): String

    /** One artist and everything they released, as the artist JSON. */
    fun artist(uri: String): String

    /**
     * Metadata for those album uris, one JSON object per line.
     *
     * An artist's answer names their records by gid alone, so their titles
     * and years come from here.
     */
    fun albums(uris: List<String>): String
}

/** The engine, over JNI. */
internal object LibrespotBrowse : BrowseResponder {
    override fun username(): String = LibrespotQueries.nativeUsername()

    override fun context(uri: String): String = LibrespotQueries.nativeBrowse(uri)

    override fun nextPage(url: String): String = LibrespotQueries.nativeNextPage(url)

    override fun tracks(uris: List<String>): String =
        if (uris.isEmpty()) "" else LibrespotQueries.nativeTracks(uris.joinToString("\n"))

    override fun playlists(): String = LibrespotQueries.nativePlaylists()

    override fun uriOf(
        kind: String,
        gid: String,
    ): String = LibrespotQueries.nativeUri(kind, gid)

    override fun imageUrl(fileId: String): String = if (fileId.isEmpty()) "" else LibrespotQueries.nativeImageUrl(fileId)

    override fun artist(uri: String): String = LibrespotQueries.nativeArtist(uri)

    override fun albums(uris: List<String>): String =
        if (uris.isEmpty()) "" else LibrespotQueries.nativeAlbums(uris.joinToString("\n"))
}
