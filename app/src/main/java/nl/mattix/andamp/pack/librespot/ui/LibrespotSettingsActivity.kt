// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.pack.common.PackLauncherEntry
import nl.mattix.andamp.pack.librespot.R
import nl.mattix.andamp.pack.librespot.pack.PackIdentity

/**
 * The pack's settings screen.
 *
 * The player opens it by intent, so it runs none of this code. It is a
 * launcher entry as well, through an alias in the manifest, unless the
 * listener removes the icon.
 *
 * It uses Material with the phone's own colors, light or dark as the listener
 * has it. The skin engine is the player's.
 */
class LibrespotSettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // a pack opened before Andamp ever bound it has had no service start to
        // put its icon in the app list; see PackLauncherEntry
        PackLauncherEntry(this, PackIdentity.LAUNCHER_ALIAS).restore()
        setContent {
            PackTheme {
                Surface(Modifier.fillMaxSize()) {
                    // the title shrinks into the bar as the page scrolls
                    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
                    Scaffold(
                        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
                        topBar = {
                            LargeTopAppBar(
                                title = { Text(PackIdentity.LABEL) },
                                scrollBehavior = bar,
                                navigationIcon = {
                                    IconButton(
                                        onClick = { finish() },
                                        modifier =
                                            Modifier
                                                .semantics { contentDescription = "Back" }
                                                .testTag("pack.settings.back"),
                                    ) { Icon(painterResource(R.drawable.ic_back), contentDescription = null) }
                                },
                            )
                        },
                    ) { padding ->
                        Column(
                            Modifier
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        ) {
                            LibrespotPage()
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phone's own colors: Material You from Android 12 on, and a plain scheme
 * before that, light or dark as the system is set.
 */
@Composable
private fun PackTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialTheme(colorScheme = colors, content = content)
}
