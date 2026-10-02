// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import android.content.Context
import nl.mattix.andamp.pack.librespot.StreamingQuality
import java.io.File

/**
 * Where this pack keeps the credential and the streaming quality the listener
 * chose.
 *
 * The credential is not in the cache, which the system may empty at any time;
 * that would sign the listener out. It is not in the ordinary files either,
 * which Android copies to a cloud backup and to a new phone: the credential
 * is a reusable login tied to the desktop client id the session uses (see
 * `LibrespotAccount`). The no-backup directory is copied by neither, and the
 * manifest turns backup off as well.
 */
internal object PackStore {
    /** The directory inside the pack's no-backup files; librespot writes `credentials.json` into it. */
    private const val DIRECTORY = "librespot"

    /** The preferences file, which holds the listener's choices and never the credential. */
    private const val PREFERENCES = "librespot"

    private const val QUALITY = "streaming_quality_kbps"

    fun credentials(context: Context): File = File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    /** The streaming quality the listener chose, in kbps; [StreamingQuality.NORMAL] until they choose. */
    fun quality(context: Context): Int = StreamingQuality.of(preferences(context).getInt(QUALITY, StreamingQuality.NORMAL))

    fun setQuality(
        context: Context,
        kbps: Int,
    ) {
        preferences(context).edit().putInt(QUALITY, StreamingQuality.of(kbps)).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
