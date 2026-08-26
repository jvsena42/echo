package com.github.jvsena42.loopky.ui.util

import android.view.autofill.AutofillManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps the platform autofill session from reaching the point where it can offer to *save* what is
 * on the calling screen.
 *
 * **Read what this does and does not do before relying on it.**
 *
 * Focusing a text field opens an autofill session and dispatches the view structure to whichever
 * service the device is configured with — Google Password Manager on a stock Play-services device —
 * plus a second *augmented* request to Android System Intelligence. On a screen holding twelve
 * recovery words or a recovery-file passphrase that is a credential leaving the app.
 *
 * **The dispatch itself could not be prevented from app code.** Four mechanisms were measured on a
 * Pixel_9 emulator with `dumpsys autofill`, comparing the newest request entry across a cold-start
 * focus of `restore_phrase_input`, and every one of them still produced a request to
 * `s=com.google.android.gms` with the field's bounds:
 *
 * 1. `importantForAutofill = NO_EXCLUDE_DESCENDANTS` on `LocalView.current`
 * 2. the same on the window's decor view
 * 3. `CompositionLocalProvider(LocalAutofillManager provides null)` around the field — this
 *    suppressed *subsequent* focuses but not the first on a cold start
 * 4. `android:importantForAutofill="noExcludeDescendants"` on the activity in the manifest
 *
 * The reason is Compose: `androidx.compose.ui.autofill.AndroidAutofillManager` implements
 * `FocusListener` and notifies the platform when a field takes focus, independent of the view
 * flags the framework would otherwise consult. There is no per-node opt-out in the semantics API
 * at Compose Multiplatform 1.10.3 — `contentType`, `contentDataType` and `fillableData` describe a
 * field, none excludes one.
 *
 * What is left, and what this does: [AutofillManager.cancel] destroys the session without saving.
 * Called on entry it drops any session inherited from the screen behind, and called on leave it
 * takes the session down *before the commit* at which a service holding `SaveInfo` would get its
 * chance to offer "save to Google Password Manager". The structure has still been sent; the save
 * prompt is what this closes off.
 *
 * The manifest attribute is kept alongside this. It is the correct declaration of intent and costs
 * nothing, and other autofill implementations may honour it even though this one does not.
 *
 * **The remaining option, if the dispatch itself has to stop**, is to stop using Compose text
 * fields for these four inputs — a classic `EditText` inside an `AndroidView` does honour
 * `importantForAutofill`. That is a real change to four screens and is not taken here.
 *
 * Reference-counted for the same reason [SecureScreen] is: Compose Navigation composes the incoming
 * destination before disposing the outgoing one, so a screen-to-screen hop between two secret
 * screens must not cancel on the way in and then think it is finished.
 */
@Composable
fun NoAutofill() {
    val autofillManager = LocalContext.current.getSystemService(AutofillManager::class.java)

    DisposableEffect(autofillManager) {
        if (autofillRequests++ == 0) {
            runCatching { autofillManager?.cancel() }
        }
        onDispose {
            if (--autofillRequests == 0) {
                // The important one: this is the moment the session would otherwise commit, which
                // is when a service that returned SaveInfo gets to offer to save the phrase.
                runCatching { autofillManager?.cancel() }
            }
        }
    }
}

/** Single-threaded by construction: composition and disposal both run on the main thread. */
private var autofillRequests = 0
