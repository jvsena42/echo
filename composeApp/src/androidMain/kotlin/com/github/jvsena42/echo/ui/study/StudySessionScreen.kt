package com.github.jvsena42.echo.ui.study

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.domain.model.SrsGrade
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.presentation.study.StudySessionEffect
import com.github.jvsena42.echo.presentation.study.StudySessionUiState
import com.github.jvsena42.echo.presentation.study.StudySessionViewModel
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun StudySessionRoute(
    deckId: String?,
    onClose: () -> Unit = {},
) {
    val viewModel = koinInject<StudySessionViewModel> { parametersOf(deckId) }
    val speaker = koinInject<Speaker>()

    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentClose by rememberUpdatedState(onClose)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is StudySessionEffect.Speak -> speaker.speak(effect.text)
                StudySessionEffect.Close -> currentClose()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    StudySessionScreen(
        state = state,
        onReveal = viewModel::onReveal,
        onGrade = viewModel::onGrade,
        onSpeak = viewModel::onSpeak,
        onClose = viewModel::onClose,
        onDone = onClose,
    )
}

@Composable
fun StudySessionScreen(
    state: StudySessionUiState,
    onReveal: () -> Unit,
    onGrade: (SrsGrade) -> Unit,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceSecondary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        when (state) {
            StudySessionUiState.Loading -> CircularProgressIndicator(
                color = colors.accentPrimary,
                modifier = Modifier.align(Alignment.Center),
            )

            is StudySessionUiState.Error -> CenteredMessage(
                title = "Something went wrong",
                subtitle = state.message,
                actionLabel = "Close",
                onAction = onClose,
            )

            is StudySessionUiState.Empty -> CenteredMessage(
                title = "You're all caught up 🎉",
                subtitle = "No cards due${state.deckTitle.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""} right now.",
                actionLabel = "Done",
                onAction = onClose,
            )

            is StudySessionUiState.Complete -> CenteredMessage(
                title = "All done! 🎊",
                subtitle = "You reviewed ${state.reviewed} card${if (state.reviewed == 1) "" else "s"}.",
                actionLabel = "Back",
                onAction = onDone,
            )

            is StudySessionUiState.Reviewing -> ReviewingContent(
                state = state,
                onReveal = onReveal,
                onGrade = onGrade,
                onSpeak = onSpeak,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ReviewingContent(
    state: StudySessionUiState.Reviewing,
    onReveal: () -> Unit,
    onGrade: (SrsGrade) -> Unit,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        // Header: close · deck name + counter · spacer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onClose,
                modifier = Modifier
                    .testTag("study_close")
                    .size(40.dp),
                shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.deckTitle.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                Text(
                    text = "${state.position} of ${state.total}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                    color = colors.foregroundPrimary,
                )
            }
            Spacer(modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { state.position.toFloat() / state.total.coerceAtLeast(1) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = colors.accentPrimary,
            trackColor = colors.borderSubtle,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Card (tap front to reveal). Crossfade doubles as the Reduce-Motion-friendly flip.
        Box(
            modifier = Modifier
                .testTag("study_card")
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surfaceCard)
                .clickable(enabled = !state.revealed, onClick = onReveal)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = state.revealed, label = "cardFace") { revealed ->
                if (!revealed) {
                    CardFace(
                        label = "TAP TO REVEAL",
                        text = state.frontText,
                        textSize = 44.sp,
                        onSpeak = onSpeak,
                    )
                } else {
                    CardFace(
                        label = state.backLabel,
                        text = state.backText,
                        textSize = 38.sp,
                        onSpeak = onSpeak,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SRS grade row — only after reveal
        if (state.revealed) {
            SrsRow(intervals = state.intervals, onGrade = onGrade)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CardFace(
    label: String?,
    text: String,
    textSize: TextUnit,
    onSpeak: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        label?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.2.sp,
                color = colors.accentPrimary,
            )
        }
        Text(
            text = text,
            fontSize = textSize,
            fontWeight = FontWeight.W800,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = onSpeak,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.accentSecondarySoft,
                contentColor = colors.accentSecondary,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Speak",
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Speak",
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

@Composable
private fun SrsRow(
    intervals: Map<SrsGrade, String>,
    onGrade: (SrsGrade) -> Unit,
) {
    val colors = EchoTheme.colors
    val buttons = listOf(
        SrsGrade.Again to colors.srsAgain,
        SrsGrade.Hard to colors.srsHard,
        SrsGrade.Good to colors.srsGood,
        SrsGrade.Easy to colors.srsEasy,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { (grade, color) ->
            Button(
                onClick = { onGrade(grade) },
                modifier = Modifier
                    .testTag("study_${grade.name.lowercase()}")
                    .weight(1f)
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color,
                    contentColor = Color.White,
                ),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = grade.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W700,
                    )
                    Text(
                        text = intervals[grade] ?: "",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W500,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CenteredMessage(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.W800,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            fontSize = 15.sp,
            color = colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
            ),
        ) {
            Text(
                text = actionLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

@Preview
@Composable
private fun StudySessionScreenPreview() {
    EchoTheme {
        StudySessionScreen(
            state = StudySessionUiState.Reviewing(
                deckTitle = "Spanish basics",
                position = 3,
                total = 12,
                frontText = "hola",
                backText = "hello",
                backLabel = "MEANING",
                revealed = true,
                intervals = mapOf(
                    SrsGrade.Again to "1m",
                    SrsGrade.Hard to "10m",
                    SrsGrade.Good to "1d",
                    SrsGrade.Easy to "4d",
                ),
            ),
            onReveal = {},
            onGrade = {},
            onSpeak = {},
            onClose = {},
            onDone = {},
        )
    }
}
