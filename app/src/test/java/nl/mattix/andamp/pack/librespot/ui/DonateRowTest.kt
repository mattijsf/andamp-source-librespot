// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The row that opens the tips on Andamp's site. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class DonateRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tap opens the tips on Andamp's site in the browser`() {
        compose.setContent { DonateRow() }
        compose.onNodeWithText("Donate").assertExists()

        compose.onNodeWithTag("pack.donate").performClick()

        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://andamp.nl/#donate", opened.dataString)
    }
}
