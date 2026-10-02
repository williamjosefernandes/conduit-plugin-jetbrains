import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Version catalogs are not available in the settings plugins block; keep in sync with docs/ARCHITECTURE.md.
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "conduit"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

// Modules are added in the phase that first gives them content (see docs/PLAN.md).
include(":core")
include(":ui")
include(":plugin")
