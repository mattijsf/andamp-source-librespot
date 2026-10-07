// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.librespot.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.pack.common.PackLauncherEntry
import nl.mattix.andamp.pack.librespot.LibrespotAccount
import nl.mattix.andamp.pack.librespot.LibrespotPlayback
import nl.mattix.andamp.pack.librespot.Pairing
import nl.mattix.andamp.pack.librespot.R
import nl.mattix.andamp.pack.librespot.StreamingQuality
import nl.mattix.andamp.pack.librespot.pack.PackIdentity
import nl.mattix.andamp.pack.librespot.pack.PackService
import nl.mattix.andamp.pack.librespot.pack.PackStore

/**
 * The pack's one screen: the account on this device.
 *
 * It shows who is signed in and the way in or out. When the account changes
 * the page tells the pack's own service, which tells every player bound to
 * it; see [LibrespotAccountActions.changed] and `PackService.signedOut`.
 *
 * The sign-in is the service's device-pair flow: a short code and a page to
 * type it on, approved on any device. So this draws a code and a wait.
 */
@Composable
fun LibrespotPage() {
    // no engine: a build packaged without the native libraries, or a JVM
    // test, draws this line instead of a sign-in
    if (!LibrespotPlayback.available) {
        Text("This build has no Spotify engine.", style = MaterialTheme.typography.bodyLarge)
        return
    }
    val context = LocalContext.current
    val account = remember(context) { EngineAccount(context.applicationContext) }
    val engine = remember { LibrespotPlayback.engineVersion }
    val appList = remember(context) { PackLauncherEntry(context.applicationContext, PackIdentity.LAUNCHER_ALIAS) }
    val quality = remember(context) { EngineQuality(context.applicationContext) }

    Identity()
    Spacer(Modifier.height(20.dp))
    LibrespotAccountSection(account)
    Spacer(Modifier.height(12.dp))
    StreamingQualityRow(quality)
    Spacer(Modifier.height(12.dp))
    HowItPlays(engine)
    Spacer(Modifier.height(12.dp))
    AppListRow(appList)
    Spacer(Modifier.height(12.dp))
    DonateRow()
}

/** What this app is, in one line, for somebody who opened it from their app list. */
@Composable
private fun Identity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_pack),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Spotify for Andamp", style = MaterialTheme.typography.titleLarge)
            Text(
                "Sign in here to play your Spotify music in Andamp.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the music is played with, and which librespot version. */
@Composable
private fun HowItPlays(engine: String?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Good to know", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Playing needs a Spotify Premium account. " +
                        "Built on librespot" + (engine?.let { " $it" } ?: "") + ", an open-source Spotify client.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the page does with the account, as an interface.
 *
 * The engine's verbs in the app, and a stand-in in a JVM test, which has no
 * engine. Everything but [progress] reads or writes the disk, so the page
 * never calls them from the main thread.
 */
internal interface LibrespotAccountActions {
    /** Whether a credential is here; see [LibrespotAccount.signedIn]. */
    fun signedIn(): Boolean

    /** A code to show and the page it goes on, or why there is none; see [LibrespotAccount.begin]. */
    suspend fun begin(): Pairing

    /** Where the wait for [waiting] has got to; see [LibrespotAccount.progress]. */
    fun progress(waiting: Pairing.WaitingForApproval): Pairing

    /** Takes the credential away; true when it is gone. See [LibrespotAccount.forget]. */
    fun forget(): Boolean

    /** Who the credential names, or null when it names nobody. */
    fun username(): String?

    /**
     * Tells every player bound to this pack that the account here has changed.
     *
     * In the app it starts this pack's own service. The default does nothing,
     * for a JVM test.
     */
    fun changed() = Unit
}

/**
 * The account on this phone, through the engine.
 *
 * The directory is found on first use, which is the page's first read, off the
 * main thread.
 */
private class EngineAccount(
    private val context: Context,
) : LibrespotAccountActions {
    private val store by lazy { PackStore.credentials(context) }

    override fun signedIn() = LibrespotAccount.signedIn(store)

    override suspend fun begin() = LibrespotAccount.begin(store)

    override fun progress(waiting: Pairing.WaitingForApproval) = LibrespotAccount.progress(waiting)

    /**
     * Deletes the credential and, when it is gone, tells the pack's service,
     * which lets go of what was playing on the closed session.
     */
    override fun forget() = LibrespotAccount.forget(store).also { if (it) PackService.signedOut(context) }

    override fun username() = LibrespotAccount.username(store)

    override fun changed() = PackService.signedIn(context)
}

/**
 * The streaming quality, as an interface for the reason [LibrespotAccountActions] is one.
 *
 * In the app it is the preference on disk and the engine together; see
 * [nl.mattix.andamp.pack.librespot.pack.PackStore] and
 * [nl.mattix.andamp.pack.librespot.LibrespotPlayback.setStreamingQuality].
 */
internal interface StreamingQualityChoice {
    /** The quality chosen, in kbps; one of [StreamingQuality.choices]. */
    val kbps: Int

    fun choose(kbps: Int)
}

/**
 * The streaming quality, on disk and in the engine: the engine is this
 * process's and hears the change at once, and the disk is what the next
 * process starts from.
 */
private class EngineQuality(
    private val context: Context,
) : StreamingQualityChoice {
    override val kbps: Int get() = PackStore.quality(context)

    override fun choose(kbps: Int) {
        PackStore.setQuality(context, kbps)
        LibrespotPlayback.setStreamingQuality(StreamingQuality.of(kbps))
    }
}

/**
 * Who is signed in, the way in or out, and the wait for a code to be approved.
 *
 * The account is read on [io], never while drawing; "Checking…" is shown
 * meanwhile. It is read again whenever the listener comes back to the app and
 * whenever the sign-in dialog closes, because the approval happens elsewhere
 * and can land after the dialog is gone.
 *
 * Closing the dialog does not stop the engine's wait: a code typed in before
 * Cancel can still be approved after it. An approval then signs the phone in;
 * a failure after the dialog is closed is not shown.
 */
@Composable
internal fun LibrespotAccountSection(
    account: LibrespotAccountActions,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val scope = rememberCoroutineScope()
    // null until the disk has answered
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    // incremented to read the disk again; every change this page makes
    // increments it too
    var look by remember { mutableIntStateOf(0) }
    // what the dialog shows; NotStarted is no dialog
    var pairing by remember { mutableStateOf<Pairing>(Pairing.NotStarted) }
    // the code being waited on, shown or not
    var waitingOn by remember { mutableStateOf<Pairing.WaitingForApproval?>(null) }
    // a code is being asked for, or the credential is going: one at a time
    var busy by remember { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }

    // whether this page is in front; a page behind another app asks nothing
    var showing by remember { mutableStateOf(true) }

    LaunchedEffect(account, look) { signedIn = withContext(io) { account.signedIn() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        showing = true
        look++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { showing = false }
    // a sign-in is told to every player bound to this pack at once
    LaunchedEffect(account, signedIn) { if (signedIn == true) account.changed() }
    // An approval happens in another app, and the credential lands a moment
    // after it. So while this page is in front and nobody is signed in, it
    // reads the disk every WATCH_MS, and stops once somebody is.
    LaunchedEffect(account, showing, signedIn) {
        if (!showing || signedIn != false) return@LaunchedEffect
        while (true) {
            if (withContext(io) { account.signedIn() }) {
                signedIn = true
                // the code has been approved, so the dialog closes itself
                pairing = Pairing.NotStarted
                waitingOn = null
                return@LaunchedEffect
            }
            delay(WATCH_MS)
        }
    }
    LaunchedEffect(account, waitingOn) {
        val started = waitingOn ?: return@LaunchedEffect
        val ended = account.awaitEnd(started)
        waitingOn = null
        if (ended == Pairing.Paired) {
            signedIn = true
            pairing = Pairing.NotStarted
        } else if (pairing == started) {
            pairing = ended
        }
        look++
    }

    val known = signedIn
    if (known == null) {
        // the card's own shape while the disk is being read, so the page does
        // not jump when the answer lands
        ElevatedCard(Modifier.fillMaxWidth()) {
            Text(
                "Checking…",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(20.dp).testTag("pack.librespot.account"),
            )
        }
        return
    }
    AccountControls(
        signedIn = known,
        summary = librespotSummary(account, known, io),
        busy = busy,
        stuck = stuck,
        onSignIn = {
            if (!busy) {
                busy = true
                stuck = false
                scope.launch {
                    // a code that could not be asked for is still an answer,
                    // and the button has to come back either way
                    val begun =
                        runCatching { account.begin() }
                            .getOrElse { Pairing.Failed("could not start signing in") }
                    busy = false
                    pairing = begun
                    // a new code ends the engine's wait on the last one, so
                    // only the new one is watched
                    waitingOn = begun as? Pairing.WaitingForApproval
                }
            }
        },
        onSignOut = {
            if (!busy) {
                busy = true
                stuck = false
                scope.launch {
                    // completed even if the page is left meanwhile
                    withContext(NonCancellable) {
                        // a sign-out that threw did not take, the same as one
                        // that answered no
                        val gone = runCatching { withContext(io) { account.forget() } }.getOrDefault(false)
                        busy = false
                        signedIn = !gone
                        stuck = !gone
                        look++
                    }
                }
            }
        },
    )
    PairingDialog(
        pairing = pairing,
        onClose = {
            pairing = Pairing.NotStarted
            look++
        },
    )
}

/** The account card: who is signed in, what signing in or out means, and the one button. */
@Composable
private fun AccountControls(
    signedIn: Boolean,
    summary: String,
    busy: Boolean,
    stuck: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (signedIn) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint =
                        if (signedIn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        summary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("pack.librespot.account"),
                    )
                    Text(
                        if (signedIn) "This phone can play your Spotify music" else "Andamp skips Spotify tracks until you do",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (signedIn) {
                    "Signing out takes the account off this phone. Spotify tracks stay in your Andamp playlists, " +
                        "marked as signed out, and play again when you sign back in."
                } else {
                    "Signing in gives you a short code to enter on Spotify's website, from any device. " +
                        "Playing needs a Spotify Premium account."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (signedIn) {
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.End).testTag("pack.librespot.signout"),
                ) { Text(if (busy) "Signing out…" else "Sign out") }
                if (stuck) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Could not sign out: the account is still on this phone, and its tracks still play. " +
                            "Try again, or clear this app's storage in Android's settings to remove it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("pack.librespot.stuck"),
                    )
                }
            } else {
                // asking for a code is a round trip to the service, so the
                // button says it is waiting
                Button(
                    onClick = onSignIn,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("pack.librespot.signin"),
                ) { Text(if (busy) "Getting a code…" else "Sign in") }
            }
        }
    }
}

/**
 * The account in a line.
 *
 * The name comes from the credential on disk, read on [io]. Until it is read
 * the line says signed in.
 */
@Composable
internal fun librespotSummary(
    account: LibrespotAccountActions,
    signedIn: Boolean,
    io: CoroutineDispatcher = Dispatchers.IO,
): String {
    if (!signedIn) return "Not signed in"
    // null until the file has been read; blank for a credential that names nobody
    val name by produceState<String?>(null, account) {
        value = withContext(io) { account.username().orEmpty() }
    }
    return when (val said = name) {
        null -> "Signed in"
        "" -> "Signed in on this phone"
        else -> "Signed in as $said"
    }
}

/**
 * Waits for [waiting] to end, and says how.
 *
 * A poll, because the engine answers with a status line; [Pairing.progress]
 * reads it.
 */
private suspend fun LibrespotAccountActions.awaitEnd(waiting: Pairing.WaitingForApproval): Pairing {
    var now: Pairing = waiting
    while (now is Pairing.WaitingForApproval) {
        delay(POLL_MS)
        now = progress(waiting)
    }
    return now
}

/** The code, the page it goes on, and the wait in between; or why it did not work. */
@Composable
private fun PairingDialog(
    pairing: Pairing,
    onClose: () -> Unit,
) {
    val waiting = pairing as? Pairing.WaitingForApproval
    val failed = pairing as? Pairing.Failed
    if (waiting == null && failed == null) return
    val links = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // whether the page has been opened yet; the wait is shown only after that
    var sent by remember(waiting?.code) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (failed != null) "Could not sign in" else "Sign in to Spotify") },
        text = {
            Column {
                if (failed != null) {
                    Text(failed.reason, style = MaterialTheme.typography.bodyMedium)
                } else if (waiting != null) {
                    Text("Open the page and enter this code:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    // the code can be copied by pressing it, from the button
                    // beside it, or on the way out through Open page
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer {
                            Text(
                                waiting.code,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier =
                                    Modifier
                                        .clickable { scope.launch { clipboard.copy(waiting.code) } }
                                        .testTag("pack.librespot.code"),
                            )
                        }
                        IconButton(
                            onClick = { scope.launch { clipboard.copy(waiting.code) } },
                            modifier = Modifier.testTag("pack.librespot.copy"),
                        ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the code") }
                    }
                    // the service's own page stays open once the code is
                    // approved, so the way back is said once the listener has
                    // gone there
                    if (sent) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Spotify stays open after you approve it - come back here and this page signs itself in.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // shown once the listener has gone to the page: the
                    // approval happens where this screen cannot see it
                    if (sent) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Waiting for you to approve it…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("pack.librespot.waiting"),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // the engine polls the service for the token a few
                        // seconds apart, so an approval is not heard at once
                        Text(
                            "This can take ten seconds or so after you approve it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (waiting != null) {
                TextButton(
                    onClick = {
                        scope.launch { clipboard.copy(waiting.code) }
                        sent = true
                        links.openUri(waiting.url)
                    },
                    modifier = Modifier.testTag("pack.librespot.open"),
                ) { Text("Open page") }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
        dismissButton = {
            if (waiting != null) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("pack.librespot.cancel")) { Text("Cancel") }
            }
        },
    )
}

/** Puts the code on the clipboard. Android shows its own confirmation from 13 on. */
private suspend fun Clipboard.copy(code: String) = setClipEntry(ClipEntry(ClipData.newPlainText("Spotify code", code)))

/** How often the engine's pairing status is read. */
private const val POLL_MS = 1_000L

/** How often a page in front looks for a credential that is not there yet. */
private const val WATCH_MS = 1_500L
