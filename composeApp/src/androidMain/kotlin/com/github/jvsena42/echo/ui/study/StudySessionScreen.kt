package com.github.jvsena42.echo.ui.study

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.domain.model.MediaRef
import com.github.jvsena42.echo.domain.model.SrsGrade
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.platform.SpeechEvent
import com.github.jvsena42.echo.platform.SpeechRecognizer
import com.github.jvsena42.echo.presentation.study.SpeakPhase
import com.github.jvsena42.echo.presentation.study.StudySessionEffect
import com.github.jvsena42.echo.presentation.study.StudySessionUiState
import com.github.jvsena42.echo.presentation.study.StudySessionViewModel
import com.github.jvsena42.echo.ui.components.CardMediaImage
import com.github.jvsena42.echo.ui.components.EchoLoadingScreen
import com.github.jvsena42.echo.ui.components.PermissionBlockedDialog
import com.github.jvsena42.echo.ui.components.PermissionRationaleDialog
import com.github.jvsena42.echo.ui.components.errorMessage
import com.github.jvsena42.echo.ui.components.errorTitle
import com.github.jvsena42.echo.ui.components.rememberReduceMotion
import com.github.jvsena42.echo.ui.theme.EchoTheme
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
                is StudySessionEffect.Speak -> speaker.speak(effect.text)
                is StudySessionEffect.StartSpeechRecognition -> {
                    recognitionJob.value?.cancel()
                    recognitionJob.value = scope.launch {
                        if (!speechRecognizer.isAvailable()) {
                            Toast.makeText(context, R.string.speak_unavailable, Toast.LENGTH_LONG).show()
                            viewModel.onSpeechError()
                            return@launch
                        }
                        speechRecognizer.listen().collect { event ->
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
            StudySessionUiState.Loading -> EchoLoadingScreen(
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

        // Card (tap front to reveal) with a 3D flip. Same Box for both faces, so front
        // and back are always the identical size.
        val reduceMotion = rememberReduceMotion()
        val rotation by animateFloatAsState(
            targetValue = if (state.revealed) 180f else 0f,
            animationSpec = if (reduceMotion) {
                snap()
            } else {
                tween(durationMillis = 700, easing = FastOutSlowInEasing)
            },
            label = "cardFlip",
        )
        // The weighted Box keeps the flip frame stable across the reveal; the card inside is
        // capped so a one-word prompt doesn't stretch into a near-full-screen rectangle.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .testTag("study_card")
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 12f * density
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceCard)
                    .clickable(enabled = !state.revealed, onClick = onReveal)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (rotation < 90f) {
                    // Front shows only the prompt (design `w1CAm`); the reveal cue lives in the
                    // hint row below, and Listen/Speak appear only on the back.
                    CardFace(
                        label = null,
                        text = state.frontText,
                        textSize = 48.sp,
                        onSpeak = onSpeak,
                        showListen = false,
                        onSpeakTest = null,
                    )
                } else {
                    // Counter-rotate so the back content is not mirrored.
                    CardFace(
                        label = state.backLabel,
                        text = state.backText,
                        textSize = 42.sp,
                        onSpeak = onSpeak,
                        showListen = state.listenEnabled,
                        onSpeakTest = if (state.speakEnabled) onSpeakTest else null,
                        imageRef = state.frontImageRef,
                        deckId = state.deckId,
                        modifier = Modifier.graphicsLayer { rotationY = 180f },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SRS grade row — reserve its space always so the card above stays the same
        // size; only show the buttons (only on the back) once revealed.
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

@Composable
private fun CardFace(
    label: String?,
    text: String,
    textSize: TextUnit,
    onSpeak: () -> Unit,
    showListen: Boolean,
    onSpeakTest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    imageRef: MediaRef.Image? = null,
    deckId: String = "",
) {
    val colors = EchoTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        label?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.5.sp,
                color = colors.accentPrimary,
            )
        }
        // Front-side image shown as a circular avatar on the card back (design `aLoMj`).
        imageRef?.let { image ->
            CardMediaImage(
                image = image,
                deckId = deckId,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimarySoft),
            )
        }
        Text(
            text = text,
            fontSize = textSize,
            fontWeight = FontWeight.W800,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showListen) {
                FilledTonalButton(
                    onClick = onSpeak,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.accentPrimarySoft,
                        contentColor = colors.accentPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.study_listen),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(R.string.study_listen), fontSize = 14.sp, fontWeight = FontWeight.W700)
                }
            }
            if (onSpeakTest != null) {
                FilledTonalButton(
                    onClick = onSpeakTest,
                    modifier = Modifier.testTag("study_speak"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.accentSecondarySoft,
                        contentColor = colors.accentSecondary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.study_speak),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(R.string.study_speak), fontSize = 14.sp, fontWeight = FontWeight.W700)
                }
            }
        }
    }
}

@Composable
private fun FlipHint(modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
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
    EchoTheme {
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
    EchoTheme {
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
