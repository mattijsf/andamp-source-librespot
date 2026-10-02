// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.pack.librespot.StreamingQuality

/**
 * The streaming quality: three choices as radio rows, each pressed as a whole.
 *
 * The line under them says when a choice takes effect: the engine fixes a
 * quality when it starts a song, so the song playing keeps its own.
 */
@Composable
internal fun StreamingQualityRow(quality: StreamingQualityChoice) {
    // read once: nothing but this row changes it while the page is open
    var chosen by remember(quality) { mutableIntStateOf(quality.kbps) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp)) {
            Text(
                "Streaming quality",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.selectableGroup()) {
                StreamingQuality.choices.forEach { kbps ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kbps == chosen, role = Role.RadioButton) {
                                if (kbps != chosen) {
                                    quality.choose(kbps)
                                    chosen = kbps
                                }
                            }.padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("pack.librespot.quality.$kbps"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // drawn, not pressed: the row takes the press
                        RadioButton(selected = kbps == chosen, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(labelOf(kbps), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Applies from the next song you start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** A choice as the listener reads it. */
private fun labelOf(kbps: Int): String =
    when (kbps) {
        StreamingQuality.LOW -> "Low · 96 kbps"
        StreamingQuality.HIGH -> "High · 320 kbps"
        else -> "Normal · 160 kbps"
    }
