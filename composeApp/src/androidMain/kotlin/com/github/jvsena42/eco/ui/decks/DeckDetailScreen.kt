package com.github.jvsena42.eco.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.decks.CardPreviewModel
import com.github.jvsena42.eco.presentation.decks.DeckDetailEffect
import com.github.jvsena42.eco.presentation.decks.DeckDetailUiState
import com.github.jvsena42.eco.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.eco.ui.components.AuthorRow
import com.github.jvsena42.eco.ui.components.CardPreviewRow
import com.github.jvsena42.eco.ui.components.EchoPrimaryButton
import com.github.jvsena42.eco.ui.components.StatsBar
import com.github.jvsena42.eco.ui.components.TagChip
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun DeckDetailRoute(
    deckId: String,
    onBack: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
) {
    val viewModel = koinInject<DeckDetailViewModel> { parametersOf(deckId) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentBack by rememberUpdatedState(onBack)
    val currentEditDeck by rememberUpdatedState(onEditDeck)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DeckDetailEffect.NavigateBack -> currentBack()
                is DeckDetailEffect.NavigateEditDeck -> currentEditDeck(effect.deckId)
                DeckDetailEffect.NavigateStudy -> { /* handled by parent nav */ }
                is DeckDetailEffect.Share -> { /* handled by platform share sheet */ }
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DeckDetailScreen(
        state = state,
        onBackClick = viewModel::onBackClick,
        onShareClick = viewModel::onShareClick,
        onStudyClick = viewModel::onStudyClick,
        onEditClick = viewModel::onEditClick,
        onRetry = viewModel::onRefresh,
    )
}

@Composable
fun DeckDetailScreen(
    state: DeckDetailUiState,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onStudyClick: () -> Unit,
    onEditClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = EchoTheme.colors

    when (state) {
        DeckDetailUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfacePrimary),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.accentPrimary)
            }
        }

        is DeckDetailUiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfacePrimary)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Something went wrong",
                    color = colors.foregroundPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = state.message,
                    color = colors.foregroundMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onRetry) {
                    Text("Retry", color = colors.accentPrimary)
                }
            }
        }

        is DeckDetailUiState.Content -> {
            DeckDetailContent(
                state = state,
                onBackClick = onBackClick,
                onShareClick = onShareClick,
                onStudyClick = onStudyClick,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckDetailContent(
    state: DeckDetailUiState.Content,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onStudyClick: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                EchoPrimaryButton(
                    label = if (state.isOwned) {
                        "Start studying \u00B7 ${state.dueCards} due"
                    } else {
                        "Study this deck"
                    },
                    onClick = onStudyClick,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = colors.foregroundOnAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header: Back + Share
            HeaderBar(onBackClick = onBackClick, onShareClick = onShareClick)

            // Cover
            CoverSection(coverEmoji = state.coverEmoji, isOwned = state.isOwned)

            // Owned badge
            if (state.isOwned) {
                OwnedBadgeRow()
            }

            // Title + Description
            TitleSection(title = state.title, description = state.description)

            // Author
            AuthorRow(
                name = state.authorName,
                pubky = state.authorPubky,
                initial = state.authorInitial,
                modifier = Modifier.fillMaxWidth(),
            )

            // Tags
            if (state.tags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.tags.forEach { tag ->
                        TagChip(tag = tag)
                    }
                }
            }

            // Stats
            StatsBar(
                totalCards = state.totalCards,
                dueCards = state.dueCards,
                masteredPercent = state.masteredPercent,
            )

            // Card previews
            if (state.cardPreviews.isNotEmpty()) {
                CardPreviewList(cards = state.cardPreviews)
            }
        }
    }
}

@Composable
private fun HeaderBar(onBackClick: () -> Unit, onShareClick: () -> Unit) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceCard)
                .clickable(onClick = onBackClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(24.dp),
            )
        }

        // Share circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceCard)
                .clickable(onClick = onShareClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CoverSection(coverEmoji: String, isOwned: Boolean) {
    val colors = EchoTheme.colors
    val coverHeight = if (isOwned) 120.dp else 160.dp
    val emojiSize = if (isOwned) 64.sp else 80.sp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimarySoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = coverEmoji,
            fontSize = emojiSize,
        )
    }
}

@Composable
private fun OwnedBadgeRow() {
    val colors = EchoTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Green badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.srsGood)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "IN YOUR LIBRARY",
                color = colors.foregroundOnAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.5.sp,
            )
        }

        Text(
            text = "Last studied...",
            color = colors.foregroundMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TitleSection(title: String, description: String?) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.W800,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun CardPreviewList(cards: List<CardPreviewModel>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { card ->
            CardPreviewRow(
                frontText = card.frontText,
                backText = card.backText,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
