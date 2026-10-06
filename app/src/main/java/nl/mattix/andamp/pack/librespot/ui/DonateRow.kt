// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The part of Andamp's site with the ways to leave a tip. */
internal const val DONATE = "https://andamp.nl/#donate"

/**
 * A row that opens [DONATE] in the browser. The whole row takes the press.
 * Without a browser on the phone nothing happens.
 */
@Composable
internal fun DonateRow() {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(role = Role.Button) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE))) }
                }.padding(20.dp)
                .testTag("pack.donate"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Donate", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "This music source and Andamp are free. A tip helps keep them going.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        }
    }
}
