package com.github.jvsena42.eco.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.eco.data.repository.IdentityRepository
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.ui.theme.EchoTheme
import org.koin.compose.koinInject

@Composable
fun HomeStubScreen(modifier: Modifier = Modifier) {
    val identityRepository = koinInject<IdentityRepository>()
    val session: Session? by produceState<Session?>(initialValue = null, identityRepository) {
        value = identityRepository.currentSession() ?: identityRepository.loadPersistedSession()
    }
    val colors = EchoTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "You're signed in",
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        val pubky = session?.identity?.pubky ?: "…"
        Text(
            text = "pk:${pubky.take(6)}…${pubky.takeLast(6)}",
            color = colors.foregroundSecondary,
            fontSize = 14.sp,
        )
    }
}
