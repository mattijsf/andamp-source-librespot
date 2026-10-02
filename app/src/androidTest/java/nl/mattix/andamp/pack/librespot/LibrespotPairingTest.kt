// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Signs a device in through the device-pair flow.
 *
 * It needs a person to approve the code, so it runs only when asked for:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.pairNow=true
 * ```
 *
 * Every run is a new device authorization against the service's desktop
 * client id. The other device tests are handed the credential this produces;
 * see [DeviceCredential].
 *
 * The code and the page appear in `adb logcat -s AndAmpPairing`. Gradle
 * uninstalls the test package when a run ends, taking its storage with it, so
 * the credential has to be copied out by hand before then.
 */
@RunWith(AndroidJUnit4::class)
class LibrespotPairingTest {
    private val asked = InstrumentationRegistry.getArguments().getString("pairNow") == "true"

    @Test
    fun aPersonCanSignThisDeviceIn() {
        assumeTrue(
            "not asked for; pass -Pandroid.testInstrumentationRunnerArguments.pairNow=true",
            asked,
        )
        val cache = cacheDir()

        val state = Pairing.of(LibrespotPairing.nativeBeginPairing(cache.absolutePath))
        val waiting = state as? Pairing.WaitingForApproval
        assertEquals("pairing starts: $state", true, waiting != null)
        Log.i(TAG, "CODE ${waiting!!.code}")
        Log.i(TAG, "PAGE ${waiting.url}")

        val settled = awaitApproval(waiting)

        assertEquals(Pairing.Paired, settled)
        assertEquals(true, LibrespotPairing.nativeIsPaired(cache.absolutePath))
    }

    private fun awaitApproval(waiting: Pairing.WaitingForApproval): Pairing {
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        var state: Pairing = waiting
        while (System.currentTimeMillis() < giveUp && state is Pairing.WaitingForApproval) {
            Thread.sleep(POLL_MS)
            state = Pairing.progress(waiting, LibrespotPairing.nativeStatus())
        }
        return state
    }

    // where the app keeps it: the no-backup files
    private fun cacheDir(): File =
        File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "librespot")
            .also { it.mkdirs() }

    private companion object {
        const val TAG = "AndAmpPairing"

        /** How long a person is given to approve the code. */
        const val PATIENCE_MS = 900_000L
        const val POLL_MS = 1_000L
    }
}
