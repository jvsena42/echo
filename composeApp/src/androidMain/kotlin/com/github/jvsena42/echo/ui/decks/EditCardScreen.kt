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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.platform.Speaker
import com.github.jvsena42.echo.presentation.decks.EditCardEffect
import com.github.jvsena42.echo.presentation.decks.EditCardUiState
import com.github.jvsena42.echo.presentation.decks.EditCardViewModel
import com.github.jvsena42.echo.ui.components.ImagePickerSheet
import com.github.jvsena42.echo.ui.components.ImageSelection
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
        onFrontImageWebSelected = viewModel::onFrontImageWebSelected,
        onFrontImageGallerySelected = viewModel::onFrontImageGallerySelected,
        onRemoveFrontImage = viewModel::onRemoveFrontImage,
        onBackImageWebSelected = viewModel::onBackImageWebSelected,
        onBackImageGallerySelected = viewModel::onBackImageGallerySelected,
        onRemoveBackImage = viewModel::onRemoveBackImage,
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
    onFrontImageWebSelected: (String) -> Unit = {},
    onFrontImageGallerySelected: (ByteArray, String) -> Unit = { _, _ -> },
    onRemoveFrontImage: () -> Unit = {},
    onBackImageWebSelected: (String) -> Unit = {},
    onBackImageGallerySelected: (ByteArray, String) -> Unit = { _, _ -> },
    onRemoveBackImage: () -> Unit = {},
    onDeleteCard: () -> Unit,
) {
    val colors = EchoTheme.colors
    // Which side's image picker is open (true = front, false = back, null = none).
    var imagePickerSide by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit_card_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancelClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = stringResource(R.string.edit_card_cancel), fontSize = 16.sp, fontWeight = FontWeight.W600)
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
                        Button(
                            onClick = onSaveClick,
                            modifier = Modifier.padding(end = 12.dp),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.foregroundOnAccent,
                            ),
                        ) {
                            Text(text = stringResource(R.string.edit_card_save), fontSize = 15.sp, fontWeight = FontWeight.W700)
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
                        text = stringResource(
                            R.string.edit_card_context,
                            state.cardIndex,
                            state.totalCards,
                            state.deckTitle,
                        ),
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
                label = stringResource(R.string.edit_card_label_front),
                speakDescription = stringResource(R.string.edit_card_speak_front),
                onSpeak = onSpeakFront,
                value = state.frontText,
                onValueChange = onFrontTextChanged,
                placeholder = stringResource(R.string.edit_card_front_placeholder),
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W700),
                error = state.frontError,
                focusedBorderColor = colors.accentPrimary,
                imageModel = state.frontImageRef?.url ?: state.frontPendingBytes,
                onPickImage = { imagePickerSide = true },
                onRemoveImage = onRemoveFrontImage,
                imageTag = "editcard_front_image",
            )

            // 3. Back section
            CardTextSection(
                label = stringResource(R.string.edit_card_label_back),
                speakDescription = stringResource(R.string.edit_card_speak_back),
                onSpeak = onSpeakBack,
                value = state.backText,
                onValueChange = onBackTextChanged,
                placeholder = stringResource(R.string.edit_card_back_placeholder),
                textStyle = TextStyle(fontSize = 16.sp),
                error = state.backError,
                focusedBorderColor = colors.accentPrimary,
                imageModel = state.backImageRef?.url ?: state.backPendingBytes,
                onPickImage = { imagePickerSide = false },
                onRemoveImage = onRemoveBackImage,
                imageTag = "editcard_back_image",
            )

            // 4. Audio (recording is a future enhancement)
            OutlinedButton(
                onClick = { /* TODO: audio recorder */ },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.foregroundMuted),
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.edit_card_add_audio),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.edit_card_audio), fontSize = 14.sp)
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
                        text = stringResource(R.string.edit_card_label_tags),
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
                                Text(text = stringResource(R.string.edit_card_add_tag), fontSize = 13.sp, fontWeight = FontWeight.W600)
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
                    contentDescription = stringResource(R.string.edit_card_delete),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.edit_card_delete), fontSize = 15.sp, fontWeight = FontWeight.W700)
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

    imagePickerSide?.let { isFront ->
        ImagePickerSheet(
            title = stringResource(if (isFront) R.string.image_sheet_front_title else R.string.image_sheet_back_title),
            subtitle = stringResource(if (isFront) R.string.image_sheet_front_subtitle else R.string.image_sheet_back_subtitle),
            onDismiss = { imagePickerSide = null },
            onSelected = { selection ->
                when (selection) {
                    is ImageSelection.Web ->
                        if (isFront) onFrontImageWebSelected(selection.url) else onBackImageWebSelected(selection.url)
                    is ImageSelection.Gallery ->
                        if (isFront) {
                            onFrontImageGallerySelected(selection.bytes, selection.mime)
                        } else {
                            onBackImageGallerySelected(selection.bytes, selection.mime)
                        }
                }
                imagePickerSide = null
            },
        )
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
    imageModel: Any?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    imageTag: String,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPickImage,
                    modifier = Modifier.testTag("${imageTag}_add"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                ) {
                    Icon(imageVector = Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (imageModel != null) R.string.image_sheet_change else R.string.edit_card_add_image),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                    )
                }
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
                    Text(text = stringResource(R.string.edit_card_speak), fontSize = 12.sp, fontWeight = FontWeight.W600)
                }
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

        imageModel?.let { model ->
            Row(
                modifier = Modifier
                    .testTag("${imageTag}_chip")
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceSecondary)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRemoveImage,
                    modifier = Modifier.testTag("${imageTag}_remove"),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.srsAgain),
                ) {
                    Text(stringResource(R.string.image_sheet_remove), fontSize = 12.sp)
                }
            }
        }

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
