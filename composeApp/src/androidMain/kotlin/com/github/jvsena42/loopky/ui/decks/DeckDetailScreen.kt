package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.github.jvsena42.loopky.ui.components.ExpandableLinkedText
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.SharePromptDialog
import com.github.jvsena42.loopky.ui.components.StatsBar
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.shareText
import com.github.jvsena42.loopky.ui.util.toast
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
    onEditCard: (String, String) -> Unit = { _, _ -> },
    onStudy: (String) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenClone: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<DeckDetailViewModel> { parametersOf(deckId, authorPubky) }

    val context = LocalContext.current
    val currentBack by rememberUpdatedState(onBack)
    val currentEditDeck by rememberUpdatedState(onEditDeck)
    val currentEditCard by rememberUpdatedState(onEditCard)
    val currentStudy by rememberUpdatedState(onStudy)
    val currentOpenTag by rememberUpdatedState(onOpenTag)
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenClone by rememberUpdatedState(onOpenClone)

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
                is DeckDetailEffect.Cloned -> currentOpenClone(effect.deckId)
                DeckDetailEffect.Shared -> context.toast(R.string.share_prompt_posted)
                DeckDetailEffect.ShareFailed -> context.toast(R.string.share_prompt_failed)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DeckDetailScreen(
        state = state,
        onOpenTag = currentOpenTag,
        onOpenProfile = currentOpenProfile,
        onBackClick = viewModel::onBackClick,
        onShareClick = viewModel::onShareClick,
        onStudyClick = viewModel::onStudyClick,
        onCardClick = { cardId -> currentEditCard(deckId, cardId) },
        onEditClick = viewModel::onEditClick,
        onDeleteClick = viewModel::onDeleteDeck,
        onConfirmDelete = viewModel::onConfirmDelete,
        onDismissDelete = viewModel::onDismissDelete,
        onToggleFollow = viewModel::onToggleFollow,
        onCloneClick = viewModel::onCloneClick,
        onConfirmClone = viewModel::onConfirmClone,
        onDismissClone = viewModel::onDismissClone,
        onDismissError = viewModel::onDismissError,
        onRetry = viewModel::onRefresh,
    )

    // Raised by a follow or a clone, over the loaded deck. A clone's navigation waits on it: the
    // screen would otherwise move to the copy and take the unanswered offer with it (#39).
    (state as? DeckDetailUiState.Content)?.sharePrompt?.let { prompt ->
        SharePromptDialog(
            prompt = prompt,
            onConfirm = viewModel::onShareConfirm,
            onDismiss = viewModel::onShareDismiss,
            onNeverAsk = viewModel::onShareNeverAsk,
        )
    }
}

@Composable
fun DeckDetailScreen(
    state: DeckDetailUiState,
    onOpenTag: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onStudyClick: () -> Unit,
    /** Null for a deck you do not own — a followed deck's cards are not yours to edit. */
    onCardClick: (String) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onToggleFollow: () -> Unit,
    onCloneClick: () -> Unit,
    onConfirmClone: () -> Unit,
    onDismissClone: () -> Unit,
    onDismissError: () -> Unit,
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
                onOpenTag = onOpenTag,
                onOpenProfile = onOpenProfile,
                onBackClick = onBackClick,
                onShareClick = onShareClick,
                onStudyClick = onStudyClick,
                onCardClick = onCardClick,
                onEditClick = onEditClick,
                onDeleteClick = onDeleteClick,
                onToggleFollow = onToggleFollow,
                onCloneClick = onCloneClick,
            )
            if (state.showDeleteConfirm) {
                DeleteDeckDialog(
                    deckTitle = state.title,
                    onConfirm = onConfirmDelete,
                    onDismiss = onDismissDelete,
                )
            }
            if (state.showCloneConfirm) {
                CloneDeckDialog(
                    deckTitle = state.title,
                    cardCount = state.totalCards,
                    onConfirm = onConfirmClone,
                    onDismiss = onDismissClone,
                )
            }
            // A failed follow or clone leaves the loaded deck on screen — there is nothing wrong
            // with it, only with the write — so it reports in a dialog rather than an Error state.
            state.errorReason?.let { reason ->
                RecoverableErrorDialog(reason = reason, onDismiss = onDismissError)
            }
            if (state.isDeleting || state.isCloning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfacePrimary),
                ) {
                    LoopkyLoadingScreen(
                        message = stringResource(
                            if (state.isCloning) {
                                R.string.deck_detail_cloning
                            } else {
                                R.string.deck_detail_deleting
                            },
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckDetailContent(
    state: DeckDetailUiState.Content,
    onOpenTag: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onStudyClick: () -> Unit,
    onCardClick: (String) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleFollow: () -> Unit,
    onCloneClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        bottomBar = {
            // Study is offered only for a deck you have kept. Grading a deck you are merely
            // browsing would strand review state under something that never reaches your
            // library or your due queue — progress you can neither see nor resume. Keeping the
            // deck is what earns it, so Follow and Clone sit up in the header instead, next to
            // the stats they act on, and the bottom bar stays a single unambiguous action.
            if (state.isOwned || state.isFollowing) {
                LoopkyPrimaryButton(
                    label = if (state.isOwned) {
                        stringResource(R.string.deck_detail_start_studying, state.dueCards)
                    } else {
                        stringResource(R.string.deck_detail_study_this_deck)
                    },
                    onClick = onStudyClick,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .testTag("deck_study"),
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

                    // Credit for a clone, so attribution lives on the copy and not only in the
                    // manifest's `source` block.
                    state.clonedFrom?.let { ClonedFromRow(author = it) }

                    // Hidden at zero rather than shown as "0 following": the indexer returns
                    // nothing when it is unreachable or has not caught up, and a confident zero
                    // would be a lie in both cases.
                    SocialCountsRow(
                        followerCount = state.followerCount,
                        clonedCount = state.clonedCount,
                    )

                    // Author — tapping them opens their profile. This is where you actually meet
                    // a stranger, so leaving it inert was the one dead end into their decks.
                    // Your own name stays inert: there is nowhere to go but the Profile tab.
                    AuthorRow(
                        identity = state.author,
                        isOwned = state.isOwned,
                        onNameClick = if (state.isOwned) {
                            null
                        } else {
                            { onOpenProfile(state.author.pubky) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deck_detail_author"),
                    )

                    // Tags
                    if (state.tags.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.tags.forEach { tag ->
                                // Live since #26: global browse exists, so a chip leads to every
                                // deck on the network carrying it rather than to a dead end.
                                TagChip(tag = tag, onClick = { onOpenTag(tag) })
                            }
                        }
                    }

                    // Stats
                    StatsBar(
                        totalCards = state.totalCards,
                        dueCards = state.dueCards,
                        masteredPercent = state.masteredPercent,
                    )

                    // The two ways of keeping someone else's deck, side by side and equally
                    // weighted: they are genuinely different choices, not a primary and an
                    // afterthought. They sit under the stats rather than in the bottom bar —
                    // crowded against Study they read as three competing primaries, and Study
                    // is the only action that belongs to a deck you have already kept.
                    if (!state.isOwned) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FollowDeckButton(
                                isFollowing = state.isFollowing,
                                isPending = state.isFollowPending,
                                onClick = onToggleFollow,
                                modifier = Modifier.weight(1f),
                            )
                            LoopkyPrimaryButton(
                                label = stringResource(R.string.deck_detail_clone),
                                onClick = onCloneClick,
                                enabled = !state.isCloning,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("deck_clone"),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = colors.foregroundOnAccent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                            )
                        }
                    }
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
                        frontImageRef = card.frontImageRef,
                        deckId = state.deckId,
                        // The deck's author, not the reader — a followed deck's blobs live on
                        // their pubky.
                        authorPubky = state.author.pubky,
                        // Only on a deck you own: these rows look like cards and did nothing at
                        // all, while the only way to reach a card was Edit deck → the same row.
                        onClick = if (state.isOwned) {
                            { onCardClick(card.id) }
                        } else {
                            null
                        },
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

/**
 * Follow/Following pill for a deck you don't own.
 *
 * Deliberately worded "Follow deck", not "Save": following and cloning are different actions with
 * different owners and lifecycles, and one ambiguous verb for both is what #33 set out to remove.
 * The alpha while pending matches [AuthorRow]'s author-follow pill — the same optimistic flip.
 */
@Composable
private fun FollowDeckButton(
    isFollowing: Boolean,
    isPending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    // A Material Button rather than a hand-rolled Box: it sits next to LoopkyPrimaryButton, and a
    // padded Box does not agree with Material's own button metrics — the two pills came out
    // different heights. Same component, same shape, same type scale; only the tint differs, which
    // is the native-first rule (brand tokens *on* the native component).
    Button(
        onClick = onClick,
        modifier = modifier.testTag("deck_follow"),
        enabled = !isPending,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFollowing) colors.accentSecondarySoft else colors.accentSecondary,
            contentColor = if (isFollowing) colors.accentSecondary else colors.foregroundOnAccent,
            // Dimmed while the write is in flight, not greyed out: the state has already flipped
            // optimistically, so it must still read as the state it is claiming.
            disabledContainerColor = (
                if (isFollowing) colors.accentSecondarySoft else colors.accentSecondary
                ).copy(alpha = FOLLOW_PENDING_ALPHA),
            disabledContentColor = (
                if (isFollowing) colors.accentSecondary else colors.foregroundOnAccent
                ).copy(alpha = FOLLOW_PENDING_ALPHA),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        // Icon + label, matching the Clone pill beside it — a bare word next to an iconed button
        // read as the lesser of the two. Check vs Add also carries the state on its own, so the
        // Follow/Following flip is legible without reading the label.
        Icon(
            imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = stringResource(
                if (isFollowing) R.string.deck_detail_following else R.string.deck_detail_follow,
            ),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
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
            ExpandableLinkedText(
                text = description,
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.testTag("deck_detail_description"),
            )
        }
    }
}

@Preview
@Composable
private fun DeckDetailScreenPreview() {
    LoopkyTheme {
        DeckDetailScreen(
            onOpenTag = {},
            onOpenProfile = {},
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
            onCardClick = {},
            onEditClick = {},
            onDeleteClick = {},
            onConfirmDelete = {},
            onDismissDelete = {},
            onToggleFollow = {},
            onCloneClick = {},
            onConfirmClone = {},
            onDismissClone = {},
            onDismissError = {},
            onRetry = {},
        )
    }
}

@Preview
@Composable
private fun DeckDetailEmptyCardsPreview() {
    LoopkyTheme {
        DeckDetailScreen(
            onOpenTag = {},
            onOpenProfile = {},
            state = DeckDetailUiState.Content(
                deckId = "deck2",
                title = "Kanji N5",
                description = null,
                coverEmoji = "🇯🇵",
                author = PubkyIdentity("zyxwvu987654abc", "Mei", avatarUrl = null, bio = null),
                isOwned = false,
                isFollowing = true,
                followerCount = 12,
                clonedCount = 3,
                tags = listOf("japanese"),
                totalCards = 0,
                dueCards = 0,
                masteredPercent = "—",
                cardPreviews = emptyList(),
            ),
            onBackClick = {},
            onShareClick = {},
            onStudyClick = {},
            onCardClick = {},
            onEditClick = {},
            onDeleteClick = {},
            onConfirmDelete = {},
            onDismissDelete = {},
            onToggleFollow = {},
            onCloneClick = {},
            onConfirmClone = {},
            onDismissClone = {},
            onDismissError = {},
            onRetry = {},
        )
    }
}

/** Matches AuthorRow's follow pill: dimmed while the write is in flight, not disabled-looking. */
private const val FOLLOW_PENDING_ALPHA = 0.5f
