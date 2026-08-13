package com.github.jvsena42.echo.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.domain.model.Tag
import com.github.jvsena42.echo.presentation.discover.DiscoverDeck
import com.github.jvsena42.echo.presentation.discover.DiscoverEffect
import com.github.jvsena42.echo.presentation.discover.DiscoverUiState
import com.github.jvsena42.echo.presentation.discover.DiscoverViewModel
import com.github.jvsena42.echo.ui.components.DeckTile
import com.github.jvsena42.echo.ui.components.EchoErrorBlock
import com.github.jvsena42.echo.ui.components.EchoLoadingScreen
import com.github.jvsena42.echo.ui.components.TagChip
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DiscoverRoute(
    onOpenProfile: (String) -> Unit = {},
    onOpenDeck: (deckId: String, author: String?) -> Unit = { _, _ -> },
) {
    val viewModel = koinViewModel<DiscoverViewModel>()

    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    var showAddFriend by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DiscoverEffect.OpenAddFriend -> showAddFriend = true
                is DiscoverEffect.OpenProfile -> currentOpenProfile(effect.pubky)
                is DiscoverEffect.OpenDeck -> currentOpenDeck(effect.deckId, effect.authorPubky)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscoverScreen(
        state = state,
        onTagSelected = viewModel::onTagSelected,
        onAddFriend = viewModel::onAddFriend,
        onOpenAuthor = viewModel::onOpenAuthor,
        onOpenDeck = viewModel::onOpenDeck,
        onRetry = viewModel::onRefresh,
    )

    if (showAddFriend) {
        AddFriendSheet(
            onDismiss = { showAddFriend = false },
            onOpenProfile = { pubky ->
                showAddFriend = false
                currentOpenProfile(pubky)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverScreen(
    state: DiscoverUiState,
    onTagSelected: (Tag?) -> Unit,
    onAddFriend: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        if (state is DiscoverUiState.Loading) {
            EchoLoadingScreen(message = stringResource(R.string.discover_loading))
        } else {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = onRetry,
                modifier = Modifier.fillMaxSize(),
            ) {
                DiscoverScreenContent(
                    state = state,
                    onTagSelected = onTagSelected,
                    onAddFriend = onAddFriend,
                    onOpenAuthor = onOpenAuthor,
                    onOpenDeck = onOpenDeck,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun DiscoverScreenContent(
    state: DiscoverUiState,
    onTagSelected: (Tag?) -> Unit,
    onAddFriend: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderRow(onAddFriend = onAddFriend)

        when (state) {
            DiscoverUiState.Loading -> Unit
            DiscoverUiState.Empty -> EmptyBlock(onAddFriend = onAddFriend)
            is DiscoverUiState.Content -> {
                if (state.tags.isNotEmpty()) {
                    TagRow(
                        tags = state.tags,
                        selectedTag = state.selectedTag,
                        onTagSelected = onTagSelected,
                    )
                }
                if (state.trendingTags.isNotEmpty()) {
                    TrendingSection(
                        tags = state.trendingTags,
                        selectedTag = state.selectedTag,
                        onTagSelected = onTagSelected,
                    )
                }
                val selected = state.selectedTag
                if (state.decks.isEmpty() && selected != null) {
                    EmptyTagBlock(tag = selected)
                } else {
                    DeckGrid(
                        decks = state.decks,
                        onOpenDeck = onOpenDeck,
                        onOpenAuthor = onOpenAuthor,
                    )
                }
            }
            is DiscoverUiState.Error -> EchoErrorBlock(reason = state.reason, onRetry = onRetry)
        }
    }
}

@Composable
private fun HeaderRow(onAddFriend: () -> Unit) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.discover_title),
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            modifier = Modifier
                .testTag("discover_add_friend")
                .clip(RoundedCornerShape(50))
                .background(colors.accentSecondarySoft)
                .clickable(onClick = onAddFriend)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = colors.accentSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.discover_add_friend),
                color = colors.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

@Composable
private fun TagRow(
    tags: List<Tag>,
    selectedTag: Tag?,
    onTagSelected: (Tag?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagChip(
                tag = tag.value,
                selected = tag == selectedTag,
                onClick = { onTagSelected(tag) },
            )
        }
    }
}

@Composable
private fun TrendingSection(
    tags: List<Tag>,
    selectedTag: Tag?,
    onTagSelected: (Tag?) -> Unit,
) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.discover_trending),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
        )
        TagRow(
            tags = tags,
            selectedTag = selectedTag,
            onTagSelected = onTagSelected,
        )
    }
}

@Composable
private fun EmptyTagBlock(tag: Tag) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
    ) {
        Text(text = stringResource(R.string.discover_empty_tag_emoji), fontSize = 36.sp)
        Text(
            text = stringResource(R.string.discover_empty_tag_title, tag.value),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.discover_empty_tag_subtitle),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DeckGrid(
    decks: List<DiscoverDeck>,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
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
                        onClick = { onOpenDeck(deck.authorPubky, deck.id) },
                        onAuthorClick = { onOpenAuthor(deck.authorPubky) },
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

@Composable
private fun EmptyBlock(onAddFriend: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
    ) {
        Text(text = stringResource(R.string.discover_empty_emoji), fontSize = 48.sp)
        Text(
            text = stringResource(R.string.discover_empty_title),
            color = colors.foregroundPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(R.string.discover_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accentSecondary)
                .clickable(onClick = onAddFriend)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.discover_add_a_friend),
                color = colors.foregroundOnAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
    ) {
        Text(
            text = stringResource(R.string.discover_error_title),
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(text = message, color = colors.foregroundMuted, fontSize = 14.sp)
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.discover_retry), color = colors.accentPrimary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriendSheet(
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = EchoTheme.colors
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    fun submit(raw: String) {
        val pubky = raw.trim().removePrefix("pubky://").substringBefore('/').trim()
        if (pubky.isNotEmpty()) onOpenProfile(pubky)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.discover_add_a_friend),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.discover_add_friend_sheet_subtitle),
                fontSize = 13.sp,
                color = colors.foregroundMuted,
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                    .background(colors.surfacePrimary)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = colors.foregroundPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(stringResource(R.string.discover_paste_pubky), fontSize = 15.sp, color = colors.foregroundMuted)
                        }
                        inner()
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(50))
                        .clickable { scanPubky(context) { scanned -> submit(scanned) } }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.discover_scan_qr),
                        color = colors.foregroundSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(colors.accentSecondary)
                        .clickable { submit(input) }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.discover_open),
                        color = colors.foregroundOnAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun DiscoverScreenPreview() {
    EchoTheme {
        Box(modifier = Modifier.background(EchoTheme.colors.surfacePrimary)) {
            DiscoverScreenContent(
                state = DiscoverUiState.Content(
                    tags = listOf(Tag("language"), Tag("science")),
                    trendingTags = listOf(Tag("travel")),
                    selectedTag = Tag("language"),
                    decks = listOf(
                        DiscoverDeck(
                            id = "1",
                            authorPubky = "abc",
                            title = "Spanish basics",
                            cardCount = 24,
                            coverEmoji = "📚",
                            authorLabel = "Ada",
                            tags = listOf("language"),
                        ),
                        DiscoverDeck(
                            id = "2",
                            authorPubky = "def",
                            title = "Biology 101",
                            cardCount = 40,
                            coverEmoji = "🧬",
                            authorLabel = "Grace",
                            tags = listOf("science"),
                        ),
                    ),
                ),
                onTagSelected = {},
                onAddFriend = {},
                onOpenAuthor = {},
                onOpenDeck = { _, _ -> },
                onRetry = {},
            )
        }
    }
}
