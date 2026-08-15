package com.github.jvsena42.loopky

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.github.jvsena42.loopky.ui.nav.LoopkyNavHost
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LoopkyTheme {
                // Surface every Modifier.testTag(...) as a UiAutomator/adb resource-id so the
                // android-cli journeys can target elements by id instead of pixel position.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    LoopkyNavHost()
                }
            }
        }
    }
}
