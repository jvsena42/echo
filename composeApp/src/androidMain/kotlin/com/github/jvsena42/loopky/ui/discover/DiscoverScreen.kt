package com.github.jvsena42.loopky.ui.discover

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.DiscoverEffect
import com.github.jvsena42.loopky.presentation.discover.DiscoverUiState
import com.github.jvsena42.loopky.presentation.discover.DiscoverViewModel
import com.github.jvsena42.loopky.presentation.discover.SectionState
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DiscoverRoute(
    onOpenProfile: (String) -> Unit = {},
    onOpenDeck: (deckId: String, author: String?) -> Unit = { _, _ -> },
) {
    val viewModel = koinViewModel<DiscoverViewModel>()

    val context = LocalContext.current
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    var showAddFriend by remember { mutableStateOf(false) }
    var followError by remember { mutableStateOf<ErrorReason?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DiscoverEffect.OpenAddFriend -> showAddFriend = true
                is DiscoverEffect.OpenProfile -> currentOpenProfile(effect.pubky)
                is DiscoverEffect.OpenDeck -> currentOpenDeck(effect.deckId, effect.authorPubky)
                is DiscoverEffect.ShowFollowError -> followError = effect.reason
            }
        }
    }

    // Resolved here rather than in the effect collector: errorMessage is @Composable.
    followError?.let { reason ->
        val message = errorMessage(reason)
        LaunchedEffect(reason, message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            followError = null
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscoverScreen(
        state = state,
        onTagSelected = viewModel::onTagSelected,
        onAddFriend = viewModel::onAddFriend,
        onOpenAuthor = viewModel::onOpenAuthor,
        onOpenDeck = viewModel::onOpenDeck,
        onFollowToggle = viewModel::onFollowToggle,
        onRefresh = viewModel::onRefresh,
        onRetryFollowing = viewModel::onRetryFollowing,
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
    onFollowToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetryFollowing: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("discover_screen")
            .background(LoopkyTheme.colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // A LazyColumn rather than a scrolling Column: strips settle at different times, and
            // item keys keep one landing from recomposing the others.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "header") { DiscoverHeader(onAddFriend = onAddFriend) }

                topicsSection(state, onTagSelected)
                peopleSection(state, onOpenAuthor, onFollowToggle)
                browseSection(state, onTagSelected, onOpenDeck, onOpenAuthor, onAddFriend)
                followingSection(state, onOpenDeck, onOpenAuthor, onRetryFollowing)
            }
        }
    }
}

private fun LazyListScope.topicsSection(
    state: DiscoverUiState,
    onTagSelected: (Tag?) -> Unit,
) {
    // No placeholder when there are no topics — an absent chip row reads as "nothing to filter by",
    // which is exactly right, and an empty-state block for it would be noise.
    if (state.topics.items.isEmpty()) return
    item(key = "topics") {
        TopicRow(
            tags = state.topics.items,
            selectedTag = state.selectedTag,
            onTagSelected = onTagSelected,
        )
    }
}

private fun LazyListScope.peopleSection(
    state: DiscoverUiState,
    onOpenAuthor: (String) -> Unit,
    onFollowToggle: (String) -> Unit,
) {
    // Hidden entirely once it settles empty: an empty people strip on a young network is not
    // information, and the browse strip below is the better thing to be looking at.
    if (state.people.isEmpty) return
    item(key = "people_header") {
        SectionHeader(text = stringResource(R.string.discover_people_title))
    }
    if (state.people.isLoading) {
        item(key = "people_loading") {
            SectionSpinner(modifier = Modifier.testTag("discover_people_loading"))
        }
        return
    }
    item(key = "people") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("discover_people_row")
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.people.items.forEach { person ->
                PersonTile(
                    person = person,
                    onOpenProfile = { onOpenAuthor(person.identity.pubky) },
                    onFollowToggle = { onFollowToggle(person.identity.pubky) },
                )
            }
        }
    }
}

private fun LazyListScope.browseSection(
    state: DiscoverUiState,
    onTagSelected: (Tag?) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onAddFriend: () -> Unit,
) {
    item(key = "browse_header") {
        SectionHeader(
            text = state.selectedTag
                ?.let { stringResource(R.string.discover_browse_tag_title, it.value) }
                ?: stringResource(R.string.discover_browse_title),
            trailing = state.selectedTag?.let { { ClearTagButton(onClick = { onTagSelected(null) }) } },
        )
    }
    if (state.browse.isLoading) {
        item(key = "browse_loading") { SectionSpinner(modifier = Modifier.testTag("discover_browse_loading")) }
    }
    if (state.browse.isEmpty) {
        item(key = "browse_empty") {
            BrowseEmptyBlock(selectedTag = state.selectedTag, onAddFriend = onAddFriend)
        }
    }
    deckRows(
        section = state.browse,
        keyPrefix = "browse",
        tileTestTag = "discover_deck_tile",
        onOpenDeck = onOpenDeck,
        onOpenAuthor = onOpenAuthor,
    )
}

private fun LazyListScope.followingSection(
    state: DiscoverUiState,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onRetryFollowing: () -> Unit,
) {
    // Silent while it is empty: someone who follows nobody should see browse, not a reminder that
    // they follow nobody. It only appears once it has decks, or something to report.
    if (state.following.items.isEmpty() && state.following.error == null) return

    item(key = "following_header") {
        SectionHeader(
            text = stringResource(R.string.discover_following_title),
            modifier = Modifier.testTag("discover_following_section"),
        )
    }
    state.following.error?.let { reason ->
        item(key = "following_error") {
            LoopkyErrorBlock(reason = reason, onRetry = onRetryFollowing)
        }
    }
    deckRows(
        section = state.following,
        keyPrefix = "following",
        tileTestTag = "discover_following_tile",
        onOpenDeck = onOpenDeck,
        onOpenAuthor = onOpenAuthor,
    )
}

private fun LazyListScope.deckRows(
    section: SectionState<DiscoverDeck>,
    keyPrefix: String,
    tileTestTag: String,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val rows = section.items.chunked(2)
    items(
        items = rows,
        key = { row -> keyPrefix + ":" + row.joinToString(",") { "${it.authorPubky}/${it.id}" } },
    ) { row ->
        DeckRow(
            decks = row,
            onOpenDeck = onOpenDeck,
            onOpenAuthor = onOpenAuthor,
            tileTestTag = tileTestTag,
        )
    }
}

/** Drops the topic filter and goes back to browsing everything. */
@Composable
private fun ClearTagButton(onClick: () -> Unit) {
    val pillShape = RoundedCornerShape(50)
    Text(
        text = stringResource(R.string.discover_clear_tag),
        color = LoopkyTheme.colors.accentPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.W700,
        modifier = Modifier
            .testTag("discover_clear_tag")
            .clip(pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun DiscoverHeader(onAddFriend: () -> Unit) {
    val colors = LoopkyTheme.colors
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriendSheet(
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
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
                // ModalBottomSheet renders in its own window, so the flag set on the nav-host
                // root does not reach it: without this the sheet's controls expose no ids at
                // all and journeys/04 cannot target them.
                .semantics { testTagsAsResourceId = true }
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
                    .testTag("add_friend_input")
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
                            Text(
                                text = stringResource(R.string.discover_paste_pubky),
                                fontSize = 15.sp,
                                color = colors.foregroundMuted,
                            )
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
                        .testTag("add_friend_scan")
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
                        .testTag("add_friend_open")
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

private fun previewDeck(id: String, title: String, emoji: String, author: String, name: String) =
    DiscoverDeck(
        id = id,
        authorPubky = author,
        title = title,
        cardCount = 24,
        coverEmoji = emoji,
        author = PubkyIdentity(author, name, null, null),
        tags = listOf("spanish"),
    )

@Preview
@Composable
private fun DiscoverScreenPreview() {
    LoopkyTheme {
        DiscoverScreen(
            state = DiscoverUiState(
                topics = SectionState(items = listOf(Tag("spanish"), Tag("biology"))),
                browse = SectionState(
                    items = listOf(
                        previewDeck("1", "Spanish basics", "📚", "abc123def456ghi", "Ada"),
                        previewDeck("2", "Biology 101", "🧬", "def456ghi789jkl", "Grace"),
                    ),
                ),
                following = SectionState(
                    items = listOf(previewDeck("3", "Chess openings", "♟️", "ghi789jkl012mno", "Alan")),
                ),
            ),
            onTagSelected = {},
            onAddFriend = {},
            onOpenAuthor = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
            onRefresh = {},
            onRetryFollowing = {},
        )
    }
}

/** The state a brand-new account lands on before anything has been published network-wide. */
@Preview
@Composable
private fun DiscoverScreenEmptyBrowsePreview() {
    LoopkyTheme {
        DiscoverScreen(
            state = DiscoverUiState(),
            onTagSelected = {},
            onAddFriend = {},
            onOpenAuthor = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
            onRefresh = {},
            onRetryFollowing = {},
        )
    }
}
