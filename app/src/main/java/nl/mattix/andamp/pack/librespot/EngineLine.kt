// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

/**
 * What the engine says, as one line of text.
 *
 * A line crosses JNI without a generated type or a callback: Rust writes it
 * and Kotlin reads it. A line nobody recognizes is ignored, as in [Pairing],
 * so an engine that says something new does not break this reader.
 */
internal object EngineLine {
    fun parse(line: String): EngineEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val kind = trimmed.substringBefore(' ')
        val rest = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
        return when (kind) {
            PLAYING -> rest.toLongOrNull()?.let { EngineEvent.Playing(it) }
            PAUSED -> rest.toLongOrNull()?.let { EngineEvent.Paused(it) }
            POSITION -> rest.toLongOrNull()?.let { EngineEvent.PositionChanged(it) }
            END_OF_TRACK -> EngineEvent.EndOfTrack
            UNAVAILABLE -> rest.takeIf { it.isNotEmpty() }?.let { EngineEvent.Unavailable(it) }
            FAILED -> EngineEvent.Failed(rest.ifEmpty { "unknown" })
            BITRATE -> rest.toIntOrNull()?.takeIf { it > 0 }?.let { EngineEvent.Bitrate(it) }
            else -> null
        }
    }

    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val POSITION = "position"
    const val END_OF_TRACK = "endOfTrack"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
    const val BITRATE = "bitrate"

    /**
     * A uri the engine could not read as a track: handled like a row that is
     * unavailable.
     *
     * This and [NOT_PREMIUM] are the two reasons after [FAILED] that change
     * what to do, written as the engine words them.
     */
    const val NOT_A_TRACK = "not a track uri"

    /**
     * An account that is not Premium, found out once the session is open. The
     * engine lets the session go, and no fresh connection would change it.
     */
    const val NOT_PREMIUM = "not premium"

    /**
     * How every failed connect begins (no network, a refused or timed out
     * connect, a session that closed as it opened), followed by the engine's
     * own words. The backend waits it out and tries again.
     */
    const val COULD_NOT_CONNECT = "could not connect"
}
