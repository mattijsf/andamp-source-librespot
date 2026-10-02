// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.LibraryAlbum

/** A record, read from the service's album metadata, which an artist's answer refers to by gid. */
internal object LibrespotAlbums {
    fun parse(line: String): LibraryAlbum? {
        val album = LibrespotAnswer.json(line) ?: return null
        val gid = album.optString("gid").takeIf { it.isNotEmpty() } ?: return null
        val title = album.optString("name").takeIf { it.isNotEmpty() } ?: return null
        return LibraryAlbum(
            id = gid,
            title = title,
            artist = LibrespotAnswer.firstArtist(album)?.optString("name").orEmpty(),
            year = LibrespotAnswer.year(album),
            kind = kindOf(album.optString("type")),
        )
    }

    fun parseAll(lines: String): List<LibraryAlbum> = LibrespotAnswer.lines(lines, ::parse)

    /** The service's word for what a record is. A type not listed here is an album. */
    private fun kindOf(type: String): AlbumKind =
        when (type) {
            "SINGLE", "EP" -> AlbumKind.SINGLE
            "COMPILATION" -> AlbumKind.COMPILATION
            else -> AlbumKind.ALBUM
        }
}
