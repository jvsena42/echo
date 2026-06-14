package com.github.jvsena42.echo.ui.decks

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.presentation.decks.CardPreviewModel
import com.github.jvsena42.echo.presentation.decks.DeckDetailEffect
import com.github.jvsena42.echo.presentation.decks.DeckDetailUiState
import com.github.jvsena42.echo.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.echo.ui.components.AuthorRow
import com.github.jvsena42.echo.ui.components.CardPreviewRow
import com.github.jvsena42.echo.ui.components.EchoPrimaryButton
import com.github.jvsena42.echo.ui.components.StatsBar
import com.github.jvsena42.echo.ui.components.TagChip
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun DeckDetailRoute(
    deckId: String,
    authorPubky: String? = null,
    onBack: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
    onStudy: (String) -> Unit = {},
) {
    val viewModel = koinInject<DeckDetailViewModel> { parametersOf(deckId, authorPubky) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentBack by rememberUpdatedState(onBack)
    val currentEditDeck by rememberUpdatedState(onEditDeck)
    val currentStudy by rememberUpdatedState(onStudy)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DeckDetailEffect.NavigateBack -> currentBack()
                is DeckDetailEffect.NavigateEditDeck -> currentEditDeck(effect.deckId)
                DeckDetailEffect.NavigateStudy -> currentStudy(deckId)
                is DeckDetailEffect.Share -> { /* handled by platform share sheet */ }
                DeckDetailEffect.Deleted -> currentBack()
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
        onDeleteClick = viewModel::onDeleteDeck,
        onConfirmDelete = viewModel::onConfirmDelete,
        onDismissDelete = viewModel::onDismissDelete,
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
    onDeleteClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
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
                onEditClick = onEditClick,
                onDeleteClick = onDeleteClick,
            )
            if (state.showDeleteConfirm) {
                DeleteDeckDialog(
                    onConfirm = onConfirmDelete,
                    onDismiss = onDismissDelete,
                )
            }
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
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
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
                    modifier = Modifier.testTag("deck_study"),
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
            // Header: Back + Edit/Delete (owner only) + Share
            HeaderBar(
                isOwned = state.isOwned,
                onBackClick = onBackClick,
                onShareClick = onShareClick,
                onEditClick = onEditClick,
                onDeleteClick = onDeleteClick,
            )

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
private fun HeaderBar(
    isOwned: Boolean,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCircleButton(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Back",
            iconSize = 24.dp,
            onClick = onBackClick,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isOwned) {
                HeaderCircleButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit deck",
                    onClick = onEditClick,
                    modifier = Modifier.testTag("deck_edit"),
                )
                HeaderCircleButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete deck",
                    tint = colors.danger,
                    onClick = onDeleteClick,
                    modifier = Modifier.testTag("deck_delete"),
                )
            }
            HeaderCircleButton(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                onClick = onShareClick,
                modifier = Modifier.testTag("deck_share"),
            )
        }
    }
}

@Composable
private fun HeaderCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconSize: Dp = 20.dp,
) {
    val colors = EchoTheme.colors
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(50),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.surfaceCard,
            contentColor = if (tint == Color.Unspecified) colors.foregroundPrimary else tint,
        ),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun DeleteDeckDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = EchoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        title = {
            Text(
                text = "Delete deck",
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Text(
                text = "Delete this deck? It will be removed from your homeserver.",
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("deck_delete_confirm")) {
                Text("Delete", color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.foregroundMuted)
            }
        },
    )
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
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = "IN YOUR LIBRARY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.5.sp,
                )
            },
            shape = RoundedCornerShape(50),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = colors.srsGood,
                labelColor = colors.foregroundOnAccent,
            ),
            border = null,
        )

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

@Preview
@Composable
private fun DeckDetailScreenPreview() {
    EchoTheme {
        DeckDetailScreen(
            state = DeckDetailUiState.Content(
                deckId = "deck1",
                title = "Spanish Essentials",
                description = "Core vocabulary for everyday conversations.",
                coverEmoji = "🇪🇸",
                authorName = "Alex",
                authorPubky = "pk:abcdef123456",
                authorInitial = 'A',
                isOwned = true,
                tags = listOf("language", "spanish", "beginner"),
                totalCards = 42,
                dueCards = 8,
                masteredPercent = "65%",
                cardPreviews = listOf(
                    CardPreviewModel(id = "c1", frontText = "Hola", backText = "Hello"),
                    CardPreviewModel(id = "c2", frontText = "Gracias", backText = "Thank you"),
                    CardPreviewModel(id = "c3", frontText = "Adiós", backText = "Goodbye"),
                ),
            ),
            onBackClick = {},
            onShareClick = {},
            onStudyClick = {},
            onEditClick = {},
            onDeleteClick = {},
            onConfirmDelete = {},
            onDismissDelete = {},
            onRetry = {},
        )
    }
}
