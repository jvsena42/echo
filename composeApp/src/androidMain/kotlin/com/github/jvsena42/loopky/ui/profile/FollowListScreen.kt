package com.github.jvsena42.loopky.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.profile.FollowListUiState
import com.github.jvsena42.loopky.presentation.profile.FollowListViewModel
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FollowListRoute(
    pubky: String,
    source: FollowSource,
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<FollowListViewModel> { parametersOf(pubky, source) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    FollowListScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onOpenProfile = onOpenProfile,
    )
}

@Composable
private fun FollowListScreen(
    state: FollowListUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val errorReason = state.errorReason

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("follow_list_screen")
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onBack)
                .testTag("follow_list_back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.follow_list_back_content_description),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = stringResource(
                when (state.source) {
                    FollowSource.FOLLOWING -> R.string.follow_list_following_title
                    FollowSource.FOLLOWERS -> R.string.follow_list_followers_title
                },
            ),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize()) {
                LoopkyLoadingScreen(message = stringResource(R.string.follow_list_loading))
            }

            errorReason != null -> LoopkyErrorBlock(
                reason = errorReason,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )

            state.people.isEmpty() -> EmptyFollowList(source = state.source)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.people, key = { _, person -> person.pubky }) { index, person ->
                    AuthorRow(
                        identity = person,
                        onNameClick = { onOpenProfile(person.pubky) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("follow_row_$index")
                            .padding(vertical = 8.dp),
                    )
                }
                // The list is filtered to Loopky accounts, so it is routinely shorter than the
                // follow count another Pubky app shows for the same person. Said once, at the
                // bottom, rather than as a banner over a list that is usually unsurprising.
                item {
                    Text(
                        text = stringResource(R.string.follow_list_loopky_only),
                        fontSize = 12.sp,
                        color = colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFollowList(source: FollowSource) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("follow_list_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                when (source) {
                    FollowSource.FOLLOWING -> R.string.follow_list_empty_following
                    FollowSource.FOLLOWERS -> R.string.follow_list_empty_followers
                },
            ),
            fontSize = 14.sp,
            color = colors.foregroundMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Preview
@Composable
private fun FollowListScreenPreview() {
    LoopkyTheme {
        FollowListScreen(
            state = FollowListUiState(
                source = FollowSource.FOLLOWING,
                isLoading = false,
                people = listOf(
                    PubkyIdentity("abcdef1234567890abcdef", "Grace Hopper", null, null),
                    PubkyIdentity("zyxwvu0987654321zyxwvu", null, null, null),
                ),
            ),
            onBack = {},
            onRetry = {},
            onOpenProfile = {},
        )
    }
}
