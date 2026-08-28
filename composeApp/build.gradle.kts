import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

/**
 * The Pubky Nexus indexer, per build type. Staging and production index separate networks, so a
 * release build reading staging would show trending tags and search results that no production
 * user ever published (#42).
 */
val NEXUS_STAGING_URL = "https://nexus.staging.pubky.app"
val NEXUS_PRODUCTION_URL = "https://nexus.pubky.app"

/**
 * Which Homegate issues signup tokens, and therefore which homeserver those tokens are good for.
 * The two travel together as [PubkyEnvironment]; only the name is threaded through BuildConfig.
 * A token is single-use, so spending one minted on the wrong environment loses it for good.
 */
val PUBKY_ENV_STAGING = "Staging"
val PUBKY_ENV_PRODUCTION = "Production"

/**
 * Must stay byte-for-byte identical to `OBFUSCATION_SALT` in `UnsplashKeyObfuscation.kt`, which
 * holds the inverse. Written twice because a Gradle script cannot import from `commonMain`;
 * `UnsplashKeyObfuscationTest` is what catches the two drifting apart.
 *
 * Not a secret. It ships in the same APK as the thing it scrambles.
 */
val UNSPLASH_OBFUSCATION_SALT = "loopky.unsplash.v1".toByteArray()

/**
 * XOR against [UNSPLASH_OBFUSCATION_SALT], then Base64 — just enough that the key is not a literal
 * in the dex. Blank stays blank, so `UnsplashClient.hasFallbackKey` keeps meaning what it says on a
 * build with no key configured.
 */
fun obfuscateUnsplashKey(key: String): String {
    if (key.isEmpty()) return ""
    val raw = key.toByteArray()
    val salted = ByteArray(raw.size) { i ->
        (raw[i].toInt() xor UNSPLASH_OBFUSCATION_SALT[i % UNSPLASH_OBFUSCATION_SALT.size].toInt()).toByte()
    }
    return Base64.getEncoder().encodeToString(salted)
}

/**
 * Local, untracked configuration: the SDK path, the Unsplash key and the four signing constants.
 *
 * Read through `providers.fileContents` so the configuration cache tracks `local.properties` as an
 * input. A plain `File.inputStream()` read at configuration time is untracked, so editing the file
 * would leave a stale cached configuration behind — and a stale one here means a release built
 * against yesterday's signing settings.
 */
val localProps = Properties().apply {
    providers.fileContents(
        rootProject.layout.projectDirectory.file("local.properties")
    ).asText.orNull?.let { load(it.reader()) }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.koin.android)
            implementation(libs.play.services.code.scanner)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.zxing.core)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.github.jvsena42.loopky"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.github.jvsena42.loopky"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 11
        versionName = "0.5.0"

        // Unsplash key for the "from web" image search; blank → gallery-only fallback.
        //
        // Emitted scrambled, and only scrambled — a plain constant put the literal in classes3.dex
        // where `strings | grep` finds it, which is how leaked keys are actually found. This is a
        // speed bump against automated scanners, not protection: the key ends up in a live
        // Authorization header, so a proxy reads it regardless. See UnsplashKeyObfuscation.
        val unsplashKey = (localProps.getProperty("UNSPLASH_ACCESS_KEY") ?: "").trim()
        buildConfigField("String", "UNSPLASH_ACCESS_KEY_OBF", "\"${obfuscateUnsplashKey(unsplashKey)}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Absent on CI and on machines without a keystore, where release builds stay unsigned.
        // The four constants live in the gitignored local.properties and are never read anywhere
        // else — refer to them by name only.
        val keystoreFile = localProps["KEYSTORE_FILE"] as? String
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = localProps["KEYSTORE_PASSWORD"] as String
                keyAlias = localProps["KEY_ALIAS"] as String
                keyPassword = localProps["KEY_PASSWORD"] as String
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Staging is the dev-only default; override it in local.properties to point a debug
            // build at production or a locally-run indexer.
            val override = (localProps.getProperty("NEXUS_BASE_URL") ?: "").trim()
            val debugNexusUrl = override.ifEmpty { NEXUS_STAGING_URL }
            buildConfigField("String", "NEXUS_BASE_URL", "\"$debugNexusUrl\"")

            // The build-time default only; a debug build can also switch environment at runtime
            // from Settings, which takes effect on the next launch.
            val envOverride = (localProps.getProperty("PUBKY_ENV") ?: "").trim()
            val debugEnv = envOverride.ifEmpty { PUBKY_ENV_STAGING }
            buildConfigField("String", "PUBKY_ENV", "\"$debugEnv\"")
        }
        getByName("release") {
            // Null without a keystore, and then the build produces an unsigned APK rather than
            // failing — the release skill is what verifies the signature before publishing.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            // Never overridable: a release must read the same network its users publish to (#42).
            buildConfigField("String", "NEXUS_BASE_URL", "\"$NEXUS_PRODUCTION_URL\"")
            buildConfigField("String", "PUBKY_ENV", "\"$PUBKY_ENV_PRODUCTION\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

