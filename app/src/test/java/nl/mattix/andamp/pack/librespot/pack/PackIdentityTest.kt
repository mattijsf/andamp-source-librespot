// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.pack.librespot.LibrespotBackend
import nl.mattix.andamp.pack.librespot.LibrespotBrowseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player is told about this pack when it binds: the label, the
 * scheme and the shelves all come from the descriptor.
 */
class PackIdentityTest {
    @Test
    fun `the descriptor says who the pack is and where its updates come from`() {
        val said = PackIdentity.descriptor("1.2.3", LibrespotBackend.RENDERING, LibrespotBrowseSource.SHELVES)

        assertEquals("spotify", said.scheme)
        assertEquals("Librespot", said.label)
        assertEquals("1.2.3", said.version)
        assertTrue("the descriptor carries an update address", said.updates.isNotEmpty())
        assertTrue("the pack is skinnable", said.skinnable)
    }

    @Test
    fun `what it can be asked is the library's own answer`() {
        val said = PackIdentity.descriptor("1.2.3", LibrespotBackend.RENDERING, LibrespotBrowseSource.SHELVES)

        assertEquals(LibrespotBrowseSource.SHELVES, said.browse)
    }

    @Test
    fun `what it can do is the backend's own answer`() {
        val said = PackIdentity.descriptor("1.2.3", LibrespotBackend.RENDERING, LibrespotBrowseSource.SHELVES)

        assertEquals(LibrespotBackend.RENDERING.canSeek, said.canSeek)
        assertEquals(LibrespotBackend.RENDERING.canEditQueue, said.canEditQueue)
        assertEquals(LibrespotBackend.RENDERING.canAttenuate, said.canAttenuate)
    }

    /** The equalizer, the balance and the rack are the player's chain, so the descriptor does not claim them. */
    @Test
    fun `the shaping of the audio is not the pack's to describe`() {
        val said = PackIdentity.descriptor("1.2.3", LibrespotBackend.RENDERING, LibrespotBrowseSource.SHELVES)

        assertEquals(false, said.playback.hasEqualizer)
        assertEquals(false, said.playback.hasBalance)
        assertEquals(false, said.playback.hasDsp)
    }

    /** The player reads the sample format before it renders. */
    @Test
    fun `the pack says it hands its audio over, and what those samples are`() {
        val said = PackIdentity.descriptor("1.2.3", LibrespotBackend.RENDERING, LibrespotBrowseSource.SHELVES)

        assertTrue("the pack hands its audio over to the player", said.handsOverAudio)
        assertEquals(PcmProvider.SAMPLE_RATE_HZ, said.sampleRate)
        assertEquals(PcmProvider.CHANNELS, said.channels)
    }
}
