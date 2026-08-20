package com.github.jvsena42.loopky.ui.util

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.github.jvsena42.loopky.BuildConfig

/**
 * Blocks screenshots and screen recording while the calling composable is on screen.
 *
 * Scoped to a screen rather than set once on the Activity: the flag is a property of the window,
 * so leaving it on would make the whole app — decks, study, profiles — uncapturable, and sharing a
 * deck screenshot is a thing people legitimately do.
 *
 * Release builds only. Debug keeps capture working, or the android-cli journeys lose
 * `android screen capture` on every screen that calls this.
 */
@Composable
fun SecureScreen() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window) {
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (!BuildConfig.DEBUG) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
