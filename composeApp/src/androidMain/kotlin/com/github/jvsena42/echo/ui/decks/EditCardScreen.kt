package com.github.jvsena42.echo.ui.decks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.presentation.decks.EditCardEffect
import com.github.jvsena42.echo.presentation.decks.EditCardUiState
import com.github.jvsena42.echo.presentation.decks.EditCardViewModel
import com.github.jvsena42.echo.ui.components.TagChip
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun EditCardRoute(
    deckId: String,
    cardId: String,
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<EditCardViewModel> { parametersOf(deckId, cardId) }
    val speaker = koinInject<Speaker>()
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

    val currentBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                EditCardEffect.NavigateBack -> currentBack()
                EditCardEffect.SaveSuccess -> currentBack()
                EditCardEffect.Deleted -> currentBack()
                is EditCardEffect.Speak -> speaker.speak(effect.text)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    EditCardScreen(
        state = state,
        onCancelClick = viewModel::onCancelClick,
        onSaveClick = viewModel::onSaveClick,
        onFrontTextChanged = viewModel::onFrontTextChanged,
        onBackTextChanged = viewModel::onBackTextChanged,
        onSpeakFront = viewModel::onSpeakFront,
        onSpeakBack = viewModel::onSpeakBack,
        onRemoveTag = viewModel::onRemoveTag,
        onAddTag = viewModel::onAddTag,
        onDeleteCard = viewModel::onDeleteCard,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditCardScreen(
    state: EditCardUiState,
    onCancelClick: () -> Unit,
    onSaveClick: () -> Unit,
    onFrontTextChanged: (String) -> Unit,
    onBackTextChanged: (String) -> Unit,
    onSpeakFront: () -> Unit,
    onSpeakBack: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteCard: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Edit card",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancelClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = "Cancel", fontSize = 16.sp, fontWeight = FontWeight.W600)
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                        )
                    } else {
                        TextButton(
                            onClick = onSaveClick,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                        ) {
                            Text(text = "Save", fontSize = 16.sp, fontWeight = FontWeight.W700)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 1. Context chip
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = "Card ${state.cardIndex} of ${state.totalCards} · ${state.deckTitle}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                shape = RoundedCornerShape(50),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colors.accentSecondarySoft,
                    labelColor = colors.accentSecondary,
                    leadingIconContentColor = colors.accentSecondary,
                ),
                border = null,
            )

            // 2. Front section
            CardTextSection(
                label = "FRONT",
                speakDescription = "Speak front",
                onSpeak = onSpeakFront,
                value = state.frontText,
                onValueChange = onFrontTextChanged,
                placeholder = "Enter front text...",
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W700),
                error = state.frontError,
                focusedBorderColor = colors.accentPrimary,
            )

            // 3. Back section
            CardTextSection(
                label = "BACK",
                speakDescription = "Speak back",
                onSpeak = onSpeakBack,
                value = state.backText,
                onValueChange = onBackTextChanged,
                placeholder = "Enter back text...",
                textStyle = TextStyle(fontSize = 16.sp),
                error = state.backError,
                focusedBorderColor = colors.accentPrimary,
            )

            // 4. Media buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { /* TODO: image picker */ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.foregroundMuted),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Add image",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Image", fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = { /* TODO: audio recorder */ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.foregroundMuted),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Add audio",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Audio", fontSize = 14.sp)
                }
            }

            // 5. Tags section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceCard)
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "TAGS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.8.sp,
                        color = colors.foregroundMuted,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.tags.forEach { tag ->
                            TagChip(
                                tag = tag,
                                onRemove = { onRemoveTag(tag) },
                            )
                        }

                        AssistChip(
                            onClick = { onAddTag("new") },
                            label = {
                                Text(text = "+ Add", fontSize = 13.sp, fontWeight = FontWeight.W600)
                            },
                            shape = RoundedCornerShape(50),
                            colors = AssistChipDefaults.assistChipColors(labelColor = colors.accentSecondary),
                            border = BorderStroke(1.dp, colors.accentSecondary),
                        )
                    }
                }
            }

            // 6. Delete button
            FilledTonalButton(
                onClick = onDeleteCard,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.dangerSoft,
                    contentColor = colors.srsAgain,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete card",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Delete card", fontSize = 15.sp, fontWeight = FontWeight.W700)
            }

            state.error?.let { errorText ->
                Text(
                    text = errorText,
                    fontSize = 14.sp,
                    color = colors.danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardTextSection(
    label: String,
    speakDescription: String,
    onSpeak: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    error: String?,
    focusedBorderColor: Color,
) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.8.sp,
                color = colors.foregroundMuted,
            )
            TextButton(
                onClick = onSpeak,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = speakDescription,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Speak", fontSize = 12.sp, fontWeight = FontWeight.W600)
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle.copy(color = colors.foregroundPrimary),
            placeholder = { Text(text = placeholder, style = textStyle, color = colors.foregroundMuted) },
            isError = error != null,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = focusedBorderColor,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accentPrimary,
                errorBorderColor = colors.danger,
            ),
        )

        error?.let { errorText ->
            Text(text = errorText, fontSize = 12.sp, color = colors.danger)
        }
    }
}

@Preview
@Composable
private fun EditCardScreenPreview() {
    EchoTheme {
        EditCardScreen(
            state = EditCardUiState(
                deckTitle = "Spanish Essentials",
                cardIndex = 3,
                totalCards = 42,
                frontText = "Hola",
                backText = "Hello",
                tags = listOf("greeting", "basic"),
                hasImage = false,
                hasAudio = true,
                isSaving = false,
            ),
            onCancelClick = {},
            onSaveClick = {},
            onFrontTextChanged = {},
            onBackTextChanged = {},
            onSpeakFront = {},
            onSpeakBack = {},
            onRemoveTag = {},
            onAddTag = {},
            onDeleteCard = {},
        )
    }
}
