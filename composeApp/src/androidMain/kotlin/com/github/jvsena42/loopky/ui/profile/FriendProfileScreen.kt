package com.github.jvsena42.loopky.ui.profile

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.presentation.profile.FriendDeck
import com.github.jvsena42.loopky.presentation.profile.FriendProfileEffect
import com.github.jvsena42.loopky.presentation.profile.FriendProfileUiState
import com.github.jvsena42.loopky.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.loopky.ui.components.DeckTile
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.ProfileHero
import com.github.jvsena42.loopky.ui.components.ProfileStat
import com.github.jvsena42.loopky.ui.components.ProfileStatsCard
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.shareText
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FriendProfileRoute(
    pubky: String,
    onBack: () -> Unit = {},
    onOpenDeck: (String) -> Unit = {},
    onOpenFollows: (pubky: String, source: FollowSource) -> Unit = { _, _ -> },
) {
    val viewModel = koinViewModel<FriendProfileViewModel> { parametersOf(pubky) }

    val context = LocalContext.current
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    val currentOpenFollows by rememberUpdatedState(onOpenFollows)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is FriendProfileEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
                is FriendProfileEffect.OpenDeck -> currentOpenDeck(effect.deckId)
                is FriendProfileEffect.ShareProfile -> context.shareText(
                    // Named, not a bare key: a recipient sees who it is before tapping.
                    text = context.getString(
                        R.string.share_profile_body,
                        effect.identity.label(context),
                        effect.uri,
                    ),
                    chooserTitle = context.getString(R.string.share_profile_chooser_title),
                )
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    FriendProfileScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::onRefresh,
        onToggleFollow = viewModel::onToggleFollow,
        onCopyPubky = viewModel::onCopyPubky,
        onShare = viewModel::onShareClick,
        onOpenFollows = { source -> currentOpenFollows(state.identity.pubky, source) },
        onOpenDeck = viewModel::onOpenDeck,
    )
}

@Composable
private fun FriendProfileScreen(
    state: FriendProfileUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFollow: () -> Unit,
    onCopyPubky: () -> Unit,
    onShare: () -> Unit,
    onOpenFollows: (FollowSource) -> Unit,
    onOpenDeck: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors

    if (state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.surfacePrimary),
        ) {
            LoopkyLoadingScreen(message = stringResource(R.string.profile_loading))
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .testTag("friend_profile_screen")
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Back row
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceCard)
                    .clickable(onClick = onBack)
                    .testTag("friend_profile_back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.friend_profile_back_content_description),
                    tint = colors.foregroundPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Identity — the same hero the signed-in user's own profile uses, so a person reads
            // as a person here rather than as a list row.
            ProfileHero(
                identity = state.identity,
                isOwned = state.isSelf,
                onPubkyClick = onCopyPubky,
                modifier = Modifier.fillMaxWidth(),
            )

            // Following yourself is not a thing, and editing lives on the Profile tab — but
            // copying and sharing still are, so the row stays and only loses the Follow button.
            // The hero's "You" badge already says whose profile this is.
            ProfileActionRow(
                isSelf = state.isSelf,
                isFollowing = state.isFollowing,
                isProcessingFollow = state.isProcessingFollow,
                onToggleFollow = onToggleFollow,
                onCopyPubky = onCopyPubky,
                onShare = onShare,
            )

            state.errorReason?.let { reason ->
                Text(
                    text = errorMessage(reason),
                    color = colors.danger,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("friend_profile_error"),
                )
            }

            // Two stats, not three: "due" is a fact about the signed-in user's review queue, so
            // it says nothing about the person being looked at.
            ProfileStatsCard(
                stats = listOf(
                    ProfileStat(
                        value = state.deckCount.toString(),
                        label = stringResource(R.string.profile_stat_decks),
                        valueColor = colors.foregroundPrimary,
                    ),
                    ProfileStat(
                        value = state.cardCount.toString(),
                        label = stringResource(R.string.profile_stat_cards),
                        valueColor = colors.accentPrimary,
                    ),
                ),
                modifier = Modifier.testTag("friend_profile_stats"),
            )

            // People — the same second strip the signed-in user's own profile carries, and the
            // way into someone else's graph: their follows are how you find the next person.
            val pending = stringResource(R.string.profile_stat_pending)
            ProfileStatsCard(
                stats = listOf(
                    ProfileStat(
                        value = state.followingCount?.toString() ?: pending,
                        label = stringResource(R.string.profile_stat_following),
                        valueColor = colors.foregroundPrimary,
                        onClick = { onOpenFollows(FollowSource.FOLLOWING) },
                        testTag = "friend_profile_stat_following",
                    ),
                    ProfileStat(
                        value = state.followerCount?.toString() ?: pending,
                        label = stringResource(R.string.profile_stat_followers),
                        valueColor = colors.accentPrimary,
                        onClick = { onOpenFollows(FollowSource.FOLLOWERS) },
                        testTag = "friend_profile_stat_followers",
                    ),
                ),
            )

            // Decks
            Text(
                text = pluralStringResource(R.plurals.public_decks_count, state.decks.size, state.decks.size),
                color = colors.foregroundPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
            )
            if (state.decks.isEmpty()) {
                Text(
                    text = stringResource(R.string.friend_profile_no_public_decks),
                    color = colors.foregroundMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            } else {
                DeckGrid(decks = state.decks, onOpenDeck = onOpenDeck)
            }
        }
    }
}

/**
 * Follow as the screen's primary action, mirroring "Edit Profile" on the owner's profile.
 * Following is a settled state rather than a call to action, so it steps down to the soft
 * secondary button — the same solid/soft split the compact `AuthorRow` pill uses.
 *
 * Copy and Share sit beside it as the two ways to pass this person on: copy for a bare key, share
 * for a `pubky://` address a recipient can tap straight back into Loopky.
 */
@Composable
private fun ProfileActionRow(
    isSelf: Boolean,
    isFollowing: Boolean,
    isProcessingFollow: Boolean,
    onToggleFollow: () -> Unit,
    onCopyPubky: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier.fillMaxWidth(),
        // Centered so the two icon buttons do not hang off the left edge on your own profile,
        // where there is no Follow button to fill the row.
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Following yourself is not a thing, so your own profile shows the icons alone.
        if (!isSelf) {
            if (isFollowing) {
                LoopkySecondaryButton(
                    text = stringResource(R.string.component_author_row_following),
                    onClick = onToggleFollow,
                    icon = Icons.Filled.Check,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("friend_profile_follow"),
                )
            } else {
                LoopkyPrimaryButton(
                    label = stringResource(R.string.component_author_row_follow),
                    onClick = onToggleFollow,
                    loading = isProcessingFollow,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.PersonAdd,
                            contentDescription = null,
                            tint = colors.foregroundOnAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("friend_profile_follow"),
                )
            }
        }

        OutlinedIconButton(
            onClick = onCopyPubky,
            modifier = Modifier
                .size(48.dp)
                .testTag("friend_profile_copy"),
            shape = CircleShape,
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = colors.surfaceCard,
                contentColor = colors.foregroundSecondary,
            ),
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.friend_profile_copy_pubky),
                modifier = Modifier.size(18.dp),
            )
        }

        OutlinedIconButton(
            onClick = onShare,
            modifier = Modifier
                .size(48.dp)
                .testTag("friend_profile_share"),
            shape = CircleShape,
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = colors.surfaceCard,
                contentColor = colors.foregroundSecondary,
            ),
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.friend_profile_share),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DeckGrid(decks: List<FriendDeck>, onOpenDeck: (String) -> Unit) {
    val rows = decks.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { deck ->
                    DeckTile(
                        deckId = deck.id,
                        authorPubky = deck.authorPubky,
                        title = deck.title,
                        cardCount = deck.cardCount,
                        coverEmoji = deck.coverEmoji,
                        coverImage = deck.coverImage,
                        authorLabel = deck.tags.firstOrNull()?.let { "#$it" } ?: "",
                        onClick = { onOpenDeck(deck.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("friend_profile_deck_tile"),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Preview
@Composable
private fun FriendProfileScreenPreview() {
    LoopkyTheme {
        FriendProfileScreen(
            state = previewState(isFollowing = false),
            onBack = {},
            onRefresh = {},
            onToggleFollow = {},
            onCopyPubky = {},
            onShare = {},
            onOpenFollows = {},
            onOpenDeck = {},
        )
    }
}

@Preview
@Composable
private fun FriendProfileScreenFollowingPreview() {
    LoopkyTheme {
        FriendProfileScreen(
            state = previewState(isFollowing = true),
            onBack = {},
            onRefresh = {},
            onToggleFollow = {},
            onCopyPubky = {},
            onShare = {},
            onOpenFollows = {},
            onOpenDeck = {},
        )
    }
}

@Preview
@Composable
private fun FriendProfileScreenSelfPreview() {
    LoopkyTheme {
        FriendProfileScreen(
            state = previewState(isFollowing = false).copy(isSelf = true),
            onBack = {},
            onRefresh = {},
            onToggleFollow = {},
            onCopyPubky = {},
            onShare = {},
            onOpenFollows = {},
            onOpenDeck = {},
        )
    }
}

private fun previewState(isFollowing: Boolean) = FriendProfileUiState(
    isLoading = false,
    identity = PubkyIdentity(
        pubky = "abcdef1234567890abcdef",
        displayName = "Grace Hopper",
        avatarUrl = null,
        bio = "Compiler pioneer. Decks on debugging and history.",
    ),
    isFollowing = isFollowing,
    decks = listOf(
        FriendDeck(
            id = "1",
            authorPubky = "grace1xqz9",
            title = "Debugging 101",
            cardCount = 18,
            coverEmoji = "🐛",
            tags = listOf("engineering"),
        ),
        FriendDeck(
            id = "2",
            authorPubky = "grace1xqz9",
            title = "Naval history",
            cardCount = 30,
            coverEmoji = "⚓",
            tags = listOf("history"),
        ),
    ),
    deckCount = 2,
    cardCount = 48,
)
