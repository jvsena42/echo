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
            // Reference-counted, because the flag is a property of the *window* while this is
            // scoped to a composable, and Compose Navigation composes the incoming destination
            // before disposing the outgoing one. With a plain add/clear, moving between two secure
            // screens — the recovery phrase to its confirm quiz, and back — cleared the flag on
            // arrival and left the screen showing twelve words capturable. Nothing reports that;
            // it only shows up in a screen recording that should have been black.
            if (secureRequests++ == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            if (!BuildConfig.DEBUG) {
                if (--secureRequests == 0) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

/**
 * How many secure screens are currently composed.
 *
 * Single-threaded by construction: composition and disposal both run on the main thread.
 */
private var secureRequests = 0
