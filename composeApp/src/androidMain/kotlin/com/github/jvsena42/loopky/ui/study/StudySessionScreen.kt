package com.github.jvsena42.loopky.ui.study

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.platform.SpeakOutcome
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.platform.SpeechEvent
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.presentation.study.GoalCelebration
import com.github.jvsena42.loopky.presentation.study.SpeakPhase
import com.github.jvsena42.loopky.presentation.study.StudySessionEffect
import com.github.jvsena42.loopky.presentation.study.StudySessionUiState
import com.github.jvsena42.loopky.presentation.study.StudySessionViewModel
import com.github.jvsena42.loopky.ui.components.Confetti
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.PermissionBlockedDialog
import com.github.jvsena42.loopky.ui.components.PermissionRationaleDialog
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.components.rememberReduceMotion
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.relativeFromNow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun StudySessionRoute(
    deckId: String?,
    onClose: () -> Unit = {},
) {
    val viewModel = koinViewModel<StudySessionViewModel> { parametersOf(deckId) }
    val speaker = koinInject<Speaker>()
    val speechRecognizer = koinInject<SpeechRecognizer>()

    val currentClose by rememberUpdatedState(onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognitionJob = remember { mutableStateOf<Job?>(null) }

    val requestSpeak = rememberMicPermissionRequest(
        onGranted = viewModel::onSpeakTest,
        onDenied = viewModel::onSpeechError,
    )

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is StudySessionEffect.Speak -> {
                    // A missing voice leaves the engine on whatever it loaded last, so silence
                    // beats reading a Spanish card in an English accent — say why.
                    if (speaker.speak(effect.text, effect.languageTag) != SpeakOutcome.Spoken) {
                        Toast.makeText(context, R.string.listen_voice_unavailable, Toast.LENGTH_LONG)
                            .show()
                    }
                }
                is StudySessionEffect.StartSpeechRecognition -> {
                    recognitionJob.value?.cancel()
                    recognitionJob.value = scope.launch {
                        if (!speechRecognizer.isAvailable()) {
                            Toast.makeText(context, R.string.speak_unavailable, Toast.LENGTH_LONG).show()
                            viewModel.onSpeechError()
                            return@launch
                        }
                        speechRecognizer.listen(effect.languageTag).collect { event ->
                            when (event) {
                                is SpeechEvent.Result -> viewModel.onSpeechResult(event.text)
                                is SpeechEvent.Error -> viewModel.onSpeechError()
                                else -> Unit
                            }
                        }
                    }
                }
                StudySessionEffect.Close -> currentClose()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // Stop the recognizer as soon as the speak sheet leaves the listening phase.
    val listening = (state as? StudySessionUiState.Reviewing)?.speakPhase is SpeakPhase.Listening
    LaunchedEffect(listening) {
        if (!listening) recognitionJob.value?.cancel()
    }

    StudySessionScreen(
        state = state,
        onReveal = viewModel::onReveal,
        onGrade = viewModel::onGrade,
        onSpeak = viewModel::onSpeak,
        onSpeakTest = requestSpeak,
        onSpeakContinue = viewModel::onSpeakDismiss,
        onSpeakRetry = viewModel::onSpeakRetry,
        onSpeakCancel = viewModel::onSpeakDismiss,
        onClose = viewModel::onClose,
        onDone = onClose,
        onDismissSyncError = viewModel::onDismissSyncError,
        onContinueAfterGoal = viewModel::onContinueAfterGoal,
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
    onSpeakTest: () -> Unit = {},
    onSpeakContinue: () -> Unit = {},
    onSpeakRetry: () -> Unit = {},
    onSpeakCancel: () -> Unit = {},
    onDismissSyncError: () -> Unit = {},
    onContinueAfterGoal: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceSecondary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        when (state) {
            StudySessionUiState.Loading -> LoopkyLoadingScreen(
                message = stringResource(R.string.study_loading),
            )

            is StudySessionUiState.Error -> CenteredMessage(
                title = errorTitle(state.reason),
                subtitle = errorMessage(state.reason),
                actionLabel = stringResource(R.string.study_close),
                onAction = onClose,
            )

            is StudySessionUiState.Empty -> CenteredMessage(
                title = stringResource(R.string.study_empty_title),
                subtitle = state.deckTitle.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.study_empty_subtitle_deck, it) }
                    ?: stringResource(R.string.study_empty_subtitle),
                actionLabel = stringResource(R.string.study_done),
                onAction = onClose,
            )

            is StudySessionUiState.Complete -> CenteredMessage(
                title = stringResource(R.string.study_complete_title),
                subtitle = pluralStringResource(R.plurals.cards_reviewed, state.reviewed, state.reviewed),
                actionLabel = stringResource(R.string.study_back),
                onAction = onDone,
                details = listOfNotNull(
                    // Saying when the next review lands is what makes an empty queue read as
                    // earned rather than as a dead end (#101 §5).
                    state.nextDueAtMillis
                        ?.let { stringResource(R.string.home_caught_up_next_due, relativeFromNow(it)) },
                    // The day's tally, which is the number that actually accumulates.
                    if (state.newCardsToday >= state.newCardsGoal) {
                        stringResource(R.string.home_new_cards_goal_reached, state.newCardsGoal)
                    } else {
                        stringResource(R.string.home_new_cards_goal, state.newCardsToday, state.newCardsGoal)
                    },
                ),
            )

            is StudySessionUiState.Reviewing -> ReviewingContent(
                state = state,
                onReveal = onReveal,
                onGrade = onGrade,
                onSpeak = onSpeak,
                onSpeakTest = onSpeakTest,
                onClose = onClose,
            )
        }

        // Overlaid rather than replacing the card: the reviews are buffered and journalled, so the
        // session is still worth finishing — the user just needs to know the writing has stopped.
        state.syncError?.let { reason ->
            SyncErrorBanner(
                reason = reason,
                onDismiss = onDismissSyncError,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // A full-screen moment rather than a banner, and the only modal thing in the session.
        // It is shown once a day, so the interruption is cheap; and "Keep studying" is right there
        // because meeting a goal must never be the thing that ends a session.
        (state as? StudySessionUiState.Reviewing)?.goalCelebration?.let { celebration ->
            GoalCelebrationScreen(
                celebration = celebration,
                onKeepStudying = onContinueAfterGoal,
                onDone = onClose,
            )
        }
    }

    if (state is StudySessionUiState.Reviewing) {
        SpeakSheets(
            phase = state.speakPhase,
            targetWord = state.backText,
            onCancel = onSpeakCancel,
            onContinue = onSpeakContinue,
            onRetry = onSpeakRetry,
        )
    }
}

@Composable
private fun ReviewingContent(
    state: StudySessionUiState.Reviewing,
    onReveal: () -> Unit,
    onGrade: (SrsGrade) -> Unit,
    onSpeak: () -> Unit,
    onSpeakTest: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LoopkyTheme.colors
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
                    contentDescription = stringResource(R.string.study_close),
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.deckTitle.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.study_position_of_total, state.position, state.total),
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

        // Card (tap front to reveal) with a 3D flip that only ever plays forward. Advancing to
        // the next card is a fade/slide, NOT a reverse flip: each card owns its own rotation
        // state, so a new card composes at 0f (front) and the outgoing card keeps rendering its
        // own snapshot while it fades. Without this the un-flip showed the next card's answer
        // for the first half of the turn.
        val reduceMotion = rememberReduceMotion()
        // The weighted Box keeps the flip frame stable across the reveal; the card inside is
        // capped so a one-word prompt doesn't stretch into a near-full-screen rectangle.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = CardSnapshot(
                    position = state.position,
                    frontText = state.frontText,
                    backText = state.backText,
                    backLabel = state.backLabel,
                    frontImageRef = state.frontImageRef,
                    backImageRef = state.backImageRef,
                    revealed = state.revealed,
                ),
                // Keyed on the card, so revealing swaps content in place (the flip) and only a
                // card change runs the enter/exit transition.
                contentKey = { it.position },
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(tween(durationMillis = 200)) +
                            slideInVertically(tween(durationMillis = 200)) { it / 12 } togetherWith
                            fadeOut(tween(durationMillis = 100))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .fillMaxHeight(),
                label = "cardAdvance",
            ) { card ->
                FlippableCard(
                    card = card,
                    reduceMotion = reduceMotion,
                    listenEnabled = state.listenEnabled,
                    speakEnabled = state.speakEnabled,
                    deckId = state.deckId,
                    authorPubky = state.authorPubky,
                    onReveal = onReveal,
                    onSpeak = onSpeak,
                    onSpeakTest = onSpeakTest,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SRS grade row — reserve its space always so the card above stays the same size; the
        // grade buttons themselves appear only once revealed. (Listen/Speak, by contrast, are on
        // both faces — they live inside the card, not here.)
        Box(modifier = Modifier.height(72.dp)) {
            if (state.revealed) {
                SrsRow(intervals = state.intervals, onGrade = onGrade, reduceMotion = reduceMotion)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Flip hint — shown on the front only; space is reserved on the back too so the
        // card above keeps the same size across the flip.
        Box(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            if (!state.revealed) {
                FlipHint(modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Everything the card face renders, plus the identity the advance transition keys on.
 * `position` is unique per card within a session — the VM only ever moves forward
 * (`index++`) and never re-queues a card.
 */
private data class CardSnapshot(
    val position: Int,
    val frontText: String,
    val backText: String,
    val backLabel: String?,
    val frontImageRef: MediaRef.Image?,
    val backImageRef: MediaRef.Image?,
    val revealed: Boolean,
)

/**
 * One card, owning its own flip. Composed per [CardSnapshot] by the advance `AnimatedContent`,
 * so a freshly entering card starts at 0f (front, no animation) and an exiting card holds the
 * face it was already showing.
 */
@Composable
private fun AnimatedContentScope.FlippableCard(
    card: CardSnapshot,
    reduceMotion: Boolean,
    listenEnabled: Boolean,
    speakEnabled: Boolean,
    deckId: String,
    authorPubky: String,
    onReveal: () -> Unit,
    onSpeak: () -> Unit,
    onSpeakTest: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val rotation by animateFloatAsState(
        targetValue = if (card.revealed) 180f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 700, easing = FastOutSlowInEasing)
        },
        label = "cardFlip",
    )
    // A card on its way out is still on screen for the fade; taps there would hit the card the
    // VM has already moved past.
    val interactive = transition.targetState == EnterExitState.Visible

    Box(
        modifier = Modifier
            .testTag("study_card")
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .clickable(enabled = interactive && !card.revealed, onClick = onReveal)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (rotation < 90f) {
            // Listen/Speak on the front as well as the back, per DESIGN_GUIDELINE §8 ("Both
            // sides include a Speak button"). They act on the side facing the user, so on the
            // front they practise the prompt in the *front* language — which is the side a
            // foreign word usually sits on, and hearing it before answering is the point.
            // The reveal cue lives in the hint row below. The prompt's picture belongs here too
            // — an Anki front is often nothing else, and passing it only to the back left those
            // cards asking the question with a blank card (#96).
            CardFace(
                label = null,
                text = card.frontText,
                textSize = 48.sp,
                onSpeak = onSpeak,
                showListen = interactive && listenEnabled,
                onSpeakTest = if (interactive && speakEnabled) onSpeakTest else null,
                featureImageRef = card.frontImageRef,
                deckId = deckId,
                authorPubky = authorPubky,
            )
        } else {
            // Counter-rotate so the back content is not mirrored.
            CardFace(
                label = card.backLabel,
                text = card.backText,
                textSize = 42.sp,
                onSpeak = onSpeak,
                showListen = interactive && listenEnabled,
                onSpeakTest = if (interactive && speakEnabled) onSpeakTest else null,
                recallImageRef = card.frontImageRef,
                featureImageRef = card.backImageRef,
                deckId = deckId,
                authorPubky = authorPubky,
                modifier = Modifier.graphicsLayer { rotationY = 180f },
            )
        }
    }
}

@Composable
private fun FlipHint(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Autorenew,
            contentDescription = null,
            tint = colors.foregroundMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.study_flip_hint),
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = colors.foregroundMuted,
        )
    }
}

@Composable
private fun SrsRow(
    intervals: Map<SrsGrade, String>,
    onGrade: (SrsGrade) -> Unit,
    reduceMotion: Boolean,
) {
    val colors = LoopkyTheme.colors
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
        buttons.forEachIndexed { index, (grade, color) ->
            SrsButton(
                grade = grade,
                color = color,
                interval = intervals[grade] ?: "",
                index = index,
                reduceMotion = reduceMotion,
                onGrade = onGrade,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One grade button. On reveal the buttons fade + rise + scale in with a per-index
 * stagger; pressing dips the scale for tactile feedback. Both effects are skipped when
 * the OS has animations disabled.
 */
@Composable
private fun SrsButton(
    grade: SrsGrade,
    color: Color,
    interval: String,
    index: Int,
    reduceMotion: Boolean,
    onGrade: (SrsGrade) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val enter by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 280, delayMillis = index * 60, easing = FastOutSlowInEasing)
        },
        label = "srsEnter",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "srsPress",
    )

    Button(
        onClick = { onGrade(grade) },
        interactionSource = interactionSource,
        modifier = modifier
            .testTag("study_${grade.name.lowercase()}")
            .height(72.dp)
            .graphicsLayer {
                alpha = enter
                val scale = (0.85f + 0.15f * enter) * pressScale
                scaleX = scale
                scaleY = scale
                translationY = (1f - enter) * 20.dp.toPx()
            },
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
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 11.sp,
                    maxFontSize = 15.sp,
                    stepSize = 0.5.sp,
                ),
                fontWeight = FontWeight.W700,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = interval,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 8.sp,
                    maxFontSize = 11.sp,
                    stepSize = 0.5.sp,
                ),
                fontWeight = FontWeight.W500,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun BoxScope.CenteredMessage(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    /** Quieter lines below the subtitle. Their own Texts rather than appended prose, so each wraps. */
    details: List<String> = emptyList(),
) {
    val colors = LoopkyTheme.colors
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
        details.forEach { line ->
            Text(
                text = line,
                fontSize = 14.sp,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
        }
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

/**
 * Why buffered reviews are not reaching the homeserver, for the states that can still show it.
 * Loading/Empty/Error have no session in progress to warn about.
 */
private val StudySessionUiState.syncError: ErrorReason?
    get() = when (this) {
        is StudySessionUiState.Reviewing -> syncError
        is StudySessionUiState.Complete -> syncError
        else -> null
    }

/**
 * Warns that graded reviews are not being written, without ending the session.
 *
 * The failure this exists for is a full homeserver, where the copy has to say the one thing the
 * generic error did not: retrying will not help, and the fix is to free space. The reviews
 * themselves are safe — buffered, journalled to disk, and re-sent by the next flush that can.
 */
@Composable
private fun SyncErrorBanner(
    reason: ErrorReason,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Surface(
        color = colors.danger,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("study_sync_error"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.study_sync_error_title),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(errorMessage(reason), color = Color.White, fontSize = 13.sp)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.study_sync_error_dismiss), color = Color.White)
            }
        }
    }
}

/**
 * The daily goal, met. Covers the session because it happens once a day and is worth stopping for.
 *
 * Both ways out are offered as equals in weight but not in emphasis: "Keep studying" is the filled
 * button, because the card behind this is already loaded and the goal withholds nothing. "Back to
 * home" exists so the moment can also be a natural place to stop.
 */
@Composable
private fun BoxScope.GoalCelebrationScreen(
    celebration: GoalCelebration,
    onKeepStudying: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val reduceMotion = rememberReduceMotion()

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(colors.surfacePrimary)
            // Swallows taps so a stray press does not reach the card underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("study_goal_reached"),
    ) {
        Confetti(
            colors = listOf(colors.srsGood, colors.accentPrimary, colors.srsEasy, colors.srsHard),
            reduceMotion = reduceMotion,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "🎉", fontSize = 64.sp)
            Text(
                text = stringResource(R.string.study_goal_reached_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.study_goal_reached_count,
                    celebration.newCardsToday,
                    celebration.newCardsToday,
                ),
                fontSize = 16.sp,
                color = colors.foregroundPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.study_goal_reached_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onKeepStudying,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("study_goal_keep_studying"),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentPrimary,
                    contentColor = colors.foregroundOnAccent,
                ),
            ) {
                Text(
                    text = stringResource(R.string.study_goal_keep_studying),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("study_goal_done"),
            ) {
                Text(
                    text = stringResource(R.string.study_goal_done),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

private val previewReviewing = StudySessionUiState.Reviewing(
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
)

@Preview
@Composable
private fun StudySessionScreenRevealedPreview() {
    LoopkyTheme {
        StudySessionScreen(
            state = previewReviewing,
            onReveal = {},
            onGrade = {},
            onSpeak = {},
            onClose = {},
            onDone = {},
        )
    }
}

@Preview
@Composable
private fun StudySessionScreenFrontPreview() {
    LoopkyTheme {
        StudySessionScreen(
            state = previewReviewing.copy(revealed = false),
            onReveal = {},
            onGrade = {},
            onSpeak = {},
            onClose = {},
            onDone = {},
        )
    }
}

/**
 * Owns the RECORD_AUDIO flow for Speak: rationale before the cold system prompt, a toast on a
 * recoverable denial, and a route into app settings once Android stops asking. Extracted from
 * `StudySessionRoute` to keep that composable under detekt's complexity cap.
 */
@Composable
private fun rememberMicPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showBlocked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onGranted()
            return@rememberLauncherForActivityResult
        }
        // Once the system stops offering the prompt a toast is a dead end: Speak would be
        // inert forever with no way to re-enable it from inside the app.
        val canAskAgain =
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true
        if (canAskAgain) {
            Toast.makeText(context, R.string.speak_permission_denied, Toast.LENGTH_LONG).show()
        } else {
            showBlocked = true
        }
        onDenied()
    }

    MicPermissionDialogs(
        showRationale = showRationale,
        showBlocked = showBlocked,
        onRationaleConfirm = {
            showRationale = false
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onRationaleDismiss = { showRationale = false },
        onBlockedDismiss = { showBlocked = false },
    )

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onGranted() else showRationale = true
    }
}

/** Split out of `StudySessionRoute` purely to keep its complexity under the detekt cap. */
@Composable
private fun MicPermissionDialogs(
    showRationale: Boolean,
    showBlocked: Boolean,
    onRationaleConfirm: () -> Unit,
    onRationaleDismiss: () -> Unit,
    onBlockedDismiss: () -> Unit,
) {
    if (showRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.permission_mic_title),
            message = stringResource(R.string.permission_mic_rationale),
            onConfirm = onRationaleConfirm,
            onDismiss = onRationaleDismiss,
        )
    }
    if (showBlocked) {
        PermissionBlockedDialog(
            title = stringResource(R.string.permission_mic_denied_title),
            message = stringResource(R.string.permission_mic_denied_message),
            onDismiss = onBlockedDismiss,
        )
    }
}
