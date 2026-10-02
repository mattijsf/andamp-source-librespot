// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An artist's answer, read.
 *
 * [REAL] is the head of an answer the engine handed over on 4 September 2026,
 * cut to a few records and otherwise verbatim. The proto allows whole album
 * messages inside the groups; the service sends bare references.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotArtistTest {
    @Test
    fun `an artist's records are references, and they all come out`() {
        val gids = LibrespotArtist.albumGids(REAL)

        assertEquals(listOf("50a4E4LERzuLEvR6vq1lxw==", "7rFDPndZRRm/I1dHB5k96g==", "4XAzJ+5WRGC6HF0akaXYTA=="), gids)
    }

    @Test
    fun `singles count as records and guest appearances do not`() {
        // appearsOnGroup is not read
        assertEquals(3, LibrespotArtist.albumGids(REAL).size)
    }

    @Test
    fun `a record referred to twice is one record`() {
        val twice =
            """{"name": "X", "albumGroup": [{"album": [{"gid": "aa"}]}], "singleGroup": [{"album": [{"gid": "aa"}]}]}"""

        assertEquals(listOf("aa"), LibrespotArtist.albumGids(twice))
    }

    @Test
    fun `an answer that failed holds nothing`() {
        assertEquals(emptyList<String>(), LibrespotArtist.albumGids("error no session"))
        assertEquals(emptyList<String>(), LibrespotArtist.albumGids("{ not json"))
    }

    private companion object {
        const val REAL =
            """{"gid": "0A/YQIfDQ5eGpzicFFW37g==", "name": "Aphex Twin", "popularity": 67,""" +
                """ "topTrack": [{"country": "NL", "track": [{"gid": "8r0mKzMoTWin6KcZeoE6fQ=="}]}],""" +
                """ "albumGroup": [{"album": [{"gid": "50a4E4LERzuLEvR6vq1lxw=="}]},""" +
                """ {"album": [{"gid": "7rFDPndZRRm/I1dHB5k96g=="}]}],""" +
                """ "singleGroup": [{"album": [{"gid": "4XAzJ+5WRGC6HF0akaXYTA=="}]}],""" +
                """ "appearsOnGroup": [{"album": [{"gid": "somebodyElsesRecord=="}]}]}"""
    }
}
