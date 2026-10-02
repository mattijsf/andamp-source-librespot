// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import nl.mattix.andamp.pack.librespot.Pairing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pack's screen, rendered: signed out with the icon in the app list, and
 * signed in with the icon removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PackScreenGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Account(
        private val here: Boolean,
        private val whose: String? = null,
    ) : LibrespotAccountActions {
        override fun signedIn() = here

        override suspend fun begin() = Pairing.NotStarted

        override fun progress(waiting: Pairing.WaitingForApproval) = waiting

        override fun forget() = true

        override fun username() = whose
    }

    private fun draw(
        account: LibrespotAccountActions,
        shown: Boolean,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        LibrespotAccountSection(account, io = UnconfinedTestDispatcher())
                        Spacer(Modifier.height(12.dp))
                        AppListRow(FakeAppList(shown))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the account nobody has signed into`() {
        draw(Account(here = false), shown = true)

        compose.onRoot().captureRoboImage("$SNAPSHOTS/pack_signed_out.png")
    }

    @Test
    fun `and the account somebody has`() {
        draw(Account(here = true, whose = "listener"), shown = false)

        compose.onRoot().captureRoboImage("$SNAPSHOTS/pack_signed_in.png")
    }

    private companion object {
        const val SNAPSHOTS = "src/test/snapshots"
    }
}
