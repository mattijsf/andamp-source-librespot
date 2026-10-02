// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/**
 * One artist's records, read out of the service's artist metadata.
 *
 * The answer lists their album, single and compilation groups as bare gid
 * references, with no title or year. A batched [BrowseResponder.albums] names
 * them.
 *
 * `appearsOnGroup` is not read: records by somebody else that this artist
 * appears on are left out.
 */
internal object LibrespotArtist {
    /** The gids of everything they released, in the order the answer gives them, without repeats. */
    fun albumGids(json: String): List<String> {
        val artist = LibrespotAnswer.json(json) ?: return emptyList()
        return GROUPS
            .flatMap { group -> LibrespotAnswer.objects(artist.optJSONArray(group)) }
            .flatMap { LibrespotAnswer.objects(it.optJSONArray("album")) }
            .mapNotNull { it.optString("gid").takeIf(String::isNotEmpty) }
            .distinct()
    }

    /** The groups that are read; `appearsOnGroup` is left out. */
    private val GROUPS = listOf("albumGroup", "singleGroup", "compilationGroup")
}
