package com.github.jvsena42.eco.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.home.HomeEffect
import com.github.jvsena42.eco.presentation.home.HomeUiState
import com.github.jvsena42.eco.presentation.home.HomeViewModel
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun HomeRoute(
    onCreateDeck: () -> Unit = {},
    onBrowseExamples: () -> Unit = {},
    onStartStudy: () -> Unit = {},
    onOpenDeck: (String) -> Unit = {},
) {
    val viewModel = koinInject<HomeViewModel>()
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentCreate by rememberUpdatedState(onCreateDeck)
    val currentBrowse by rememberUpdatedState(onBrowseExamples)
    val currentStart by rememberUpdatedState(onStartStudy)
    val currentOpen by rememberUpdatedState(onOpenDeck)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                HomeEffect.NavigateCreateDeck -> currentCreate()
                HomeEffect.NavigateBrowseExamples -> currentBrowse()
                HomeEffect.NavigateStartStudy -> currentStart()
                is HomeEffect.NavigateDeck -> currentOpen(effect.deckId)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onStartStudyClick = viewModel::onStartStudyClick,
        onCreateDeckClick = viewModel::onCreateDeckClick,
        onBrowseExamplesClick = viewModel::onBrowseExamplesClick,
        onDeckClick = viewModel::onDeckClick,
        onRetry = viewModel::onRefresh,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onStartStudyClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp)),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (state) {
            HomeUiState.Loading -> LoadingBlock()
            is HomeUiState.Empty -> {
                GreetingHeader(name = state.greetingName)
                HomeEmptyContent(
                    onCreateDeckClick = onCreateDeckClick,
                    onBrowseExamplesClick = onBrowseExamplesClick,
                )
            }
            is HomeUiState.Content -> {
                GreetingHeader(name = state.greetingName)
                HomeContent(
                    state = state,
                    onStartStudyClick = onStartStudyClick,
                    onDeckClick = onDeckClick,
                )
            }
            is HomeUiState.Error -> {
                GreetingHeader(name = state.greetingName)
                ErrorBlock(message = state.message, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = colors.accentPrimary)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 48.dp),
    ) {
        Text(
            text = "Something went wrong",
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = message,
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text("Retry", color = colors.accentPrimary)
        }
    }
}
