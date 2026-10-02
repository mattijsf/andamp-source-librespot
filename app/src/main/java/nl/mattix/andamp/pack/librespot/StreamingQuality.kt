// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/**
 * The three bitrates librespot can fetch, in kbps. The number is what the
 * engine is told and what is kept on disk. 160 is librespot's default.
 */
object StreamingQuality {
    const val LOW = 96
    const val NORMAL = 160
    const val HIGH = 320

    /** Every choice, lowest first, in the order a settings page lists them. */
    val choices = listOf(LOW, NORMAL, HIGH)

    /** [kbps] when it is one of the [choices], and [NORMAL] otherwise. */
    fun of(kbps: Int): Int = kbps.takeIf { it in choices } ?: NORMAL
}
