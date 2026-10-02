// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The SDK from the local Maven repository, when a checkout of the
        // player published it there with `./gradlew publishToMavenLocal`: how
        // an unreleased SDK change is tried against this source. Only that
        // group is looked for here.
        mavenLocal {
            content { includeGroup("nl.mattix.andamp") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "andamp-source-librespot"
include(":app")
