// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.playback.PcmProvider

/**
 * Who this pack says it is: the label, the scheme and the capabilities the
 * player is told over the wire.
 */
internal object PackIdentity {
    /** What this pack's rows start with: `spotify:track:…`, and where their ids come from. */
    const val SCHEME = "spotify"

    /**
     * The manifest's launcher alias, which is the icon in the app list. The
     * alias exists only in the manifest, so this string and that
     * `android:name` are kept in step by hand.
     */
    const val LAUNCHER_ALIAS = "nl.mattix.andamp.pack.librespot.ui.LauncherEntry"

    /** What a listener reads: the row in Preferences, the entry in Media Library. */
    const val LABEL = "Librespot"

    /** Where this pack's own `update.json` is. */
    const val UPDATES = "https://andamp.nl/extensions/librespot/update.json"

    /** The page a listener gets this pack from. */
    const val HOME = "https://andamp.nl/extensions/librespot"

    /**
     * Everything the player reads when it binds.
     *
     * The capabilities are the backend's and the library's own, handed in.
     * The equalizer, the balance and the rack are not in it: the chain that
     * holds them is the player's, and this pack hands over the audio it
     * decodes.
     */
    fun descriptor(
        version: String,
        playback: Capabilities,
        browse: BrowseCapabilities,
    ): PackDescriptor =
        PackDescriptor(
            scheme = SCHEME,
            label = LABEL,
            version = version,
            canSeek = playback.canSeek,
            canEditQueue = playback.canEditQueue,
            canAttenuate = playback.canAttenuate,
            hasArtists = browse.hasArtists,
            hasAlbums = browse.hasAlbums,
            canSearch = browse.canSearch,
            hasPlaylists = browse.hasPlaylists,
            hasCatalogue = browse.hasCatalogue,
            // a skin can be worn while this pack's tracks play
            skinnable = true,
            updates = UPDATES,
            home = HOME,
            // the samples cross to the player, which renders them through its
            // equalizer, rack and visualizer
            handsOverAudio = true,
            // read from PcmProvider, so the player and the pack agree about
            // what the bytes in the pipe are
            sampleRate = PcmProvider.SAMPLE_RATE_HZ,
            channels = PcmProvider.CHANNELS,
        )
}
