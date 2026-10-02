// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.LibraryPlaylist

/**
 * The listener's own playlists, read out of the service's rootlist.
 *
 * The rootlist is two lists that line up by position: `items` carries the uris
 * and `metaItems` carries each one's name and length.
 *
 * A rootlist also holds the listener's folders, as `start-group` and
 * `end-group` markers between the playlists. Those are dropped.
 */
internal object LibrespotPlaylists {
    /**
     * The lists in a rootlist answer; empty when it failed.
     *
     * [me] is who is signed in. A list owned by [me] gets no owner.
     */
    fun parse(
        json: String,
        me: String,
    ): List<LibraryPlaylist> {
        val contents = LibrespotAnswer.json(json)?.optJSONObject("contents") ?: return emptyList()
        val items = contents.optJSONArray("items") ?: return emptyList()
        val meta = contents.optJSONArray("metaItems")
        return (0 until items.length()).mapNotNull { at ->
            val uri = items.optJSONObject(at)?.optString("uri").orEmpty()
            if (!uri.startsWith(LibrespotUris.PLAYLIST)) return@mapNotNull null
            val about = meta?.optJSONObject(at)
            val name = about?.optJSONObject("attributes")?.optString("name").orEmpty()
            // a list with no name is left out
            if (name.isEmpty()) return@mapNotNull null
            LibraryPlaylist(
                id = uri,
                name = name,
                trackCount = about?.optInt("length")?.takeIf { it > 0 },
                owner = about?.optString("ownerUsername")?.takeIf { it.isNotEmpty() && !it.equals(me, ignoreCase = true) },
            )
        }
    }
}
