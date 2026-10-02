// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service's own answer, read.
 *
 * [REAL] is one line the engine handed over on 3 September 2026, kept
 * verbatim, so the parser is checked against a real answer and not only
 * against the fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotRowsTest {
    @Test
    fun `a real answer becomes a row`() {
        val row = LibrespotRows.parse(REAL)!!

        assertEquals("Crestfallen", row.track.title)
        assertEquals("Imminence", row.track.artist)
        assertEquals("Crestfallen", row.albumTitle)
        assertEquals(2026, row.albumYear)
        assertEquals(244_064L, row.track.durationMs)
        assertEquals(1, row.number)
        assertEquals(1, row.disc)
    }

    /** Bitrate and sample rate belong to a playing of the song, so a row carries neither. */
    @Test
    fun `a row says nothing about bitrate or sample rate`() {
        val row = LibrespotRows.parse(REAL)!!

        assertNull(row.track.sampleRateKhz)
        assertNull(row.track.bitrateKbps)
    }

    @Test
    fun `the id is the uri`() {
        val row = LibrespotRows.parse(REAL)!!

        assertEquals("spotify:track:15tdjaSaSNFxjrKtj52kDZ", row.track.id)
        assertEquals("spotify:track:15tdjaSaSNFxjrKtj52kDZ", row.track.uri)
    }

    @Test
    fun `an album is identified by its gid, not by what it is called`() {
        val row = LibrespotRows.parse(REAL)!!

        assertEquals("mdS15d0MR3ijrEA2roRpmw==", row.albumId)
        assertEquals("9IkBdBkISbys2gfsFt+enw==", row.artistId)
    }

    @Test
    fun `a track the engine could not fetch is not a row`() {
        assertNull(LibrespotRows.parse("error no metadata"))
        assertNull(LibrespotRows.parse(""))
        assertNull(LibrespotRows.parse("{\"name\": \"no uri, so nothing to play\"}"))
    }

    @Test
    fun `a line that is not JSON at all is dropped rather than fatal`() {
        assertEquals(emptyList<LibrespotRow>(), LibrespotRows.parseAll("{ this is not json"))
    }

    @Test
    fun `uris come out of a context in the order it gave them`() {
        val context =
            """{"pages": [{"tracks": [{"uri": "spotify:track:aaa"}, {"uri": "spotify:track:bbb"}]}]}"""

        assertEquals(listOf("spotify:track:aaa", "spotify:track:bbb"), LibrespotRows.pages(context)!!.single().uris)
    }

    @Test
    fun `a track a list holds twice comes out twice`() {
        val context =
            """{"pages": [{"tracks": [{"uri": "spotify:track:aaa"}, {"uri": "spotify:track:bbb"},""" +
                """ {"uri": "spotify:track:aaa"}]}]}"""

        assertEquals(
            listOf("spotify:track:aaa", "spotify:track:bbb", "spotify:track:aaa"),
            LibrespotRows.pages(context)!!.single().uris,
        )
    }

    @Test
    fun `a context that failed holds no pages at all`() {
        assertNull(LibrespotRows.pages("error no session"))
        assertNull(LibrespotRows.pages(""))
        assertNull(LibrespotRows.pages("{ not json"))
    }

    @Test
    fun `a page says where the next one is`() {
        val context =
            """{"pages": [{"tracks": [{"uri": "spotify:track:aaa"}], "nextPageUrl": "hm://context-resolve/next"}]}"""

        assertEquals(listOf("hm://context-resolve/next"), LibrespotRows.pages(context)!!.single().further)
    }

    @Test
    fun `a page that came without its tracks is only its address`() {
        val context =
            """{"pages": [{"tracks": [{"uri": "spotify:track:aaa"}]}, {"pageUrl": "hm://context-resolve/second"}]}"""

        val pages = LibrespotRows.pages(context)!!

        assertEquals(listOf(ContextPage(listOf("spotify:track:aaa"), emptyList())), pages.take(1))
        assertEquals(ContextPage(emptyList(), listOf("hm://context-resolve/second")), pages[1])
    }

    /** A page that carries its tracks and names itself is not followed to its own address. */
    @Test
    fun `a page that carries its tracks is not asked for again`() {
        val context =
            """{"pages": [{"pageUrl": "hm://context-resolve/this", "tracks": [{"uri": "spotify:track:aaa"}]}]}"""

        assertEquals(emptyList<String>(), LibrespotRows.pages(context)!!.single().further)
    }

    @Test
    fun `a page asked for on its own is read as one page`() {
        val page = """{"tracks": [{"uri": "spotify:track:ccc"}], "nextPageUrl": "hm://context-resolve/third"}"""

        assertEquals(
            listOf(ContextPage(listOf("spotify:track:ccc"), listOf("hm://context-resolve/third"))),
            LibrespotRows.pages(page),
        )
    }

    @Test
    fun `an address in the service's own spelling is read too`() {
        val page = """{"tracks": [], "next_page_url": "hm://context-resolve/fourth"}"""

        assertEquals(listOf("hm://context-resolve/fourth"), LibrespotRows.pages(page)!!.single().further)
    }

    @Test
    fun `only tracks are entries`() {
        // a playlist can hold a podcast episode, which is not a track
        val context =
            """{"pages": [{"tracks": [{"uri": "spotify:episode:eee"}, {"uri": "spotify:track:aaa"}]}]}"""

        assertEquals(listOf("spotify:track:aaa"), LibrespotRows.pages(context)!!.single().uris)
    }

    @Test
    fun `a context with no pages is one empty page rather than a failure`() {
        assertEquals(listOf(ContextPage(emptyList(), emptyList())), LibrespotRows.pages("""{"uri": "spotify:search:zzz"}"""))
    }

    @Test
    fun `the cover is the biggest one offered`() {
        val row = LibrespotRows.parse(REAL)!!

        // 640 wide, where 300 and 64 are also offered
        assertEquals("q2dhbQAAsnPj+Ppe6/MXlTLCnHg=", row.coverFileId)
    }

    @Test
    fun `a track whose album has no cover carries none`() {
        val bare =
            """{"name": "X", "album": {"gid": "a", "name": "A"}, "canonicalUri": "spotify:track:x"}"""

        assertEquals("", LibrespotRows.parse(bare)!!.coverFileId)
    }

    private companion object {
        /** One line as the engine printed it. */
        const val REAL =
            """{"gid": "I8Gd9GzeS5CpwyuGJ9ivHw==", "name": "Crestfallen", "album": {"gid": "mdS15d0MR3ijrEA2roRpmw==",""" +
                """ "name": "Crestfallen", "artist": [{"gid": "9IkBdBkISbys2gfsFt+enw==", "name": "Imminence"}],""" +
                """ "type": "SINGLE", "label": "Sumerian Records", "date": {"year": 2026, "month": 7, "day": 22},""" +
                """ "coverGroup": {"image": [{"fileId": "q2dhbQAAHgLj+Ppe6/MXlTLCnHg=", "size": "DEFAULT",""" +
                """ "width": 300, "height": 300},""" +
                """ {"fileId": "q2dhbQAASFHj+Ppe6/MXlTLCnHg=", "size": "SMALL", "width": 64, "height": 64},""" +
                """ {"fileId": "q2dhbQAAsnPj+Ppe6/MXlTLCnHg=", "size": "LARGE", "width": 640, "height": 640}]}},""" +
                """ "artist": [{"gid": "9IkBdBkISbys2gfsFt+enw==", "name": "Imminence"}], "number": 1,""" +
                """ "discNumber": 1, "duration": 244064, "popularity": 65, "hasLyrics": true,""" +
                """ "canonicalUri": "spotify:track:15tdjaSaSNFxjrKtj52kDZ"}"""
    }
}
