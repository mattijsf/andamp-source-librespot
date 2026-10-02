// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import nl.mattix.andamp.pack.librespot.Pairing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The account section shows whether this phone is signed in.
 *
 * The listener approves the code somewhere else, and the credential lands a
 * moment later. Guards against the page reading the disk once and still
 * saying "Not signed in" after the credential has landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class LibrespotAccountSectionTest {
    @get:Rule
    val compose = createComposeRule()

    /** An account nobody is signed into until [arrives] is set. */
    private class LateCredential(
        private val arrives: AtomicBoolean,
    ) : LibrespotAccountActions {
        override fun signedIn(): Boolean = arrives.get()

        override suspend fun begin(): Pairing = Pairing.Failed("not asked for here")

        override fun progress(waiting: Pairing.WaitingForApproval): Pairing = waiting

        override fun forget(): Boolean = true

        override fun username(): String? = "listener".takeIf { arrives.get() }
    }

    @Test
    fun `a credential that lands while the page is open signs the page in by itself`() {
        val landed = AtomicBoolean(false)
        compose.setContent {
            LibrespotAccountSection(LateCredential(landed), io = UnconfinedTestDispatcher())
        }

        compose.onNodeWithText("Not signed in").assertIsDisplayed()

        // the approval happens elsewhere: nothing on this screen is pressed
        landed.set(true)
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        compose.onNodeWithText("Signed in as listener").assertIsDisplayed()
    }
}
