# Third-party notices

What this source is built on, and under which licenses.

## This source: GPL-3.0-or-later

Its own code is under the GNU GPL, version 3 or later (see `LICENSE`).

## librespot: MIT

<https://github.com/librespot-org/librespot>

An open-source client for a commercial streaming service's protocol. It is compiled
into this source's APK. `docs/librespot-pack.md` describes the design.

librespot's license, from `app/third_party/librespot/LICENSE`:

```
The MIT License (MIT)

Copyright (c) 2015 Paul Lietar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Rust crates linked with librespot

librespot brings in 223 crates that are linked into the library. `app/rust/THIRD-PARTY.md`
lists each one with its version, declared license and repository. Most are MIT,
Apache-2.0 or a choice of the two. The others:

- **symphonia** and its seven codec and format crates are **MPL-2.0**, a file-level
  copyleft. Linking is allowed. The source of those files must be available, and they
  are used unmodified from their upstream crates.
- **priority-queue** is dual licensed, **LGPL-3.0-or-later OR MPL-2.0**. This project
  uses it under **MPL-2.0**, which, as for symphonia, requires the source of its files to
  be available.
- The ICU4X crates (`icu_*`, `zerovec`, `yoke` and related) are **Unicode-3.0**.
- **webpki-roots** is **CDLA-Permissive-2.0**; it is Mozilla's root certificate list as
  data.
- **ring** is **Apache-2.0 AND ISC**; **rustls-webpki** and **untrusted** are **ISC**;
  **webpki** declares no SPDX license and ships an ISC-style `LICENSE` file.
- **subtle** is **BSD-3-Clause**, and **encoding_rs** is
  **(Apache-2.0 OR MIT) AND BSD-3-Clause**.
- **foldhash** is **Zlib**.

MPL-2.0 is the only copyleft license that applies. No crate is GPL or AGPL, and none is
LGPL-only.

The current build:

| | |
|---|---|
| Source | `app/third_party/librespot`, git submodule pinned to `a1b66d3c` on `dev` (1 September 2026) |
| Why a dev commit | the device-pair login flow is not in any release; v0.8.0 predates it |
| Build | `app/build.gradle.kts` runs `cargo ndk` per ABI, with no audio backend and no discovery feature, so cpal, oboe and the C++ runtime are not included |
| Toolchain | NDK 28.2.13676358, `--platform 26`, Rust targets `aarch64-linux-android` and `x86_64-linux-android` |
| Shipped | `libandamp_librespot.so` in this source's APK (`nl.mattix.andamp.pack.librespot`), one per ABI, about 5.7 MB stripped on arm64. It needs only liblog, libdl, libm and libc. The player carries none of it. |
| Local patches | three, recorded in `app/third_party/PATCHES.md` |

`app/rust/THIRD-PARTY.md` is made from the output of this command, run in `app/rust`:

```
cargo tree --locked --target aarch64-linux-android -e normal,no-proc-macro \
  --prefix none --format '{p}|{l}|{r}' | sed 's/ (\*)$//' | sort -u
```

Regenerate it and review the licenses when `Cargo.lock` or the librespot pin changes.

## Libraries it links against

These come from Maven and ship inside the APK. All are permissive and ask only for
attribution, which this file gives.

| | | |
|---|---|---|
| Andamp source SDK | Apache-2.0 | <https://github.com/mattijsf/andamp> |
| AndroidX and Jetpack Compose | Apache-2.0 | <https://github.com/androidx/androidx> |
| Kotlin and kotlinx.coroutines | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
