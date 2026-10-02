// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * The switch that takes this app's icon out of the app list. With the icon
 * off, the row says the settings are reached from Andamp's Preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AppListRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the switch opens on what the launcher holds`() {
        compose.setContent { AppListRow(FakeAppList(shown = false)) }

        compose.onNodeWithTag("pack.applist").assertIsOff()
        compose.onNodeWithText(WHERE).assertExists()
    }

    @Test
    fun `turning it off hides the icon and says where the settings are now`() {
        val list = FakeAppList(shown = true)
        compose.setContent { AppListRow(list) }
        compose.onNodeWithTag("pack.applist").assertIsOn()
        compose.onNodeWithText(WHERE).assertDoesNotExist()

        compose.onNodeWithTag("pack.applist").performClick()

        assertEquals(listOf(false), list.told)
        compose.onNodeWithTag("pack.applist").assertIsOff()
        compose.onNodeWithText(WHERE).assertExists()
    }

    @Test
    fun `turning it back on shows the icon again`() {
        val list = FakeAppList(shown = false)
        compose.setContent { AppListRow(list) }

        compose.onNodeWithTag("pack.applist").performClick()

        assertEquals(listOf(true), list.told)
        compose.onNodeWithTag("pack.applist").assertIsOn()
        compose.onNodeWithText(WHERE).assertDoesNotExist()
    }

    private companion object {
        const val WHERE = "Open these settings from Andamp: Preferences > Music sources."
    }
}
