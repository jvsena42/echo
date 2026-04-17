package com.github.jvsena42.eco.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.decks.EditCardEffect
import com.github.jvsena42.eco.presentation.decks.EditCardUiState
import com.github.jvsena42.eco.presentation.decks.EditCardViewModel
import com.github.jvsena42.eco.ui.components.TagChip
import com.github.jvsena42.eco.ui.theme.EchoTheme
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
                is EditCardEffect.Speak -> { /* handled by platform TTS */ }
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

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 1. Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cancel",
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                color = colors.accentPrimary,
                modifier = Modifier.clickable(onClick = onCancelClick),
            )

            Text(
                text = "Edit card",
                fontSize = 18.sp,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
            )

            // Save button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentPrimary)
                    .clickable(enabled = !state.isSaving, onClick = onSaveClick)
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = colors.foregroundOnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = "Save",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W700,
                        color = colors.foregroundOnAccent,
                    )
                }
            }
        }

        // 2. Context chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accentSecondarySoft)
                .padding(vertical = 6.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = null,
                tint = colors.accentSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Card ${state.cardIndex} of ${state.totalCards} \u00B7 ${state.deckTitle}",
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                color = colors.accentSecondary,
            )
        }

        // 3. Front section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "FRONT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                Row(
                    modifier = Modifier.clickable(onClick = onSpeakFront),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speak front",
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Speak",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                        color = colors.accentPrimary,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 2.dp,
                        color = colors.accentPrimary,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(16.dp)
                    .defaultMinSize(minHeight = 100.dp),
            ) {
                BasicTextField(
                    value = state.frontText,
                    onValueChange = onFrontTextChanged,
                    textStyle = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.W700,
                        color = colors.foregroundPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.frontText.isEmpty()) {
                                Text(
                                    text = "Enter front text...",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.W700,
                                    color = colors.foregroundMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }

        // 4. Back section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BACK",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                Row(
                    modifier = Modifier.clickable(onClick = onSpeakBack),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speak back",
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Speak",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                        color = colors.accentPrimary,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = colors.borderSubtle,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(16.dp),
            ) {
                BasicTextField(
                    value = state.backText,
                    onValueChange = onBackTextChanged,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = colors.foregroundPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.backText.isEmpty()) {
                                Text(
                                    text = "Enter back text...",
                                    fontSize = 16.sp,
                                    color = colors.foregroundMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }

        // 5. Media buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Image button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = 1.dp,
                        color = colors.borderSubtle,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { /* TODO: image picker */ }
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "Add image",
                    tint = colors.foregroundMuted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Image",
                    fontSize = 14.sp,
                    color = colors.foregroundMuted,
                )
            }

            // Audio button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        width = 1.dp,
                        color = colors.borderSubtle,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { /* TODO: audio recorder */ }
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Add audio",
                    tint = colors.foregroundMuted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Audio",
                    fontSize = 14.sp,
                    color = colors.foregroundMuted,
                )
            }
        }

        // 6. Tags section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = colors.borderSubtle,
                        shape = RoundedCornerShape(16.dp),
                    )
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

                        // + Add chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(
                                    width = 1.dp,
                                    color = colors.accentSecondary,
                                    shape = RoundedCornerShape(50),
                                )
                                .clickable { onAddTag("new") }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "+ Add",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W600,
                                color = colors.accentSecondary,
                            )
                        }
                    }
                }
            }
        }

        // 7. Spacer to push delete to bottom
        Spacer(modifier = Modifier.weight(1f))

        // 8. Delete button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.dangerSoft)
                .clickable(onClick = onDeleteCard)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete card",
                tint = colors.srsAgain,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Delete card",
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                color = colors.srsAgain,
            )
        }

        // Error display
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
