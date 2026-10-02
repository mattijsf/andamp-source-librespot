// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * The account a device test is handed, for the tests that need one.
 *
 * Pairing is a device authorization against the service's desktop client id,
 * so [LibrespotPairingTest] does it once and these tests use the credential it
 * produced:
 *
 * ```
 * adb push credentials.json /data/local/tmp/
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.librespotCredentials=/data/local/tmp/credentials.json
 * ```
 *
 * Without that argument every test that asks for it skips.
 */
internal object DeviceCredential {
    /**
     * The cache the engine is opened with, holding a fresh copy of the
     * credential. Skips the test when there is none to copy.
     *
     * Copied every time, because signing out deletes it. It goes in the
     * no-backup files, where the app keeps it.
     */
    fun cacheOrSkip(): File {
        val supplied = InstrumentationRegistry.getArguments().getString("librespotCredentials")
        assumeTrue(
            "no credential; pass -Pandroid.testInstrumentationRunnerArguments.librespotCredentials=<path>",
            supplied != null,
        )
        val blob = File(supplied!!)
        assumeTrue("no credential at ${blob.path}", blob.isFile)
        val cache =
            File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "librespot")
                .also { it.mkdirs() }
        blob.copyTo(File(cache, "credentials.json"), overwrite = true)
        return cache
    }

    /** Opening is fire and forget; a session answers with a username once it is connected. */
    fun awaitSession(): Boolean {
        val giveUp = System.currentTimeMillis() + PATIENCE_MS
        while (System.currentTimeMillis() < giveUp) {
            if (LibrespotQueries.nativeUsername().isNotEmpty()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private const val PATIENCE_MS = 30_000L
    private const val POLL_MS = 250L
}
