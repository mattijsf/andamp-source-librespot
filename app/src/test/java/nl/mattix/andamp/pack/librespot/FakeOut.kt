// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider

/**
 * An [AudioOut] that records what was done to it.
 *
 * [order] is the device being taken and let go of, [buffered] is what happened
 * to the audio already on its way, and [volumes] is every gain it was given.
 * [log], when a test hands one in, is shared with a [FakeEngine], so the order
 * across the two can be asserted.
 */
class FakeOut(
    private val log: MutableList<String>? = null,
) : AudioOut {
    val order = mutableListOf<String>()
    val volumes = mutableListOf<Float>()

    /** What happened to the audio already on its way, kept apart from [order]. */
    val buffered = mutableListOf<String>()

    override val tap = null

    /** The listener the backend registered for interruptions. */
    var interruptions: ((AudioOut.Interruption) -> Unit)? = null
        private set

    /** Delivers [interruption] as the output would. */
    fun interrupt(interruption: AudioOut.Interruption) {
        interruptions?.invoke(interruption)
    }

    override fun start(provider: PcmProvider) {
        order += "start"
        log?.add("out.start")
    }

    override fun stop() {
        order += "stop"
        log?.add("out.stop")
    }

    override fun setEqualizer(settings: EqSettings) = Unit

    override fun setBalance(balance: Float) = Unit

    override fun setDsp(rack: RackSettings) = Unit

    override fun setPlugins(sources: List<String>) = Unit

    override fun setVolume(fraction: Float) {
        volumes += fraction
    }

    override fun setInterruptions(listener: ((AudioOut.Interruption) -> Unit)?) {
        interruptions = listener
    }

    override fun pause() {
        buffered += "pause"
    }

    override fun resume() {
        buffered += "resume"
    }

    override fun discard() {
        buffered += "discard"
    }
}
