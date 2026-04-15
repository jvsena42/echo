package com.github.jvsena42.eco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.jvsena42.eco.ui.nav.EchoNavHost
import com.github.jvsena42.eco.ui.theme.EchoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EchoTheme {
                EchoNavHost()
            }
        }
    }
}
