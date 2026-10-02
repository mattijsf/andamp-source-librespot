// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.AlbumKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrespotAlbumsTest {
    @Test
    fun `a record is read with what it is called and when it came out`() {
        val album = LibrespotAlbums.parse(REAL)!!

        assertEquals("Selected Ambient Works 85-92", album.title)
        assertEquals("Aphex Twin", album.artist)
        assertEquals(1992, album.year)
        assertEquals("50a4E4LERzuLEvR6vq1lxw==", album.id)
    }

    @Test
    fun `a record with no title is not a row`() {
        assertNull(LibrespotAlbums.parse("""{"gid": "aa"}"""))
        assertNull(LibrespotAlbums.parse("error no metadata"))
    }

    @Test
    fun `a line that is not JSON is dropped rather than fatal`() {
        assertEquals(emptyList<Any>(), LibrespotAlbums.parseAll("{ not json"))
    }

    @Test
    fun `a record says what kind it is`() {
        assertEquals(AlbumKind.ALBUM, LibrespotAlbums.parse(REAL)!!.kind)
    }

    @Test
    fun `and a single is not an album`() {
        val single = REAL.replace("\"type\": \"ALBUM\"", "\"type\": \"SINGLE\"")

        assertEquals(AlbumKind.SINGLE, LibrespotAlbums.parse(single)!!.kind)
    }

    @Test
    fun `an EP shelves with the singles`() {
        val ep = REAL.replace("\"type\": \"ALBUM\"", "\"type\": \"EP\"")

        assertEquals(AlbumKind.SINGLE, LibrespotAlbums.parse(ep)!!.kind)
    }

    @Test
    fun `a word we do not know is a record like any other`() {
        val odd = REAL.replace("\"type\": \"ALBUM\"", "\"type\": \"AUDIOBOOK\"")

        assertEquals(AlbumKind.ALBUM, LibrespotAlbums.parse(odd)!!.kind)
    }

    private companion object {
        const val REAL =
            """{"gid": "50a4E4LERzuLEvR6vq1lxw==", "name": "Selected Ambient Works 85-92",""" +
                """ "artist": [{"gid": "0A/YQIfDQ5eGpzicFFW37g==", "name": "Aphex Twin"}],""" +
                """ "type": "ALBUM", "label": "Apollo", "date": {"year": 1992, "month": 11, "day": 9}}"""
    }
}
