// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import android.content.Context
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.PackAnswers
import nl.mattix.andamp.pack.common.PackServiceBase
import nl.mattix.andamp.pack.librespot.BuildConfig
import nl.mattix.andamp.pack.librespot.LibrespotAccount
import nl.mattix.andamp.pack.librespot.LibrespotBackend
import nl.mattix.andamp.pack.librespot.LibrespotBrowseSource
import nl.mattix.andamp.pack.librespot.LibrespotLibrary
import nl.mattix.andamp.pack.librespot.LibrespotPlayback

/**
 * This source's side of the wire: librespot, the account, the browse parsing
 * and one [LibrespotBackend]. The wire itself, and the lifetime around it, is
 * [PackServiceBase].
 *
 * The backend is built at the first verb, not with the service: building it
 * opens a librespot session, and the player binds to ask who this pack is and
 * who is signed in before anybody presses play.
 */
class PackService : PackServiceBase() {
    /** Where the credential is. */
    private val store by lazy { PackStore.credentials(this) }

    private val answers by lazy { PackAnswers(::library) }

    /** The library, once there is one to keep; see [library]. */
    private var shelves: BrowseSource? = null

    override val launcherAlias: String = PackIdentity.LAUNCHER_ALIAS

    override val buildsBackendWithService: Boolean = false

    override fun makeBackend(): PlaybackBackend =
        LibrespotPlayback.backend(
            cacheDir = store,
            tracks = emptyList(),
            startIndex = 0,
            scope = scope,
            out = audio,
            network = SystemNetworkWatch(this),
        )

    override fun onCreate() {
        super.onCreate()
        // set before anything can open a session, so the first player is built
        // at the listener's quality
        LibrespotPlayback.setStreamingQuality(PackStore.quality(this))
    }

    override fun descriptor(): PackDescriptor =
        PackIdentity.descriptor(
            version = BuildConfig.VERSION_NAME,
            playback = LibrespotBackend.RENDERING,
            browse = LibrespotBrowseSource.SHELVES,
        )

    /**
     * Who is signed in, read from the credential on disk.
     *
     * A file read and the engine's parse of it, answered on a binder thread.
     */
    override fun whoIsHere(): PackAccount {
        val signedIn = runCatching { LibrespotAccount.signedIn(store) }.getOrDefault(false)
        if (!signedIn) return PackAccount(signedIn = false)
        return PackAccount(signedIn = true, name = LibrespotAccount.username(store).orEmpty())
    }

    /** The work runs on the library's own dispatcher; this thread waits for it. */
    override fun answer(question: PackQuestion): PackAnswer = runBlocking { answers.to(question) }

    /**
     * Drops the library when the account goes.
     *
     * Synchronized with [library], because a question on a binder thread may
     * be reading the shelves.
     */
    @Synchronized
    override fun forgetAccount() {
        shelves = null
    }

    /**
     * The library, kept for as long as it can answer.
     *
     * Kept, because the source holds Liked Songs for a couple of minutes, so
     * drilling from an artist to an album to its tracks reads it once. One
     * that cannot answer is dropped, and the next question builds another.
     */
    @Synchronized
    private fun library(): BrowseSource? {
        shelves?.takeIf { it.available }?.let { return it }
        return LibrespotLibrary.source(store).also { shelves = it }
    }

    internal companion object {
        /** Tells every player listening that somebody signed in here. */
        fun signedIn(context: Context) = signedIn(context, PackService::class.java)

        /** Tells a pack that is playing that the account has gone. */
        fun signedOut(context: Context) = signedOut(context, PackService::class.java)
    }
}
