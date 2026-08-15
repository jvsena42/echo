package com.github.jvsena42.loopky.ui.profile

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.github.jvsena42.loopky.presentation.profile.FriendDeck
import com.github.jvsena42.loopky.presentation.profile.FriendProfileEffect
import com.github.jvsena42.loopky.presentation.profile.FriendProfileUiState
import com.github.jvsena42.loopky.presentation.profile.FriendProfileViewModel
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.DeckTile
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FriendProfileRoute(
    pubky: String,
    onBack: () -> Unit = {},
    onOpenDeck: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<FriendProfileViewModel> { parametersOf(pubky) }

    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is FriendProfileEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
                is FriendProfileEffect.OpenDeck -> currentOpenDeck(effect.deckId)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    FriendProfileScreen(
        state = state,
        onBack = onBack,
        onToggleFollow = viewModel::onToggleFollow,
        onCopyPubky = viewModel::onCopyPubky,
        onOpenDeck = viewModel::onOpenDeck,
    )
}

@Composable
private fun FriendProfileScreen(
    state: FriendProfileUiState,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onCopyPubky: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Back row
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.friend_profile_back_content_description),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Header: avatar + name + pubky + follow pill (reused AuthorRow)
        AuthorRow(
            identity = state.identity,
            isOwned = state.isSelf,
            isFollowing = state.isFollowing,
            isFollowPending = state.isProcessingFollow,
            onFollowClick = onToggleFollow,
            modifier = Modifier.fillMaxWidth(),
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

        // Copyable full pubky
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceSecondary)
                .clickable(onClick = onCopyPubky)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.friend_profile_copy_pubky),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundMuted,
            )
        }

        val bio = state.identity.bio
        if (!bio.isNullOrBlank()) {
            Text(
                text = bio,
                fontSize = 13.sp,
                color = colors.foregroundSecondary,
                lineHeight = 19.sp,
            )
        }

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
                        title = deck.title,
                        cardCount = deck.cardCount,
                        coverEmoji = deck.coverEmoji,
                        authorLabel = deck.tags.firstOrNull()?.let { "#$it" } ?: "",
                        onClick = { onOpenDeck(deck.id) },
                        modifier = Modifier.weight(1f),
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
            state = FriendProfileUiState(
                isLoading = false,
                identity = PubkyIdentity(
                    pubky = "abcdef1234567890abcdef",
                    displayName = "Grace Hopper",
                    avatarUrl = null,
                    bio = "Compiler pioneer. Decks on debugging and history.",
                ),
                isFollowing = true,
                decks = listOf(
                    FriendDeck(
                        id = "1",
                        title = "Debugging 101",
                        cardCount = 18,
                        coverEmoji = "🐛",
                        tags = listOf("engineering"),
                    ),
                    FriendDeck(
                        id = "2",
                        title = "Naval history",
                        cardCount = 30,
                        coverEmoji = "⚓",
                        tags = listOf("history"),
                    ),
                ),
            ),
            onBack = {},
            onToggleFollow = {},
            onCopyPubky = {},
            onOpenDeck = {},
        )
    }
}
