// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three streaming qualities: the one chosen is the one marked, a press
 * chooses another, and the page says when a choice is heard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class StreamingQualityRowTest {
    @get:Rule
    val compose = createComposeRule()

    private class Quality(
        override var kbps: Int,
    ) : StreamingQualityChoice {
        val told = mutableListOf<Int>()

        override fun choose(kbps: Int) {
            told += kbps
            this.kbps = kbps
        }
    }

    @Test
    fun `the row opens on the quality chosen, and says when a change is heard`() {
        compose.setContent { StreamingQualityRow(Quality(160)) }

        compose.onNodeWithTag("pack.librespot.quality.160").assertIsSelected()
        compose.onNodeWithTag("pack.librespot.quality.96").assertIsNotSelected()
        compose.onNodeWithTag("pack.librespot.quality.320").assertIsNotSelected()
        compose.onNodeWithText("Normal · 160 kbps").assertExists()
        compose.onNodeWithText("Applies from the next song you start.").assertExists()
    }

    @Test
    fun `pressing another quality chooses it`() {
        val quality = Quality(160)
        compose.setContent { StreamingQualityRow(quality) }

        compose.onNodeWithTag("pack.librespot.quality.320").performClick()

        assertEquals(listOf(320), quality.told)
        compose.onNodeWithTag("pack.librespot.quality.320").assertIsSelected()
        compose.onNodeWithTag("pack.librespot.quality.160").assertIsNotSelected()
    }

    @Test
    fun `pressing the quality already chosen changes nothing`() {
        val quality = Quality(96)
        compose.setContent { StreamingQualityRow(quality) }

        compose.onNodeWithTag("pack.librespot.quality.96").performClick()

        assertEquals(emptyList<Int>(), quality.told)
    }
}
