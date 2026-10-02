# Local patches to librespot

librespot is a client for a commercial streaming service's own protocol, called *the service*
below.

The submodule beside this file is upstream's working tree and carries no local
commits. Every local change is a patch file in `patches/`, applied by the build
before cargo runs.

Each entry says what the patch does, why it is needed, and the condition for
dropping it.

Applying is idempotent: the build checks whether a patch is already in the tree
before applying it. To start again from upstream's tree, after editing a patch
or before moving the pin, restore the working tree and let the next build
re-apply:

```
git -C app/third_party/librespot checkout -- .
```

A plain `git submodule update` does not do that. It leaves the patched files
as they are, and refuses to move to a new pin that touches them; `.gitmodules`
says `ignore = dirty`, so `git status` does not mention either.

## 0001 — request the client token under the desktop client id on Android

*Added 3 September 2026, against `a1b66d3c` (dev, 1 September 2026).*

`SpClient::client_token()` chooses the client id and the platform data for the
client-token request from the compile-time OS. On Android it uses the service's
Android client id with Android platform data, ignoring the id the session was
configured with. The device-pair login flow is available only for the service's
desktop, TV and wearable client ids, so this build's session and its device-pair
login use the desktop client id, and the credential is minted under it. Without
the patch, the client token and the session use different client ids and
`login5` refuses the login.

The table shows what `login5` answers without the patch, for each session client
id. Both were measured on 3 September 2026, and neither error names the cause:

| Session client id | What `login5` answers |
| --- | --- |
| desktop (ours, credential matches) | `FaultyRequest(BAD_REQUEST)` |
| Android (upstream default, credential does not match) | `FaultyRequest(INVALID_CREDENTIALS)` |

Asking for the token under the desktop id with the Android platform data left
in place fails too: the token endpoint answers `400`, because the id and the
platform block have to agree.

The patch makes the client-token request treat Android as Linux, so it uses the
desktop client id and desktop platform data, which match the session's client id
and the credential. It changes one line and nothing on any other platform.

**Drop it when** upstream lets a caller choose the client id used for the client
token, or when the service enables the device-pair flow for the Android client
id. [librespot#1745](https://github.com/librespot-org/librespot/pull/1745)
added the flow and documents which ids it accepts.

## 0002 — ask again for the audio key, and fail the track without one

*Added 3 September 2026, against `a1b66d3c`.*

Two changes.

**The key request is retried.** Upstream's `AudioKeyManager::request` sends one
packet and waits 1.5 seconds, so one slow round trip fails the track. With the
patch it asks up to three times.

**A track with no key fails.** Upstream tries the file without a key, because
not every file is encrypted, and lets the decoder fail on the ones that are.
The decoder fails with `Symphonia Decoder Error: end of stream`. Measured on
3 September 2026, a key timeout looks like this in the log:

```
E librespot_core::audio..: Audio key response timeout
W librespot_playback::p..: Unable to load key, continuing without decryption
E librespot_playback::p..: Unable to read audio file: Symphonia Decoder Error: end of stream
```

A caller cannot tell that from a corrupt file. An account whose audio keys are
refused fails the same way, and this pack counts those failures (`Playability`).
With the patch the player abandons the load and logs the key error, and the
track is reported as unavailable, which is the event the pack counts.

**Drop it when** upstream carries the same change.
[librespot#1748](https://github.com/librespot-org/librespot/pull/1748) is the
open pull request that does both.

**Cost:** an unencrypted file, if one exists, fails with the patch where
upstream would play it.

## 0003 — report a non-Premium account instead of exiting

*Added 11 September 2026, against `a1b66d3c`.*

`Session::check_catalogue` reads the account type from the product-info packet
the service sends straight after the welcome, and for anything but Premium it calls
`exit(1)`, under a TODO that says it should log out instead. On Android that
ends the app's process from a background thread with nothing on screen, and a
Free account reaches it by connecting, which browsing the library does.

With the patch the check returns whether the account is supported. Its callers
keep the attributes the service sent, so the holder of the session can read
`type`, and they invalidate the session, so nothing plays or asks on it
afterwards. `app/rust/src/lib.rs` reads the attribute after every connect: the
engine discards the session and says `failed not premium`, and a pairing ends
with a `pairing failed: ` status saying the account is not Premium and removes
the credential it had just stored.

**Drop it when** upstream resolves its TODO and logs out instead of exiting.
