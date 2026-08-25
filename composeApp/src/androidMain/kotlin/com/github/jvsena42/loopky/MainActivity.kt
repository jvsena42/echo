package com.github.jvsena42.loopky

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import com.github.jvsena42.loopky.ui.importflow.FileReadException
import com.github.jvsena42.loopky.ui.importflow.IncomingFile
import com.github.jvsena42.loopky.ui.importflow.readPickedFile
import com.github.jvsena42.loopky.ui.layout.ProvideWindowSize
import com.github.jvsena42.loopky.ui.nav.LoopkyNavHost
import com.github.jvsena42.loopky.ui.nav.PendingOpen
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.importFileUri
import com.github.jvsena42.loopky.ui.util.pubkyLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * The address or the deck file the app was opened with, held until the nav host can act on
     * it. See [PendingOpen].
     */
    private val pendingOpen = MutableStateFlow<PendingOpen?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Swaps Theme.Loopky.Starting for Theme.Loopky. Must run before super.onCreate, and it
        // hands straight off to SplashContent — the splash window and that screen are drawn to
        // look the same, so there is no flash between them.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh launch. A recreated activity carries the same intent, so re-consuming
        // it would re-open a link the user has already navigated away from, or re-spool a file
        // whose one-shot uri grant may no longer be good.
        if (savedInstanceState == null) intent?.let(::consume)
        setContent {
            val pending by pendingOpen.asStateFlow().collectAsStateWithLifecycle()
            LoopkyTheme {
                // Above the nav host so every screen — including the ones reached by deeplink,
                // which never pass through MainScreen — can size itself to the window.
                ProvideWindowSize {
                    // Surface every Modifier.testTag(...) as a UiAutomator/adb resource-id so the
                    // android-cli journeys can target elements by id instead of pixel position.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true },
                    ) {
                        LoopkyNavHost(
                            pendingOpen = pending,
                            onPendingOpenHandled = { pendingOpen.value = null },
                        )
                    }
                }
            }
        }
    }

    /**
     * The activity is `singleTask`, so a link or a file opened while Loopky is already running is
     * delivered here rather than through a fresh [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * A file wins over a link, because the two overlap: sharing a `.txt` export arrives as
     * `ACTION_SEND` of `text/plain` — the same action and type as sharing a message with a
     * `pubky://` address in it — distinguished only by the stream extra. Checking the link first
     * would drop every shared text export into the "no link in that text" toast below.
     */
    private fun consume(intent: Intent) {
        val fileUri = intent.importFileUri()
        if (fileUri != null) {
            spool(fileUri)
            return
        }
        val link = intent.pubkyLink()
        if (link != null) {
            pendingOpen.value = PendingOpen.Link(link)
        } else if (intent.action == Intent.ACTION_SEND) {
            // The one case worth saying something about: the user picked Loopky out of a share
            // sheet and would otherwise just watch the app open on whatever screen it was on.
            Toast.makeText(this, R.string.deeplink_no_link_in_shared_text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Copies [uri] into our cache immediately, while the grant that came with it is still good,
     * and publishes the result for the import screen to pick up. See [IncomingFile].
     *
     * [IncomingFile.Reading] is published first rather than after the copy: a 137 MB deck takes a
     * moment, and a share into a running Loopky should not look like nothing happened.
     */
    private fun spool(uri: Uri) {
        pendingOpen.value = PendingOpen.File(IncomingFile.Reading)
        lifecycleScope.launch {
            val read = contentResolver.readPickedFile(uri, cacheDir)
            pendingOpen.value = PendingOpen.File(
                read.fold(
                    onSuccess = { IncomingFile.Ready(it) },
                    onFailure = { err ->
                        IncomingFile.Failed(
                            (err as? FileReadException)?.reason ?: BulkImportError.Unreadable,
                        )
                    },
                ),
            )
        }
    }
}
