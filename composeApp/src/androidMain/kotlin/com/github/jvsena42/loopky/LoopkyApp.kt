package com.github.jvsena42.loopky

import android.app.Application
import com.github.jvsena42.loopky.di.initKoinAndroid
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import uniffi.pubkycore.RustlsInit
import uniffi.pubkycore.initLogging

class LoopkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must precede any Pubky network call: without it the first TLS handshake panics on a
        // background task and sign-in fails with an unexplained "auth request expired".
        RustlsInit.init(this)
        if (BuildConfig.DEBUG) {
            // Routes the SDK's tracing plus any Rust panic to logcat under the tag `pubkycore`.
            // Debug-only — it logs network activity.
            initLogging()
        }
        initKoinAndroid(
            // A fallback only: a key the user saves in Settings wins, and this one is never
            // shown to them.
            unsplashFallbackKey = BuildConfig.UNSPLASH_ACCESS_KEY,
            // Staging on debug, production on release — see composeApp/build.gradle.kts.
            nexusBaseUrl = BuildConfig.NEXUS_BASE_URL,
        ) {
            androidLogger()
            androidContext(this@LoopkyApp)
        }
    }
}
