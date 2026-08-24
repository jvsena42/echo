rootProject.name = "Loopky"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        // The JVM half of the `rustls-platform-verifier` Rust crate, vendored under
        // `shared/libs/maven` in Maven layout.
        //
        // Not on Maven Central: the crate ships this AAR inside its own source tree
        // (`rustls-platform-verifier-android`), expecting each app to publish it locally. Copied
        // into the repo rather than resolved from `~/.cargo/registry` so CI and a fresh clone
        // build the same artifact — the registry path carries a checksum hash that changes with
        // the crate version and does not exist until someone has run `cargo build`.
        //
        // Scoped to the `rustls` group so it can never answer for anything else.
        maven {
            url = uri("${rootDir}/shared/libs/maven")
            content { includeGroup("rustls") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":shared")