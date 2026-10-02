// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
}

// Everything a push has to pass, as one task: the formatting, detekt, the unit
// tests and the goldens of the settings screen. The push hook and CI both run
// it. No APK is assembled here.
tasks.register("gate") {
    group = "verification"
    description = "Format, detekt and the unit tests and the goldens: what a push has to pass."
    dependsOn("spotlessCheck", ":app:detekt", ":app:testDebugUnitTest", ":app:verifyRoborazziDebug")
}

/**
 * The release signing settings, read from `keystore.properties` in the project
 * root. The file is not tracked.
 *
 * It names the keystore (`storeFile`, relative to the project root) and the
 * alias inside it (`keyAlias`). Without the file, or without `storeFile` in
 * it, a release build is unsigned. The passwords come from the environment
 * variables `SOURCE_STORE_PASSWORD` and `SOURCE_KEY_PASSWORD`, or, when those
 * are unset or blank, from `storePassword` and `keyPassword` in the file. With
 * a keystore but no password from either, the release build fails at packaging.
 *
 * ```
 * SOURCE_STORE_PASSWORD=... SOURCE_KEY_PASSWORD=... ./gradlew :app:assembleRelease
 * ```
 */
val signing =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

/** The environment variable when it is set and not blank, else the property from the file. */
fun secret(
    variable: String,
    property: String,
): String? = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: signing.getProperty(property)

val store: String? = signing.getProperty("storeFile")

/** A semver name as the version code Android compares: 1.2.3 is 10203. */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

subprojects {
    val module = this
    plugins.withId("com.android.application") {
        module.extensions.configure<ApplicationExtension>("android") {
            compileSdk = 36
            buildToolsVersion = "36.1.0"
            defaultConfig.minSdk = 26
            compileOptions.sourceCompatibility = JavaVersion.VERSION_17
            compileOptions.targetCompatibility = JavaVersion.VERSION_17
            signingConfigs.create("source") {
                if (store != null) {
                    storeFile = rootProject.file(store)
                    keyAlias = signing.getProperty("keyAlias")
                    storePassword = secret("SOURCE_STORE_PASSWORD", "storePassword")
                    keyPassword = secret("SOURCE_KEY_PASSWORD", "keyPassword")
                }
            }
            // unsigned where there is no keystore, so a clone can build a
            // release
            buildTypes.named("release") {
                signingConfig = signingConfigs.getByName("source").takeIf { store != null }
            }
        }
        module.extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
            finalizeDsl { android ->
                android.defaultConfig.versionCode = versionCodeOf(android.defaultConfig.versionName.orEmpty())
            }
        }
    }
    // every source set, as one directory: detekt's default is main and test
    // only, which would leave androidTest unanalyzed
    plugins.withId("io.gitlab.arturbosch.detekt") {
        module.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("detekt.yml"))
            source.setFrom(module.files("src"))
        }
    }
}
