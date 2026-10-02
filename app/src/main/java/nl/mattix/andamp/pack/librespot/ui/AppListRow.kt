// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.pack.common.AppListEntry

/**
 * Whether this app's icon is in the listener's app list.
 *
 * Andamp's Preferences open this screen anyway, so the icon is the listener's
 * to keep or remove. With it off, the line under the title says where the
 * settings are. Why the icon is a manifest alias is in
 * [nl.mattix.andamp.pack.common.PackLauncherEntry].
 *
 * The whole row is the switch.
 */
@Composable
internal fun AppListRow(entry: AppListEntry) {
    // read once: nothing but this row changes it while the page is open
    var shown by remember(entry) { mutableStateOf(entry.shown) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .toggleable(value = shown, role = Role.Switch) {
                    entry.show(it)
                    shown = it
                }.padding(20.dp)
                .testTag("pack.applist"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show in app list", style = MaterialTheme.typography.titleMedium)
                if (!shown) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open these settings from Andamp: Preferences > Music sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            // drawn, not pressed: the row above takes the press
            Switch(checked = shown, onCheckedChange = null)
        }
    }
}
