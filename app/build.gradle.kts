// SPDX-License-Identifier: GPL-3.0-or-later

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.roborazzi)
    // the pack draws its own settings screen, in Compose
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.pack.librespot"
    // r27 or later, for 16 KB page alignment: every LOAD segment of the built
    // library aligns at 0x4000. AGP resolves this to a directory, which the
    // cargo task hands to cargo-ndk, so the version is pinned only here
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "nl.mattix.andamp.pack.librespot"
        targetSdk = 36
        versionName = "0.5.2" // x-release-please-version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // arm64 for phones, x86_64 so the emulator stays usable
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is the version in the descriptor the player
        // reads
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric draws the settings screen, which needs the merged
            // manifest and the resources
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // the transport rules, the backend contract and the reconnect schedule
    implementation(libs.andamp.playback)
    // the contract both sides read: the AIDL and the types that cross it
    implementation(libs.andamp.source.api)
    // the pack-side plumbing shared by every source: the audio crossing, the
    // state relay and the paging behind a library question
    implementation(libs.andamp.source.common)
    implementation(libs.kotlinx.coroutines.core)
    // the account screen: one page, Material 3
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // the icons on the account screen
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    // LifecycleEventEffect: the page reads the account again whenever it
    // comes back into view
    implementation(libs.lifecycle.runtime.compose)
    detektPlugins(libs.detekt.compose.rules)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(libs.andamp.playback))
    // Robolectric for org.json, which is the platform's parser, and to draw
    // the settings screen on the JVM
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // the settings screen is driven through the Compose test rule
    testImplementation(libs.compose.ui.test.junit4)
    // the settings screen, rendered as goldens
    testImplementation(libs.roborazzi)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

/**
 * Which Android ABI maps to which Rust target.
 *
 * cargo-ndk takes the ABI name and puts its output under the target triple, so
 * both halves are needed to find the library again.
 */
val abiTargets =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "x86_64" to "x86_64-linux-android",
    )

/**
 * Builds librespot and the JNI glue, once per ABI, into a directory the
 * Android build takes as generated native libraries.
 *
 * A plain task that launches cargo. Each way it can fail (no submodule, no
 * cargo-ndk, no Android target) names the command that fixes it, and cargo's
 * and git's own output reaches the console.
 *
 * It always runs, and cargo's own fingerprinting decides what to rebuild: the
 * library depends on the crate, its lockfile, the patched submodule and the
 * flags below.
 *
 * A task class in a build script cannot reach the script around it, so
 * everything it needs arrives as a property.
 */
abstract class CargoBuild : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    /** The crate cargo builds, and where every cargo and rustup command runs. */
    @get:Internal
    abstract val crate: DirectoryProperty

    /** The submodule, patched before cargo sees it. */
    @get:Internal
    abstract val librespot: DirectoryProperty

    @get:Internal
    abstract val patches: DirectoryProperty

    /** The NDK AGP resolved from `ndkVersion`, which cargo-ndk has to be told. */
    @get:Internal
    abstract val ndk: DirectoryProperty

    /** ABI to Rust target triple; see `abiTargets`. */
    @get:Input
    abstract val targets: MapProperty<String, String>

    /** Where rustup puts cargo, and where two of the path remaps start. */
    @get:Input
    abstract val home: Property<String>

    /** The checkout, which is taken out of every path the library records. */
    @get:Input
    abstract val repo: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val userHome = home.get()
        // cargo by the path rustup puts it at, when it is there: the executable
        // is looked up on the Gradle daemon's own PATH, which may not include
        // ~/.cargo/bin
        val tools = File(userHome, ".cargo/bin")
        val cargo = File(tools, "cargo").takeIf(File::canExecute)?.absolutePath ?: "cargo"
        val rustup = File(tools, "rustup").takeIf(File::canExecute)?.absolutePath ?: "rustup"
        val path = tools.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty()
        val crateDir = crate.get().asFile
        val tree = librespot.get().asFile

        if (!File(tree, "Cargo.toml").isFile) {
            throw GradleException(
                "app/third_party/librespot is empty. Run: git submodule update --init --recursive",
            )
        }
        // checked with the same cargo executable the build then runs
        if (probe(path, crateDir, cargo, "ndk", "--version") != 0) {
            throw GradleException("cargo-ndk is not installed. Run: cargo install cargo-ndk")
        }
        requireTargets(path, crateDir, rustup)
        applyPatches(path, tree)

        // the crate's own tests, on the host target; nothing else runs them.
        // --locked here and below, so what is built is what Cargo.lock says
        exec.exec {
            workingDir = crateDir
            commandLine(cargo, "test", "--locked")
            environment("PATH", path)
        }

        val out = outputDir.get().asFile
        files.delete { delete(out) }
        val ndkHome = ndk.get().asFile.absolutePath
        // CARGO_ENCODED_RUSTFLAGS separates flags with the unit separator, so a
        // checkout path with a space in it works
        val flags = remaps(userHome).joinToString("\u001f")
        targets.get().forEach { (abi, triple) ->
            // cargo's output streams to the console, so a compile error or a
            // long release build is visible as it happens
            exec.exec {
                workingDir = crateDir
                commandLine(cargo, "ndk", "-t", abi, "--platform", "26", "build", "--release", "--locked")
                environment("PATH", path)
                environment("ANDROID_NDK_HOME", ndkHome)
                environment("CARGO_ENCODED_RUSTFLAGS", flags)
            }
            files.copy {
                from(File(crateDir, "target/$triple/release/libandamp_librespot.so"))
                into(File(out, abi))
            }
        }
    }

    /**
     * Takes the build machine's paths out of the library.
     *
     * Every panic location in the library is a source path: the builder's home
     * directory, registry, toolchain and checkout. `--remap-path-prefix`
     * rewrites them.
     */
    private fun remaps(userHome: String): List<String> =
        listOf(
            "--remap-path-prefix=${repo.get()}=.",
            "--remap-path-prefix=$userHome/.cargo=/cargo",
            "--remap-path-prefix=$userHome/.rustup=/rustup",
        )

    /** Runs [command] in [dir] for its exit code alone: -1 when it could not be started. */
    private fun probe(
        path: String,
        dir: File,
        vararg command: String,
    ): Int =
        runCatching {
            exec
                .exec {
                    workingDir = dir
                    commandLine(command.toList())
                    environment("PATH", path)
                    isIgnoreExitValue = true
                    standardOutput = OutputStream.nullOutputStream()
                    errorOutput = OutputStream.nullOutputStream()
                }.exitValue
        }.getOrDefault(-1)

    /**
     * Fails with the command that fixes it when rustup has no target for an ABI
     * we build. Asked in [dir], so it answers for the toolchain cargo will use.
     */
    private fun requireTargets(
        path: String,
        dir: File,
        rustup: String,
    ) {
        val listed = ByteArrayOutputStream()
        val ran =
            runCatching {
                exec
                    .exec {
                        workingDir = dir
                        commandLine(rustup, "target", "list", "--installed")
                        environment("PATH", path)
                        isIgnoreExitValue = true
                        standardOutput = listed
                        errorOutput = OutputStream.nullOutputStream()
                    }.exitValue
            }.getOrDefault(-1)
        // a Rust installed without rustup cannot be asked; cargo then reports
        // a missing target itself
        if (ran != 0) return
        val installed =
            listed
                .toString()
                .lines()
                .map(String::trim)
                .toSet()
        val missing = targets.get().values.filterNot(installed::contains)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Rust cannot build for ${missing.joinToString()}. Run: rustup target add ${missing.joinToString(" ")}",
            )
        }
    }

    /**
     * Applies the patches to the pinned submodule, skipping any already in the
     * tree.
     *
     * The submodule's working tree is upstream's, so patches live beside it and
     * are applied by the build; see `app/third_party/PATCHES.md`. To start
     * again from upstream's tree, run
     * `git -C app/third_party/librespot checkout -- .` before the build. A
     * plain `git submodule update` leaves the patched files as they are, and
     * `.gitmodules` says `ignore = dirty`, so `git status` does not show them.
     */
    private fun applyPatches(
        path: String,
        tree: File,
    ) {
        val found =
            patches
                .get()
                .asFile
                .listFiles()
                .orEmpty()
                .filter { it.extension == "patch" }
                .sorted()
        found.forEach { patch ->
            val already = probe(path, tree, "git", "apply", "--reverse", "--check", patch.absolutePath) == 0
            if (already) return@forEach
            // git's output reaches the console: a patch that does not apply
            // says where
            exec.exec {
                workingDir = tree
                commandLine("git", "apply", patch.absolutePath)
                environment("PATH", path)
            }
            logger.lifecycle("applied ${patch.name} to app/third_party/librespot")
        }
    }
}

val cargoBuild =
    tasks.register<CargoBuild>("cargoBuild") {
        crate.set(layout.projectDirectory.dir("rust"))
        librespot.set(layout.projectDirectory.dir("third_party/librespot"))
        patches.set(layout.projectDirectory.dir("third_party/patches"))
        ndk.set(androidComponents.sdkComponents.ndkDirectory)
        targets.set(abiTargets)
        home.set(System.getProperty("user.home"))
        repo.set(rootDir.absolutePath)
        // under build/, so a clean removes it like any other output
        outputDir.set(layout.buildDirectory.dir("generated/jniLibs/cargo"))
        outputs.upToDateWhen { false }
    }

// handed to AGP as a generated source directory, which makes every task that
// reads native libraries depend on the cargo build
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoBuild::outputDir)
    }
}
