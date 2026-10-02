// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The packaged library loads on a real device, and the engine inside it is the
 * one this repository pins.
 */
@RunWith(AndroidJUnit4::class)
class LibrespotLoadTest {
    @Test
    fun theLinkedEngineIsThePinnedOne() {
        assertEquals(PINNED_SEMVER, Librespot.nativeVersion())
    }

    /**
     * The status always says something.
     *
     * It does not assert "not started": every device test class runs in one
     * process, so another test may have paired first.
     */
    @Test
    fun thePairingStatusIsAlwaysAnswerable() {
        assertTrue("the pairing status is not blank", LibrespotPairing.nativeStatus().isNotBlank())
    }

    /** An empty cache directory is not paired; needs no account. */
    @Test
    fun anEmptyCacheIsNotPaired() {
        val empty =
            File(
                ApplicationProvider.getApplicationContext<Context>().cacheDir,
                "librespot-empty",
            ).also { it.mkdirs() }

        assertFalse(LibrespotPairing.nativeIsPaired(empty.absolutePath))
    }

    private companion object {
        /** `version` in the submodule's own Cargo.toml, at commit a1b66d3c. */
        const val PINNED_SEMVER = "0.8.0"
    }
}
