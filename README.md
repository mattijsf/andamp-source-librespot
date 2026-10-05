# Andamp Librespot source

A music source for [Andamp](https://github.com/mattijsf/andamp), built on
[librespot](https://github.com/librespot-org/librespot), an open-source client library for
a music streaming service. It signs in with your account and plays your library there. A
Premium account is required.

It is a separate app. Once installed, Andamp lists it under Preferences > Music sources
and in the Media Library. This app's one screen is where you sign in. Audio is decoded by
librespot here and played by Andamp through its own equalizer, effects and visualizer.

[docs/librespot-pack.md](docs/librespot-pack.md) covers the design and sign-in.

## Get it

The APK is at [andamp.nl/extensions/librespot](https://andamp.nl/extensions/librespot).
It is not on Google Play. Andamp notifies you when a newer version is available.

## Build

JDK 17, the Android SDK (compile SDK 36) and the NDK version pinned in
`app/build.gradle.kts`. Gradle finds the SDK through `ANDROID_HOME` or through `sdk.dir`
in a `local.properties` file in the project root. `assembleDebug` and `installDebug` also
need a Rust toolchain on the host, 1.85 or later: the build runs `cargo test --locked` and
then `cargo ndk` for each ABI. `gate` does not run cargo.

```bash
git clone https://github.com/mattijsf/andamp-source-librespot.git
cd andamp-source-librespot
git submodule update --init --recursive
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android

./gradlew assembleDebug      # runs cargo: librespot and the JNI glue, per ABI
./gradlew installDebug       # onto a connected phone, beside Andamp
./gradlew gate               # format, detekt, unit tests and goldens; no Rust needed
```

`lefthook install` sets up the pre-push hook, which runs `gate`.

The app is built against Andamp's source SDK, `nl.mattix.andamp:source-api`,
`nl.mattix.andamp:source-common` and `nl.mattix.andamp:playback`, from Maven Central. To
test an unreleased SDK change, run `./gradlew publishToMavenLocal` in a checkout of the
player; the local artifacts take precedence.

The build patches the librespot submodule before compiling it, so the submodule's working
tree differs from the pinned commit. See [app/third_party/PATCHES.md](app/third_party/PATCHES.md).

A release build is signed when a `keystore.properties` file in the project root names the
keystore (`storeFile`, relative to the project root) and the key (`keyAlias`). The
passwords are read from the environment variables `SOURCE_STORE_PASSWORD` and
`SOURCE_KEY_PASSWORD`, or, when those are unset, from `storePassword` and `keyPassword` in
the same file. Without the file a release build is unsigned. With the file
but without a password from either place, the release build fails at packaging.

## License

Copyright (C) 2026 Mattix (Mattijs Fuijkschot).

GNU General Public License, version 3 or later; see [LICENSE](LICENSE). librespot is MIT;
that and the rest of the third-party attribution is in [NOTICE.md](NOTICE.md), and the Rust
crates linked with it are listed in [app/rust/THIRD-PARTY.md](app/rust/THIRD-PARTY.md).
