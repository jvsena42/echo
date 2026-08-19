import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * The Pubky Nexus indexer, per build type. Staging and production index separate networks, so a
 * release build reading staging would show trending tags and search results that no production
 * user ever published (#42).
 */
val NEXUS_STAGING_URL = "https://nexus.staging.pubky.app"
val NEXUS_PRODUCTION_URL = "https://nexus.pubky.app"

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
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
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

    val localProps = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    defaultConfig {
        applicationId = "com.github.jvsena42.loopky"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // Unsplash key for the "from web" image search; blank → gallery-only fallback.
        val unsplashKey = (localProps.getProperty("UNSPLASH_ACCESS_KEY") ?: "").trim()
        buildConfigField("String", "UNSPLASH_ACCESS_KEY", "\"$unsplashKey\"")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            // Staging is the dev-only default; override it in local.properties to point a debug
            // build at production or a locally-run indexer.
            val override = (localProps.getProperty("NEXUS_BASE_URL") ?: "").trim()
            val debugNexusUrl = override.ifEmpty { NEXUS_STAGING_URL }
            buildConfigField("String", "NEXUS_BASE_URL", "\"$debugNexusUrl\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            // Never overridable: a release must read the same network its users publish to (#42).
            buildConfigField("String", "NEXUS_BASE_URL", "\"$NEXUS_PRODUCTION_URL\"")
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

