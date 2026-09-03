plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

/**
 * `loopky` — the headless client (#54).
 *
 * A plain JVM module rather than another KMP target: it consumes `:shared`'s `jvm()` variant and
 * has no platform half of its own. It reaches for repositories directly and never touches
 * `presentation/`, which is what the repos-own-the-logic rule (no use-case layer) buys here.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.core)
    // QR encoding for `loopky login`. Already in the catalog for the tablet sign-in panel; core
    // only, so it brings no camera/scanner stack.
    implementation(libs.zxing.core)
    testImplementation(libs.kotlin.test)
}

application {
    applicationName = "loopky"
    mainClass.set("com.github.jvsena42.loopky.cli.MainKt")
    // ImageIO's raster and JPEG paths need no display, but AWT tries to open one on first use
    // unless told otherwise — an exception rather than a fallback on a box with no X server.
    // `JvmMediaProcessor` degrades either way; this stops it having to.
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

/**
 * Quiet the pubky SDK's own `tracing` output unless the user asks for it.
 *
 * `libpubkycore` installs a subscriber that, with no `RUST_LOG` set, defaults to
 * `pubky=info,pubkycore=debug` and writes ANSI-coloured lines to stderr — four of them before a
 * `login` has printed anything. It is genuinely useful when a relay or a TLS handshake is
 * misbehaving and pure noise the rest of the time.
 *
 * It has to be an environment variable, and it has to be set before the library loads: the filter
 * is read once, from the process environment, by a `Lazy` the first network call triggers. A JVM
 * cannot set its own environment, so the start script is the only place this can happen — which is
 * also why it is a default rather than an override: `RUST_LOG=debug loopky …` still works, and is
 * the first thing to try when a homeserver call fails for no visible reason.
 */
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(
            unixScript.readText().replace(
                "\nAPP_HOME=",
                "\nexport RUST_LOG=\"\${RUST_LOG:-warn}\"\n\nAPP_HOME=",
            ),
        )
        windowsScript.writeText(
            windowsScript.readText().replace(
                "\r\nset APP_HOME=",
                "\r\nif not defined RUST_LOG set RUST_LOG=warn\r\nset APP_HOME=",
            ),
        )
    }
}
