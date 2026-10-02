// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Signing this device in, as a surface outside this package uses it: whether
 * there is a credential, starting the flow, and reading where it has got to.
 *
 * The credential is minted under the desktop client id the session uses (see
 * `app/third_party/PATCHES.md`) and is tied to that id, not to this phone or
 * this install. It is a reusable login in a file, so it is made readable by
 * this app alone, and the caller chooses a directory that no backup or device
 * transfer copies.
 */
object LibrespotAccount {
    /**
     * Whether this device holds a credential the engine can read.
     *
     * False without the engine, which is the case in a JVM test. Reads the
     * file, so it is not for the main thread; [hasCredential] is.
     */
    fun signedIn(cacheDir: File): Boolean {
        if (!LibrespotPlayback.available) return false
        keepToOurselves(cacheDir)
        return LibrespotPairing.nativeIsPaired(cacheDir.absolutePath)
    }

    /**
     * Whether the credential file is there: no read, no engine, and the file's
     * permissions left as they are. Whether the engine can use the file is
     * [signedIn]'s to say.
     */
    fun hasCredential(cacheDir: File): Boolean = File(cacheDir, CREDENTIALS).isFile

    /**
     * Makes the credential readable and writable by this app alone.
     *
     * librespot writes it with the umask it finds. Done at every [signedIn],
     * so a file that was copied in is restricted too.
     */
    private fun keepToOurselves(cacheDir: File) {
        val file = File(cacheDir, CREDENTIALS)
        if (!file.isFile) return
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    /**
     * Who this device is signed in as, read from the credential file, or null
     * when there is none or it cannot be read.
     *
     * No network and no engine. It reads a file, so a page asks it off the
     * main thread.
     */
    fun username(cacheDir: File): String? =
        runCatching {
            org.json
                .JSONObject(File(cacheDir, CREDENTIALS).readText())
                .optString("username")
                .takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * Starts the device-pair flow; the result is what to put on screen.
     *
     * Runs on the IO dispatcher, because asking for a code blocks until the
     * service answers or the engine gives up.
     *
     * The session this process holds is closed first: a new sign-in can be a
     * different account.
     */
    suspend fun begin(cacheDir: File): Pairing {
        if (!LibrespotPlayback.available) return Pairing.Failed("this build has no Spotify engine")
        return withContext(Dispatchers.IO) {
            Librespot.nativeClose()
            cacheDir.mkdirs()
            Pairing.of(LibrespotPairing.nativeBeginPairing(cacheDir.absolutePath))
        }
    }

    /** Where a wait has got to, asked again as often as a surface likes. */
    fun progress(waiting: Pairing.WaitingForApproval): Pairing = Pairing.progress(waiting, LibrespotPairing.nativeStatus())

    /**
     * Signs out by deleting the credential and closing the session. True when
     * [signedIn] then says no.
     *
     * The file goes before the session. A session is only opened from the
     * file, so once it is gone a library question that finds no session
     * cannot open one as the account being signed out.
     *
     * A delete and a read, so not for the main thread.
     */
    fun forget(cacheDir: File): Boolean {
        File(cacheDir, CREDENTIALS).delete()
        if (LibrespotPlayback.available) Librespot.nativeClose()
        return !signedIn(cacheDir)
    }

    private const val CREDENTIALS = "credentials.json"
}
