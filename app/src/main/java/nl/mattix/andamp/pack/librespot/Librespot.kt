// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/** The one place the native library is loaded. */
internal object NativeLibrary {
    /**
     * Whether the engine loaded.
     *
     * A load that threw from an initializer would reach the first caller as an
     * `ExceptionInInitializerError`, so the failure is kept as an answer. A JVM
     * test has no `.so` and reads false.
     */
    val loaded: Boolean =
        runCatching { System.loadLibrary("andamp_librespot") }.isSuccess

    /** Triggers the load. */
    fun load() = loaded
}

/** Signing a device in. What a surface draws from it is [Pairing]. */
internal object LibrespotPairing {
    init {
        NativeLibrary.load()
    }

    /** Whether this device already holds a credential of its own. */
    external fun nativeIsPaired(cacheDir: String): Boolean

    /**
     * Starts the device-pair login. Returns the code and the page to enter it
     * on, one per line, or an empty string when it could not be started.
     *
     * A new attempt ends any earlier one: its code stops working here, and
     * nothing it says reaches [nativeStatus].
     */
    external fun nativeBeginPairing(cacheDir: String): String

    /**
     * Where the latest pairing has got to: "paired", a line beginning
     * "pairing failed: " when it ended any other way, or something else while
     * it is still waiting. "not started" before the first attempt.
     *
     * Only the pairing writes it; opening a session, playing and asking
     * questions do not.
     */
    external fun nativeStatus(): String
}

/**
 * The engine's control verbs, over JNI. The queue and the transport rules are
 * Kotlin above this, and the samples are [LibrespotPcm]'s.
 */
@Suppress("TooManyFunctions") // a JNI boundary has one function per verb of the player behind it
internal object Librespot {
    init {
        NativeLibrary.load()
    }

    /** The pinned librespot's own version, for the device test that checks the pin. */
    external fun nativeVersion(): String

    /**
     * Open a session from the stored credential, ready to be told what to play.
     *
     * Idempotent on a live session that is still the credential's, so every
     * load asks for one. An invalid session, or one belonging to somebody the
     * credential does not name, is let go of and replaced.
     */
    external fun nativeOpen(cacheDir: String): Boolean

    /**
     * Takes the account off this process: the player stops and goes, the
     * session is shut down, and a load waiting for one is dropped.
     *
     * Where the credential lives is forgotten too, so nothing opens a session
     * again until [nativeOpen] is asked. Never waits.
     */
    external fun nativeClose()

    external fun nativeLoad(
        trackUri: String,
        startPlaying: Boolean,
        positionMs: Long,
    )

    external fun nativePlay()

    external fun nativePause()

    /** Stops the track, or drops the load still waiting for its session. */
    external fun nativeStop()

    external fun nativeSeek(positionMs: Long)

    external fun nativeSetVolume(fraction: Float)

    /**
     * The streaming quality in kbps (96, 160 or 320, and anything else is 160)
     * for the next player the engine builds.
     *
     * Process-wide. librespot fixes a player's quality when it builds one, so
     * the next load after a change opens a player at the new quality.
     */
    external fun nativeSetBitrate(kbps: Int)

    /**
     * A new reader has the event queue: what was said before this is not for
     * it, and [nativeNextEvent] drops it. Never blocks.
     */
    external fun nativeClaimEvents()

    /** One line the engine had to say since the latest claim, or empty when it had nothing in time. */
    external fun nativeNextEvent(timeoutMs: Long): String
}

/**
 * Questions about the library, over JNI. Each blocks until it is answered or
 * given up on, and can be asked whether or not anything is playing.
 *
 * None of them waits for a session. Asked with none, or with an invalid one,
 * they ask for one to be opened when the credential's location is known, and
 * answer at once: an error, or empty where that is the answer. The caller
 * waits and asks again.
 *
 * The answers cross unread. The parsers are [LibrespotAnswer] for the shape
 * every answer shares, [LibrespotRows] for a context's tracks, and
 * [LibrespotPlaylists], [LibrespotAlbums] and [LibrespotArtist].
 */
internal object LibrespotQueries {
    init {
        NativeLibrary.load()
    }

    /**
     * The tracks a uri of the service resolves to, as the service's own context JSON.
     *
     * Two uris are not ids: `spotify:user:<id>:collection` is Liked Songs and
     * `spotify:search:<terms>` is a search. A failure comes back beginning
     * `error`.
     */
    external fun nativeBrowse(uri: String): String

    /**
     * The page a context's `nextPageUrl` names, as the service's own page JSON,
     * printed the way [nativeBrowse] prints a context. A failure comes back
     * beginning `error`.
     */
    external fun nativeNextPage(url: String): String

    /** Who is signed in, which is part of the uri for Liked Songs. Empty without a session. */
    external fun nativeUsername(): String

    /**
     * Metadata for a batch of track uris, one per line in and one JSON object
     * per line out: a line per track the server answered with, in the
     * server's order, and a line beginning `error` for one it could not read.
     * The engine does not put the lines in the order of the uris asked for.
     */
    external fun nativeTracks(uris: String): String

    /** The listener's own playlists, as the service's own JSON. Blocking. */
    external fun nativePlaylists(): String

    /**
     * A gid as the uri that names the same thing; [kind] is "artist" or
     * "album". Empty when the gid was not one.
     */
    external fun nativeUri(
        kind: String,
        gid: String,
    ): String

    /** Where a cover lives, from its file id; empty without a session. */
    external fun nativeImageUrl(fileId: String): String

    /** One artist and everything they released, as the service's own JSON. Blocking. */
    external fun nativeArtist(uri: String): String

    /**
     * Metadata for a batch of album uris, one JSON object per line, lined up
     * as [nativeTracks] says.
     *
     * An artist's answer names their records by gid alone, so their titles and
     * years come from here.
     */
    external fun nativeAlbums(uris: String): String

    /** Invalidates the session on purpose. Called only by the device test `LibrespotHealsTest`. */
    external fun nativeBreakSession()
}
