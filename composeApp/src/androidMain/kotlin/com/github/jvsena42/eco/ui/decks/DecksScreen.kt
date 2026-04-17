package com.github.jvsena42.eco.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.decks.DeckTileModel
import com.github.jvsena42.eco.presentation.decks.DecksLibraryEffect
import com.github.jvsena42.eco.presentation.decks.DecksLibraryUiState
import com.github.jvsena42.eco.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.eco.ui.components.DeckTile
import com.github.jvsena42.eco.ui.components.EchoPrimaryButton
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun DecksRoute(
    onDeckClick: (String) -> Unit = {},
    onImportClick: () -> Unit = {},
    onCreateDeckClick: () -> Unit = {},
) {
    val viewModel = koinInject<DecksLibraryViewModel>()
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentDeckClick by rememberUpdatedState(onDeckClick)
    val currentImportClick by rememberUpdatedState(onImportClick)
    val currentCreateDeckClick by rememberUpdatedState(onCreateDeckClick)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is DecksLibraryEffect.NavigateDeckDetail -> currentDeckClick(effect.deckId)
                DecksLibraryEffect.NavigateImport -> currentImportClick()
                DecksLibraryEffect.NavigateCreateDeck -> currentCreateDeckClick()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DecksScreen(
        state = state,
        onDeckClick = viewModel::onDeckClick,
        onImportClick = viewModel::onImportClick,
        onCreateDeckClick = viewModel::onCreateDeckClick,
        onRetry = viewModel::onRefresh,
    )
}

@Composable
fun DecksScreen(
    state: DecksLibraryUiState,
    onDeckClick: (String) -> Unit,
    onImportClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderRow()
        PasteCtaCard(onClick = onImportClick)

        when (state) {
            DecksLibraryUiState.Loading -> LoadingBlock()
            DecksLibraryUiState.Empty -> EmptyBlock(onCreateDeckClick = onCreateDeckClick)
            is DecksLibraryUiState.Content -> {
                SectionHeader(deckCount = state.deckCount)
                DeckGrid(decks = state.decks, onDeckClick = onDeckClick)
            }
            is DecksLibraryUiState.Error -> ErrorBlock(
                message = state.message,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun HeaderRow() {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Your decks",
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = colors.foregroundPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun PasteCtaCard(onClick: () -> Unit) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x33FF5C00),
                spotColor = Color(0x33FF5C00),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimary)
            .clickable(onClick = onClick)
            .padding(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Icon box
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\uD83D\uDCCB",
                    fontSize = 20.sp,
                )
            }

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Paste to import",
                    color = colors.foregroundOnAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W700,
                )
                Text(
                    text = "Turn any list into a deck",
                    color = colors.foregroundOnAccent.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                )
            }

            // Arrow icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Go",
                tint = colors.foregroundOnAccent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(deckCount: Int) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Library \u00B7 $deckCount",
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
        )
        Text(
            text = "Recent",
            color = colors.accentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
        )
    }
}

@Composable
private fun DeckGrid(decks: List<DeckTileModel>, onDeckClick: (String) -> Unit) {
    val rows = decks.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { deck ->
                    DeckTile(
                        title = deck.title,
                        cardCount = deck.cardCount,
                        coverEmoji = deck.coverEmoji,
                        authorLabel = deck.authorLabel,
                        onClick = { onDeckClick(deck.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // If odd number of items, add spacer to balance the last row
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
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
private fun EmptyBlock(onCreateDeckClick: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
    ) {
        Text(
            text = "\uD83D\uDCDA",
            fontSize = 48.sp,
        )
        Text(
            text = "No decks yet",
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = "Create your first deck or paste a list to get started.",
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        EchoPrimaryButton(
            label = "Create a deck",
            onClick = onCreateDeckClick,
            modifier = Modifier.padding(top = 8.dp),
        )
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
        TextButton(onClick = onRetry) {
            Text("Retry", color = colors.accentPrimary)
        }
    }
}
