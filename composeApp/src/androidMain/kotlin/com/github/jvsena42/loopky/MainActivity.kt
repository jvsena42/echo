package com.github.jvsena42.loopky

import android.content.Intent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.ui.nav.LoopkyNavHost
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.pubkyLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    /**
     * The `pubky://` address the app was opened with, held until the nav host is past onboarding
     * and can act on it. State rather than a one-shot channel because a cold start delivers the
     * link before there is anything on screen to receive it.
     */
    private val deepLink = MutableStateFlow<PubkyLink?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.let(::consume)
        setContent {
            val pendingLink by deepLink.asStateFlow().collectAsStateWithLifecycle()
            LoopkyTheme {
                // Surface every Modifier.testTag(...) as a UiAutomator/adb resource-id so the
                // android-cli journeys can target elements by id instead of pixel position.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    LoopkyNavHost(
                        deepLink = pendingLink,
                        onDeepLinkHandled = { deepLink.value = null },
                    )
                }
            }
        }
    }

    /**
     * The activity is `singleTask`, so a link tapped while Loopky is already running is delivered
     * here rather than through a fresh [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * Text shared into Loopky that carries no address is the one case worth saying something
     * about: the user picked Loopky out of a share sheet and would otherwise just watch the app
     * open on whatever screen it was already on.
     */
    private fun consume(intent: Intent) {
        val link = intent.pubkyLink()
        if (link != null) {
            deepLink.value = link
        } else if (intent.action == Intent.ACTION_SEND) {
            Toast.makeText(this, R.string.deeplink_no_link_in_shared_text, Toast.LENGTH_LONG).show()
        }
    }
}
