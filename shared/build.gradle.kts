import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
            // `api` so the ViewModel type stays visible to the platform UI layers (and the
            // exported iOS framework) that consume the shared ViewModels.
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kvault)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            // The media re-host job (#53). Lives here rather than in :composeApp because
            // PlatformModule.android.kt binds it and :shared cannot depend on the app module.
            implementation(libs.androidx.work.runtime)
            // JNA is required by the UniFFI-generated Kotlin bindings (uniffi.pubkycore).
            // `@aar` pulls the Android-flavored artifact with the native .so bundled.
            implementation("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")
        }
    }
}

android {
    namespace = "com.github.jvsena42.loopky.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    testOptions {
        // commonTest code exercises shared classes that log via android.util.Log;
        // return defaults instead of throwing "not mocked" in local unit tests.
        unitTests.isReturnDefaultValues = true
    }
}
