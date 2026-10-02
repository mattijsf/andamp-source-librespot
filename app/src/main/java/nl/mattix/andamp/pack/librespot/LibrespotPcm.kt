// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot

import nl.mattix.andamp.core.playback.PcmProvider

/**
 * The engine's decoded audio, as a [PcmProvider].
 *
 * Any buffer size will do. A packet bigger than the buffer is served over as
 * many reads as it takes, and a read never splits a frame.
 */
internal object LibrespotPcm : PcmProvider {
    init {
        NativeLibrary.load()
    }

    override fun read(into: ByteArray): Int = nativeReadPcm(into)

    override fun discard() = nativeDiscardPcm()

    /**
     * Blocking pull of decoded audio: bytes written, 0 when nothing arrived in
     * time, and -1 only when the bytes could not be copied into [into]. Always
     * 44.1 kHz stereo, 16-bit little endian.
     */
    private external fun nativeReadPcm(into: ByteArray): Int

    /** Everything decoded and not yet read is dropped; never blocks. */
    private external fun nativeDiscardPcm()
}
