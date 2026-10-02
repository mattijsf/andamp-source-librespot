// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.pack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.pack.librespot.StreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The streaming quality as it is kept: normal until the listener chooses,
 * their choice after, and only one of the three choices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the quality is normal until the listener chooses`() {
        assertEquals(160, PackStore.quality(context))
    }

    @Test
    fun `a choice is what is read back`() {
        PackStore.setQuality(context, StreamingQuality.HIGH)
        assertEquals(320, PackStore.quality(context))

        PackStore.setQuality(context, StreamingQuality.LOW)
        assertEquals(96, PackStore.quality(context))
    }

    @Test
    fun `a quality that is not one of the choices is kept as the normal one`() {
        PackStore.setQuality(context, 256)

        assertEquals(160, PackStore.quality(context))
    }
}
