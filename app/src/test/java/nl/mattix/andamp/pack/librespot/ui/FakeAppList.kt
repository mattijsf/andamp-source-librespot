// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import nl.mattix.andamp.pack.common.AppListEntry

/** An app list with no launcher behind it: it holds what it was told and records each call. */
internal class FakeAppList(
    override var shown: Boolean = true,
) : AppListEntry {
    val told = mutableListOf<Boolean>()

    override fun show(shown: Boolean) {
        told += shown
        this.shown = shown
    }
}
