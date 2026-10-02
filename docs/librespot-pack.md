# Librespot in Andamp

How the Librespot pack is built, and why. What any pack answers and how the player drives
it is in
[source-packs.md](https://github.com/mattijsf/andamp/blob/main/docs/source-packs.md) and the
Sources section of the player's
[ENGINEERING.md](https://github.com/mattijsf/andamp/blob/main/ENGINEERING.md). The licenses
are in `NOTICE.md`, and the local changes to librespot are in `app/third_party/PATCHES.md`.
This pack's music comes from a commercial streaming service, called *the service* below.

This repository builds one application (`nl.mattix.andamp.pack.librespot`, label
"Librespot", rows under the `spotify:` scheme). It is distributed as an APK from its own
page, <https://mattix.nl/andamp/extensions/librespot>, and is not on Google Play. The player
carries none of it.

## Why librespot

librespot is an open-source client for the service's own protocol. Embedding it means the
service's audio is decoded on the phone and handed to the player as samples, so the
equalizer, the balance, the effect rack, AVS and Milkdrop work on it as they do on a file.
The alternatives that authenticate with a developer-dashboard client id (the Web API, App
Remote, the Web Playback SDK) or drive the service's own app through its media session
yield no samples. Where each Winamp readout comes from:

| Winamp element | Where it comes from |
| --- | --- |
| Title, artist, album, cover | The service's metadata for the row; the cover address from the account's `image-url` template |
| Time elapsed, posbar | the engine's own position, ticking every 250 ms (`position_update_interval`) |
| kbps | the file the engine picked for the chosen quality (96, 160, 256 or 320) |
| kHz, stereo lamp | constant 44.1 kHz stereo, which is what librespot decodes to |
| Volume, equalizer, rack, visualizers | the player's own chain, over the samples the pack hands over |

The library comes off the same session, with no developer account involved.

Two things can stop an account from playing:

- **Audio keys.** Since late 2025 librespot-family clients fail to get audio keys for many
  accounts created after roughly late 2024
  ([librespot#1649](https://github.com/librespot-org/librespot/issues/1649)), with no fix
  upstream. The failure arrives as a track the engine calls unavailable, one track at a time,
  so the pack counts them (`Playability`).
- **Premium.** librespot is Premium-only by its maintainers' policy. A non-Premium account is
  refused with a message (patch 0003) and cannot play.

## How it is built

librespot is a pinned submodule, patched by the build. It sits at
`app/third_party/librespot`, pinned to a `dev` commit because the device-pair login flow is
not in any release. Each local change is a patch file beside it, with the condition for
dropping it; see `PATCHES.md`. `Sink` is a public trait, so the audio sink lives in this
repository's own crate (`app/rust`) against stock librespot.

Rust speaks the protocol and hands back samples; everything else is Kotlin. The crate
compiles librespot's core, playback, metadata and oauth, with no audio backend and no
discovery, so cpal, oboe and the C++ runtime are left out and the library needs only
`liblog`, `libdl`, `libm` and `libc`. It compiles no connect module: the queue and its rules
are Winamp's, and the pack does not appear as a device in the service's apps. The queue,
Winamp's transport rules, uri parsing, the credential and the event-to-state mapping are
Kotlin.

Winamp owns the transport, because librespot's player has no queue. It holds one track: no
index, no shuffle, no repeat, no next. `LibrespotBackend` applies `TransportRules` from the
SDK's `playback` and tells the engine the difference between the state before a rule and
after it, not the verb that was called. So next, previous, a row picked in the playlist and
a track ending need no handling of their own, and the shared `PlaybackBackendContractTest`
runs against the backend on the JVM with a fake engine.

The engine speaks in lines of text. Verbs go down as JNI calls; events come up as lines
(`EngineLine`) that a pure parser reads, and a line nobody recognizes is ignored. There is no
callback into the JVM, so nothing attaches a thread or holds a global reference. Library
answers cross the same way: Rust asks and prints the service's own JSON, and Kotlin reads it
(`LibrespotRows`, `LibrespotPlaylists`, `LibrespotAlbums`, `LibrespotArtist`), so every
parser is tested against recorded answers on the JVM.

The samples take the path every pack's do. The sink converts the decoder's f64 to 16-bit
once and hands packets to Kotlin over a bounded channel; `LibrespotPcm` serves them as a
`PcmProvider`; `PackAudioOut` from `source-common` writes them into the pipe `openAudio()`
answers; the player renders them. The engine pushes back when nobody reads (a full buffer
blocks its decoder), so whoever opens an engine drains it; otherwise playback loads, reports
position zero and never moves. The engine's own mixer stays at full scale, because the
player that renders the samples applies the volume.

librespot calls `exit(1)` in several places. Two of them are handled:

1. Upstream's catalog check calls `exit(1)` for a non-Premium account, from a background
   thread. Patch 0003 makes it return an answer and invalidate the session; `lib.rs` reads
   the account type after every connect and says `failed not premium`, and a pairing ends
   with a message and removes the credential it had just stored.
2. The player calls `exit(1)` when the sink's `stop()` returns an error, so `Sink::stop()`
   in `lib.rs` returns `Ok(())` unconditionally. Errors from `start()` and `write()` pause
   playback.

The other `exit(1)` calls in the pinned `player.rs` are unchanged. The release profile uses
`panic = "abort"`, so a panic in the library ends the process; nothing catches one at the
JNI boundary.

## Signing in

Playback authenticates through librespot's device-pair flow, which the service makes
available only for its desktop, TV and wearable client ids. The session and the device-pair
login both use the desktop client id. On Android, librespot would request its client token
under the Android client id with Android platform data; patch 0001 makes that request use
the desktop client id and desktop platform data, so it matches the session's client id.
When the two differ, `login5` refuses the login with an error that does not name the cause;
`PATCHES.md` has both failure modes.

`nativeBeginPairing` hands back the code and the page to enter it on, and the approval is
awaited on a task that ends in a stored credential. Kotlin reads the status line: `Pairing`
treats a status it does not recognize as still waiting, so a new status upstream does not
become an error on screen.

The credential is a reusable login to the account, tied to the client id and not to the
install. It is kept in the pack's no-backup directory (`PackStore`), readable by the app
alone, with backup off in the manifest. A restart authenticates from the stored credential
without asking. The service can revoke a stored credential, and the listener then signs in
again on the pack's screen.

## The library

Liked Songs is the library. librespot resolves `spotify:user:<id>:collection` to track
uris, and a batched metadata request names them, covers included. Saved albums and followed
artists sit behind collection endpoints librespot does not wrap. So the ARTISTS and ALBUMS
shelves are grouping over Liked Songs, and an album shows the tracks of it that were liked.

- **LISTS** is the listener's rootlist. `items` and `metaItems` line up by position, which
  nothing in the answer says; `LibrespotPlaylists` pairs them. Folders arrive as
  `start-group` and `end-group` markers and are dropped.
- **FIND** reaches past the listener's own library. The service has no artist search this
  engine can make, so the artists are the ones behind a track search
  (`spotify:search:<terms>`), ordered by how often they turn up. An artist's answer names
  their records by gid alone, with no title or year, so a discography costs a second batched
  ask (`LibrespotArtist`). Gids become uris in Rust, because the conversion is librespot's.
- **Search** inside the shelves is a flat, playable track list.

A failed ask is not kept and is not shown as an empty shelf: it throws `SourceUnreachable`,
which the window words as a source it could not reach.

## When it goes wrong

- **An invalid session is replaced.** librespot does not reconnect an invalidated session.
  Opening asks whether the session held is still usable and makes a fresh one when it is
  not; a load with no session opens one, and waits for a connect already in flight.
  `nativeBreakSession` invalidates a session on purpose, and `LibrespotHealsTest` asserts
  the library comes back.
- **A dropped connection is redialled.** `TrackRedial` puts the row back on at the position
  last heard, on the SDK's `StreamReconnect` schedule, and tries nothing while there is no
  network.
- **A run of refusals is counted.** `Playability` counts refusals that are not the
  connection: three in a row reopens the session and retries, and a second run raises
  `SourceCannotPlay`. The engine cannot tell a refused key from a missing track, so the
  count does not either.
- **A key that times out fails the track** (patch 0002), after three attempts. Upstream
  plays on undecrypted, which ends in a decoder error.

## Working on it

The toolchain (`cargo-ndk`, the Rust targets) is in the README. The JVM tests, the goldens
and `LibrespotLoadTest` on a device need no account. The device tests that do need one ask
`DeviceCredential` for it and skip with a message otherwise: `LibrespotSessionTest`,
`LibrespotPlaybackTest`, `LibrespotHealsTest` and `LibrespotBrowseTest`.

```
adb push credentials.json /data/local/tmp/
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.librespotCredentials=/data/local/tmp/credentials.json
```

`LibrespotPairingTest` mints a credential, and runs only when asked, because every run
creates a new device authorization for the desktop client id:

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pairNow=true
```

The code appears in `adb logcat -s AndAmpPairing`. Gradle uninstalls the test package when
the run ends, taking its storage with it, so the credential has to be copied out by hand
before then.

Two environment notes. The Gradle daemon does not inherit a shell's environment, so the
cargo task hands `cargo-ndk` the NDK path and the cargo directory itself. And an emulator
without working DNS fails a pairing with `Failed to obtain device code (Request failed)`;
starting it with `-dns-server 8.8.8.8,1.1.1.1` fixes that.
