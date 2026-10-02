// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import nl.mattix.andamp.core.playback.BrowseSource
import java.io.File

/** Builds the listener's library on the service. What crosses out is a [BrowseSource]. */
object LibrespotLibrary {
    /**
     * The library, or null when there is no engine or no credential.
     *
     * Cheap: a look at whether the credential file is there, because the
     * first ask can come from the main thread. The source opens a session from
     * the first question that finds none. Opening is idempotent on the
     * engine's side, so the library and the player share one session.
     */
    fun source(cacheDir: File): BrowseSource? {
        if (!LibrespotPlayback.available) return null
        return over(LibrespotBrowse, cacheDir) { Librespot.nativeOpen(cacheDir.absolutePath) }.takeIf { it.available }
    }

    /**
     * The source [source] builds, over any responder.
     *
     * It can answer while the credential file is there
     * ([LibrespotAccount.hasCredential]), which is a look at the directory and
     * not a read of the file.
     */
    internal fun over(
        responder: BrowseResponder,
        cacheDir: File,
        io: CoroutineDispatcher = Dispatchers.IO,
        openSession: () -> Unit = {},
    ): LibrespotBrowseSource =
        LibrespotBrowseSource(
            responder = responder,
            io = io,
            canAnswer = { LibrespotAccount.hasCredential(cacheDir) },
            openSession = openSession,
        )
}
