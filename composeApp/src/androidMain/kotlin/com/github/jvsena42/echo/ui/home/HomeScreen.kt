package com.github.jvsena42.echo.ui.home

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.presentation.home.DeckSummary
import com.github.jvsena42.echo.presentation.home.HomeEffect
import com.github.jvsena42.echo.presentation.home.HomeUiState
import com.github.jvsena42.echo.presentation.home.HomeViewModel
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun HomeRoute(
    onCreateDeck: () -> Unit = {},
    onBrowseExamples: () -> Unit = {},
    onStartStudy: () -> Unit = {},
    onOpenDeck: (String) -> Unit = {},
    onSignedOut: () -> Unit = {},
) {
    val viewModel = koinInject<HomeViewModel>()
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentCreate by rememberUpdatedState(onCreateDeck)
    val currentBrowse by rememberUpdatedState(onBrowseExamples)
    val currentStart by rememberUpdatedState(onStartStudy)
    val currentOpen by rememberUpdatedState(onOpenDeck)
    val currentSignedOut by rememberUpdatedState(onSignedOut)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                HomeEffect.NavigateCreateDeck -> currentCreate()
                HomeEffect.NavigateBrowseExamples -> currentBrowse()
                HomeEffect.NavigateStartStudy -> currentStart()
                is HomeEffect.NavigateDeck -> currentOpen(effect.deckId)
                HomeEffect.NavigateToOnboarding -> currentSignedOut()
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

@OptIn(ExperimentalMaterial3Api::class)
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
    PullToRefreshBox(
        isRefreshing = state is HomeUiState.Loading,
        onRefresh = onRetry,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        HomeScreenContent(
            state = state,
            onStartStudyClick = onStartStudyClick,
            onCreateDeckClick = onCreateDeckClick,
            onBrowseExamplesClick = onBrowseExamplesClick,
            onDeckClick = onDeckClick,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun HomeScreenContent(
    state: HomeUiState,
    onStartStudyClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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

@Preview
@Composable
private fun HomeScreenPreview() {
    EchoTheme {
        HomeScreen(
            state = HomeUiState.Content(
                greetingName = "Alex",
                dueToday = 24,
                doneToday = 9,
                decks = listOf(
                    DeckSummary(
                        id = "1",
                        title = "Spanish Basics",
                        cardCount = 60,
                        dueCount = 12,
                        coverInitial = 'S',
                    ),
                    DeckSummary(
                        id = "2",
                        title = "Kanji N5",
                        cardCount = 103,
                        dueCount = 8,
                        coverInitial = 'K',
                    ),
                ),
            ),
            onStartStudyClick = {},
            onCreateDeckClick = {},
            onBrowseExamplesClick = {},
            onDeckClick = {},
            onRetry = {},
        )
    }
}
