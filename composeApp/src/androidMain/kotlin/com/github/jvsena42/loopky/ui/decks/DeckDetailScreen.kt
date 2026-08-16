package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.decks.CardPreviewModel
import com.github.jvsena42.loopky.presentation.decks.DeckDetailEffect
import com.github.jvsena42.loopky.presentation.decks.DeckDetailUiState
import com.github.jvsena42.loopky.presentation.decks.DeckDetailViewModel
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.CardPreviewRow
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.StatsBar
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.shareText
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun DeckDetailRoute(
    deckId: String,
    authorPubky: String? = null,
    onBack: () -> Unit = {},
    onEditDeck: (String) -> Unit = {},
    onStudy: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<DeckDetailViewModel> { parametersOf(deckId, authorPubky) }

    val context = LocalContext.current
    val currentBack by rememberUpdatedState(onBack)
    val currentEditDeck by rememberUpdatedState(onEditDeck)
    val currentStudy by rememberUpdatedState(onStudy)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DeckDetailEffect.NavigateBack -> currentBack()
                is DeckDetailEffect.NavigateEditDeck -> currentEditDeck(effect.deckId)
                DeckDetailEffect.NavigateStudy -> currentStudy(deckId)
                is DeckDetailEffect.Share -> context.shareText(
                    text = context.getString(R.string.share_deck_body, effect.title, effect.uri),
                    chooserTitle = context.getString(R.string.share_deck_chooser_title),
                )
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
    val colors = LoopkyTheme.colors

    when (state) {
        DeckDetailUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfacePrimary),
            ) {
                LoopkyLoadingScreen(message = stringResource(R.string.deck_detail_loading))
            }
        }

        is DeckDetailUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfacePrimary)
                    .statusBarsPadding(),
            ) {
                // Without this the screen is a trap: after a deck is deleted the stale tile
                // still opens here, "Deck not found" can never be retried away, and the only
                // way out is the system back gesture.
                HeaderCircleButton(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.deck_detail_back),
                    iconSize = 24.dp,
                    onClick = onBackClick,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("deck_detail_back"),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = errorTitle(state.reason),
                        color = colors.foregroundPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = errorMessage(state.reason),
                        color = colors.foregroundMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Retrying a deck that no longer exists can only fail again.
                    if (state.canRetry) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.deck_detail_retry), color = colors.accentPrimary)
                        }
                    }
                    TextButton(onClick = onBackClick, modifier = Modifier.testTag("deck_detail_back_to_decks")) {
                        Text(
                            text = stringResource(R.string.deck_detail_back_to_decks),
                            color = if (state.canRetry) colors.foregroundMuted else colors.accentPrimary,
                        )
                    }
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
                    deckTitle = state.title,
                    onConfirm = onConfirmDelete,
                    onDismiss = onDismissDelete,
                )
            }
            if (state.isDeleting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfacePrimary),
                ) {
                    LoopkyLoadingScreen(message = stringResource(R.string.deck_detail_deleting))
                }
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
    val colors = LoopkyTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                LoopkyPrimaryButton(
                    label = if (state.isOwned) {
                        stringResource(R.string.deck_detail_start_studying, state.dueCards)
                    } else {
                        stringResource(R.string.deck_detail_study_this_deck)
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
        // A LazyColumn rather than a scrolling Column: the card list below is as long as the
        // deck, and a 500-card deck would otherwise compose every row up front.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("deck_detail_content"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        ) {
            // Everything above the card list is a fixed set of sections that are on screen
            // together anyway, so they stay in one item and keep their shared 20.dp rhythm.
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Header: Back + Edit/Delete (owner only) + Share
                    HeaderBar(
                        isOwned = state.isOwned,
                        onBackClick = onBackClick,
                        onShareClick = onShareClick,
                        onEditClick = onEditClick,
                        onDeleteClick = onDeleteClick,
                    )

                    // Cover
                    CoverSection(
                        coverEmoji = state.coverEmoji,
                        coverImageUrl = state.coverImageUrl,
                        coverImageBase64 = state.coverImageBase64,
                        isOwned = state.isOwned,
                    )

                    // Owned badge
                    if (state.isOwned) {
                        OwnedBadgeRow()
                        if (state.isIncomplete) IncompleteWarning()
                    }

                    // Title + Description
                    TitleSection(title = state.title, description = state.description)

                    // Author
                    AuthorRow(
                        identity = state.author,
                        isOwned = state.isOwned,
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
                }
            }

            // Cards. Shown for decks you don't own too — being able to look through the cards
            // before studying is what makes a shared deck worth opening.
            item(key = "cards_heading") {
                CardsHeading(
                    count = state.cardPreviews.size,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                )
            }

            if (state.cardPreviews.isEmpty()) {
                item(key = "cards_empty") {
                    CardsEmptyState(isOwned = state.isOwned)
                }
            } else {
                items(state.cardPreviews, key = { it.id }) { card ->
                    CardPreviewRow(
                        frontText = card.frontText,
                        backText = card.backText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("deck_card_row"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardsHeading(count: Int, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.deck_detail_cards_heading),
            color = colors.foregroundPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.W800,
        )
        Text(
            text = "$count",
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.W700,
        )
    }
}

@Composable
private fun CardsEmptyState(isOwned: Boolean) {
    val colors = LoopkyTheme.colors
    Text(
        text = if (isOwned) {
            stringResource(R.string.deck_detail_cards_empty_owned)
        } else {
            stringResource(R.string.deck_detail_cards_empty_foreign)
        },
        color = colors.foregroundMuted,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deck_cards_empty"),
    )
}

@Composable
private fun HeaderBar(
    isOwned: Boolean,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCircleButton(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.deck_detail_back),
            iconSize = 24.dp,
            onClick = onBackClick,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isOwned) {
                HeaderCircleButton(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.deck_detail_edit),
                    onClick = onEditClick,
                    modifier = Modifier.testTag("deck_edit"),
                )
                HeaderCircleButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.deck_detail_delete),
                    tint = colors.danger,
                    onClick = onDeleteClick,
                    modifier = Modifier.testTag("deck_delete"),
                )
            }
            HeaderCircleButton(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(R.string.deck_detail_share),
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
    val colors = LoopkyTheme.colors
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
private fun DeleteDeckDialog(deckTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        // AlertDialog renders in its own window, so the root's testTagsAsResourceId does not
        // reach it — journeys/05 could not find `deck_delete_confirm` without this.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = stringResource(R.string.deck_detail_delete_dialog_title),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.deck_detail_delete_dialog_message, deckTitle),
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("deck_delete_confirm")) {
                Text(stringResource(R.string.deck_detail_delete_confirm), color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.deck_detail_delete_cancel), color = colors.foregroundMuted)
            }
        },
    )
}

/**
 * Cover in priority order: remote URL → homeserver blob (Base64 bytes loaded by the ViewModel) →
 * the accent-soft emoji box. Coil renders both a URL string and a decoded [ByteArray] directly.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun CoverSection(
    coverEmoji: String,
    coverImageUrl: String?,
    coverImageBase64: String?,
    isOwned: Boolean,
) {
    val colors = LoopkyTheme.colors
    val coverHeight = if (isOwned) 120.dp else 160.dp
    val emojiSize = if (isOwned) 64.sp else 80.sp

    val coverModel: Any? = remember(coverImageUrl, coverImageBase64) {
        when {
            !coverImageUrl.isNullOrEmpty() -> coverImageUrl
            !coverImageBase64.isNullOrEmpty() -> runCatching { Base64.decode(coverImageBase64) }.getOrNull()
            else -> null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimarySoft),
        contentAlignment = Alignment.Center,
    ) {
        if (coverModel != null) {
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = coverEmoji,
                fontSize = emojiSize,
            )
        }
    }
}

/**
 * A publish that died part-way leaves the deck claimed but short of cards. The count comes from
 * the manifest, so without this the deck looks complete while holding fewer cards than it says.
 */
@Composable
private fun IncompleteWarning() {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.deck_incomplete_warning),
        fontSize = 13.sp,
        color = colors.srsAgain,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun OwnedBadgeRow() {
    val colors = LoopkyTheme.colors
    // The "last studied" date is not tracked yet, so only the library badge is shown for now.
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = stringResource(R.string.deck_detail_in_your_library),
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
}

@Composable
private fun TitleSection(title: String, description: String?) {
    val colors = LoopkyTheme.colors
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

@Preview
@Composable
private fun DeckDetailScreenPreview() {
    LoopkyTheme {
        DeckDetailScreen(
            state = DeckDetailUiState.Content(
                deckId = "deck1",
                title = "Spanish Essentials",
                description = "Core vocabulary for everyday conversations.",
                coverEmoji = "🇪🇸",
                author = PubkyIdentity("abcdef123456xyz", "Alex", avatarUrl = null, bio = null),
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

@Preview
@Composable
private fun DeckDetailEmptyCardsPreview() {
    LoopkyTheme {
        DeckDetailScreen(
            state = DeckDetailUiState.Content(
                deckId = "deck2",
                title = "Kanji N5",
                description = null,
                coverEmoji = "🇯🇵",
                author = PubkyIdentity("zyxwvu987654abc", "Mei", avatarUrl = null, bio = null),
                isOwned = false,
                tags = listOf("japanese"),
                totalCards = 0,
                dueCards = 0,
                masteredPercent = "—",
                cardPreviews = emptyList(),
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
