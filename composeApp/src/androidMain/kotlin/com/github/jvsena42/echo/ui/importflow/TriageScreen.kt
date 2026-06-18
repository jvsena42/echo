package com.github.jvsena42.echo.ui.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.presentation.importflow.TriageCard
import com.github.jvsena42.echo.presentation.importflow.TriageEffect
import com.github.jvsena42.echo.presentation.importflow.TriageUiState
import com.github.jvsena42.echo.presentation.importflow.TriageViewModel
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun TriageRoute(
    onBack: () -> Unit = {},
    onEditCard: (Int) -> Unit = {},
    onNext: () -> Unit = {},
) {
    val viewModel = koinInject<TriageViewModel>()
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    // Re-read the draft each time this screen resumes (e.g. after editing a card).
    LaunchedEffect(viewModel) { viewModel.refresh() }

    val currentBack by rememberUpdatedState(onBack)
    val currentEdit by rememberUpdatedState(onEditCard)
    val currentNext by rememberUpdatedState(onNext)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                TriageEffect.NavigateBack -> currentBack()
                is TriageEffect.NavigateEditCard -> currentEdit(effect.rowIndex)
                TriageEffect.NavigatePublish -> currentNext()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    TriageScreen(
        state = state,
        onBackClick = viewModel::onBackClick,
        onApproveAll = viewModel::onApproveAll,
        onDiscard = viewModel::onDiscard,
        onEditClick = viewModel::onEditClick,
        onKeep = viewModel::onKeep,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriageScreen(
    state: TriageUiState,
    onBackClick: () -> Unit,
    onApproveAll: () -> Unit,
    onDiscard: () -> Unit,
    onEditClick: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfaceSecondary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.triage_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.publish_back),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onApproveAll,
                        modifier = Modifier.testTag("triage_approve_all"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(
                            text = stringResource(R.string.triage_approve_all),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfaceSecondary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Progress + stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.triage_progress, state.currentIndex + 1, state.total),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.foregroundMuted,
                    modifier = Modifier.testTag("triage_progress"),
                )
                Text(
                    text = stringResource(R.string.triage_stats, state.keptCount, state.discardedCount),
                    fontSize = 12.sp,
                    color = colors.foregroundMuted,
                )
            }

            val card = state.currentCard
            if (card != null) {
                TriageCardView(card = card, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }

            state.error?.let { Text(it, fontSize = 13.sp, color = colors.danger) }

            // Action buttons: discard / edit / keep
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleActionButton(
                    onClick = onDiscard,
                    tag = "triage_discard",
                    background = colors.dangerSoft,
                    iconTint = colors.srsAgain,
                    size = 56,
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.triage_discard), modifier = Modifier.size(28.dp))
                }
                CircleActionButton(
                    onClick = onEditClick,
                    tag = "triage_edit",
                    background = colors.surfaceCard,
                    iconTint = colors.foregroundSecondary,
                    size = 48,
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.triage_edit), modifier = Modifier.size(20.dp))
                }
                CircleActionButton(
                    onClick = onKeep,
                    tag = "triage_keep",
                    background = colors.srsGood,
                    iconTint = colors.foregroundOnAccent,
                    size = 56,
                ) {
                    Icon(Icons.Default.Check, stringResource(R.string.triage_keep), modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun TriageCardView(card: TriageCard, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceCard)
            .testTag("triage_card")
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.triage_front_label),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.foregroundMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.front.ifBlank { "—" },
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("triage_front"),
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(40.dp).height(2.dp).background(colors.accentPrimary))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.triage_back_label),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.foregroundMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.back.ifBlank { "—" },
            fontSize = 20.sp,
            color = colors.foregroundSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("triage_back"),
        )
    }
}

@Composable
private fun CircleActionButton(
    onClick: () -> Unit,
    tag: String,
    background: Color,
    iconTint: Color,
    size: Int,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .testTag(tag),
        colors = IconButtonDefaults.iconButtonColors(contentColor = iconTint),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun TriageScreenPreview() {
    EchoTheme {
        TriageScreen(
            state = TriageUiState(
                cards = listOf(TriageCard(0, "por favor", "please")),
                currentIndex = 11,
                total = 42,
                keptCount = 11,
                discardedCount = 0,
            ),
            onBackClick = {},
            onApproveAll = {},
            onDiscard = {},
            onEditClick = {},
            onKeep = {},
        )
    }
}
