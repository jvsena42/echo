package com.github.jvsena42.echo.ui.decks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.presentation.decks.DeckEditorEffect
import com.github.jvsena42.echo.presentation.decks.DeckEditorUiState
import com.github.jvsena42.echo.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.echo.presentation.decks.EditableCardModel
import com.github.jvsena42.echo.ui.components.TagChip
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DeckEditorRoute(
    deckId: String?,
    onBack: () -> Unit = {},
    onEditCard: (deckId: String, cardId: String) -> Unit = { _, _ -> },
    onSaved: (deckId: String) -> Unit = {},
) {
    val viewModel = koinViewModel<DeckEditorViewModel> { parametersOf(deckId) }

    val currentBack by rememberUpdatedState(onBack)
    val currentEditCard by rememberUpdatedState(onEditCard)
    val currentSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DeckEditorEffect.NavigateBack -> currentBack()
                is DeckEditorEffect.NavigateEditCard -> currentEditCard(effect.deckId, effect.cardId)
                is DeckEditorEffect.SaveSuccess -> currentSaved(effect.deckId)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DeckEditorScreen(
        state = state,
        onCloseClick = viewModel::onCloseClick,
        onSaveClick = viewModel::onSaveClick,
        onTitleChanged = viewModel::onTitleChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onRemoveTag = viewModel::onRemoveTag,
        onAddTag = viewModel::onAddTag,
        onCardClick = viewModel::onCardClick,
        onAddCard = viewModel::onAddCard,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeckEditorScreen(
    state: DeckEditorUiState,
    onCloseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onCardClick: (String) -> Unit,
    onAddCard: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (state.isNew) {
                            stringResource(R.string.deck_editor_title_new)
                        } else {
                            stringResource(R.string.deck_editor_title_edit)
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onCloseClick,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.surfaceCard,
                            contentColor = colors.foregroundPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.deck_editor_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(18.dp),
                        )
                    } else {
                        TextButton(
                            onClick = onSaveClick,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                        ) {
                            Text(text = stringResource(R.string.deck_editor_save), fontSize = 14.sp, fontWeight = FontWeight.W700)
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Metadata card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = colors.shadowElevationLow,
                        spotColor = colors.shadowElevationLow,
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceCard)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Top row: emoji + title
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Cover emoji box
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.accentPrimarySoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.coverEmoji.isNotEmpty()) {
                            Text(text = state.coverEmoji, fontSize = 32.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = stringResource(R.string.deck_editor_cover),
                                tint = colors.foregroundMuted,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Title + description column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.deck_editor_label_title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.W700,
                            letterSpacing = 0.8.sp,
                            color = colors.foregroundMuted,
                        )

                        OutlinedTextField(
                            value = state.title,
                            onValueChange = onTitleChanged,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.W700,
                                color = colors.foregroundPrimary,
                            ),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.deck_editor_title_placeholder),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.W700,
                                    color = colors.foregroundMuted,
                                )
                            },
                            isError = state.titleError != null,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors(),
                        )

                        state.titleError?.let { errorText ->
                            Text(text = errorText, fontSize = 12.sp, color = colors.danger)
                        }
                    }
                }

                // Description section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.deck_editor_label_description),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.8.sp,
                        color = colors.foregroundMuted,
                    )

                    OutlinedTextField(
                        value = state.description,
                        onValueChange = onDescriptionChanged,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundSecondary),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.deck_editor_description_placeholder),
                                fontSize = 14.sp,
                                color = colors.foregroundMuted,
                            )
                        },
                        isError = state.descriptionError != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors(),
                    )

                    state.descriptionError?.let { errorText ->
                        Text(text = errorText, fontSize = 12.sp, color = colors.danger)
                    }
                }

                // Tags section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.deck_editor_label_tags),
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
                                Text(text = stringResource(R.string.deck_editor_add_tag), fontSize = 13.sp, fontWeight = FontWeight.W600)
                            },
                            shape = RoundedCornerShape(50),
                            colors = AssistChipDefaults.assistChipColors(labelColor = colors.accentSecondary),
                            border = BorderStroke(1.dp, colors.accentSecondary),
                        )
                    }
                }
            }

            // 2. Cards section header
            Text(
                text = stringResource(R.string.deck_editor_cards_count, state.cards.size),
                fontSize = 16.sp,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
            )

            // 3. Card list
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.cards.forEach { card ->
                    CardRow(
                        card = card,
                        onClick = { onCardClick(card.id) },
                    )
                }
            }

            // 4. Add card button
            OutlinedButton(
                onClick = onAddCard,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentPrimary),
                border = BorderStroke(1.5.dp, colors.accentPrimary),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.deck_editor_add_card),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.deck_editor_add_card), fontSize = 15.sp, fontWeight = FontWeight.W700)
            }

            // 5. Error toast
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
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = EchoTheme.colors.accentPrimary,
    unfocusedBorderColor = EchoTheme.colors.borderSubtle,
    cursorColor = EchoTheme.colors.accentPrimary,
    errorBorderColor = EchoTheme.colors.danger,
)

@Composable
private fun CardRow(
    card: EditableCardModel,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = colors.shadowElevationLow,
                spotColor = colors.shadowElevationLow,
            )
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drag handle
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = stringResource(R.string.deck_editor_reorder),
            tint = colors.foregroundMuted,
            modifier = Modifier.size(18.dp),
        )

        // Text column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.frontText.ifEmpty { stringResource(R.string.deck_editor_card_front_placeholder) },
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                color = if (card.frontText.isEmpty()) colors.foregroundMuted else colors.foregroundPrimary,
                maxLines = 1,
            )
            Text(
                text = card.backText.ifEmpty { stringResource(R.string.deck_editor_card_back_placeholder) },
                fontSize = 13.sp,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        }

        // Media indicators
        if (card.hasImage || card.hasAudio) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.hasImage) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.deck_editor_has_image),
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (card.hasAudio) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.deck_editor_has_audio),
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun DeckEditorScreenPreview() {
    EchoTheme {
        DeckEditorScreen(
            state = DeckEditorUiState(
                isNew = false,
                coverEmoji = "🇪🇸",
                title = "Spanish Essentials",
                description = "Core vocabulary for everyday conversations.",
                tags = listOf("language", "spanish"),
                cards = listOf(
                    EditableCardModel(
                        id = "c1",
                        frontText = "Hola",
                        backText = "Hello",
                        hasImage = false,
                        hasAudio = true,
                    ),
                    EditableCardModel(
                        id = "c2",
                        frontText = "Gracias",
                        backText = "Thank you",
                        hasImage = true,
                        hasAudio = false,
                    ),
                ),
            ),
            onCloseClick = {},
            onSaveClick = {},
            onTitleChanged = {},
            onDescriptionChanged = {},
            onRemoveTag = {},
            onAddTag = {},
            onCardClick = {},
            onAddCard = {},
        )
    }
}
