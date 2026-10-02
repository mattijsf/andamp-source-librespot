// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.test.TestScope
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.playback.PlaybackBackendContractTest

/**
 * The backend against the SDK's contract suite for every backend, over a
 * [FakeEngine] whose clock runs on the test scope. Needs no account, device or
 * native library.
 */
class LibrespotBackendTest : PlaybackBackendContractTest() {
    override fun TestScope.createBackend(tracks: List<Track>): PlaybackBackend {
        val playable = tracks.map { it.copy(uri = "spotify:track:${it.id}") }
        return LibrespotBackend(
            playable,
            backgroundScope,
            FakeEngine(backgroundScope, playable),
        )
    }
}
