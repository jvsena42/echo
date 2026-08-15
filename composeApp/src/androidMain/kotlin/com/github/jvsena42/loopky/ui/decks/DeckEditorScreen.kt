package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.decks.DeckEditorEffect
import com.github.jvsena42.loopky.presentation.decks.DeckEditorUiState
import com.github.jvsena42.loopky.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.loopky.presentation.decks.EditableCardModel
import com.github.jvsena42.loopky.ui.components.ImagePickerSheet
import com.github.jvsena42.loopky.ui.components.ImageSelection
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.rememberReorderableListState
import com.github.jvsena42.loopky.ui.components.reorderableHandle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
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
        onMoveCard = viewModel::onMoveCard,
        onCoverWebSelected = viewModel::onCoverWebSelected,
        onCoverGallerySelected = viewModel::onCoverGallerySelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onMoveCard: (Int, Int) -> Unit,
    onCoverWebSelected: (String) -> Unit,
    onCoverGallerySelected: (ByteArray, String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    var showCoverSheet by rememberSaveable { mutableStateOf(false) }

    if (showCoverSheet) {
        ImagePickerSheet(
            title = stringResource(R.string.deck_editor_cover),
            subtitle = null,
            onDismiss = { showCoverSheet = false },
            onSelected = { selection ->
                when (selection) {
                    is ImageSelection.Web -> onCoverWebSelected(selection.url)
                    is ImageSelection.Gallery -> onCoverGallerySelected(selection.bytes, selection.mime)
                }
                showCoverSheet = false
            },
        )
    }

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
        // A LazyColumn rather than a scrolling Column: drag-to-reorder needs the list's layout
        // info to know which row the finger is over, and rows to animate into their new slots.
        val listState = rememberLazyListState()
        val cardKeys = remember(state.cards) { state.cards.mapTo(mutableSetOf<Any>()) { it.id } }
        val currentCards by rememberUpdatedState(state.cards)
        val reorderState = rememberReorderableListState(
            listState = listState,
            reorderableKeys = cardKeys,
            onMove = { fromKey, toKey ->
                val from = currentCards.indexOfFirst { it.id == fromKey }
                val to = currentCards.indexOfFirst { it.id == toKey }
                if (from >= 0 && to >= 0) onMoveCard(from, to)
            },
        )
        val haptics = LocalHapticFeedback.current

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Metadata card
            item(key = "metadata") {
                DeckMetadataCard(
                    state = state,
                    onTitleChanged = onTitleChanged,
                    onDescriptionChanged = onDescriptionChanged,
                    onRemoveTag = onRemoveTag,
                    onAddTag = onAddTag,
                    onCoverClick = { showCoverSheet = true },
                )
            }

            // 2. Cards section header
            item(key = "cards_header") {
                Text(
                    text = stringResource(R.string.deck_editor_cards_count, state.cards.size),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W800,
                    color = colors.foregroundPrimary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            // 3. Card list
            itemsIndexed(state.cards, key = { _, card -> card.id }) { index, card ->
                val isDragging = reorderState.draggingKey == card.id
                CardRow(
                    card = card,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.cards.lastIndex,
                    isDragging = isDragging,
                    onClick = { onCardClick(card.id) },
                    onMoveUp = { onMoveCard(index, index - 1) },
                    onMoveDown = { onMoveCard(index, index + 1) },
                    modifier = if (isDragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = reorderState.draggingOffset }
                    } else {
                        Modifier.animateItem()
                    },
                    dragHandleModifier = Modifier.reorderableHandle(
                        state = reorderState,
                        key = card.id,
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    ),
                )
            }

            // 4. Add card button
            item(key = "add_card") {
                OutlinedButton(
                    onClick = onAddCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
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
            }

            // 5. Error toast
            state.error?.let { errorText ->
                item(key = "error") {
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckMetadataCard(
    state: DeckEditorUiState,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Column(
        modifier = modifier
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
            // Cover box — tappable so a deck's cover can be changed after publishing,
            // which was previously impossible: the picker only existed on the publish step.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentPrimarySoft)
                    .clickable(onClick = onCoverClick)
                    .testTag("deck_editor_cover"),
                contentAlignment = Alignment.Center,
            ) {
                val pickedCover = state.coverImageUrl
                if (pickedCover != null) {
                    AsyncImage(
                        model = pickedCover,
                        contentDescription = stringResource(R.string.deck_editor_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.coverEmoji.isNotEmpty()) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LoopkyTheme.colors.accentPrimary,
    unfocusedBorderColor = LoopkyTheme.colors.borderSubtle,
    cursorColor = LoopkyTheme.colors.accentPrimary,
    errorBorderColor = LoopkyTheme.colors.danger,
)

@Composable
private fun CardRow(
    card: EditableCardModel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDragging) 14.dp else 4.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = colors.shadowElevationLow,
                spotColor = colors.shadowElevationLow,
            )
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Long-press-and-drag handle, per design guideline §6.5. No content description:
        // dragging is not operable with TalkBack, and announcing it would just be another
        // dead control — the move buttons next to it are the accessible path to the same move.
        Icon(
            imageVector = Icons.Default.DragIndicator,
            contentDescription = null,
            tint = if (isDragging) colors.accentPrimary else colors.foregroundMuted,
            modifier = dragHandleModifier
                .size(24.dp)
                .testTag("card_drag_handle"),
        )

        // The move buttons stay alongside the handle: dragging is unreachable with TalkBack,
        // and these are what the journey tests drive.
        Column {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(24.dp).testTag("card_move_up"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.deck_editor_move_up),
                    tint = if (canMoveUp) colors.foregroundMuted else colors.borderSubtle,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(24.dp).testTag("card_move_down"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.deck_editor_move_down),
                    tint = if (canMoveDown) colors.foregroundMuted else colors.borderSubtle,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

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
    LoopkyTheme {
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
            onMoveCard = { _, _ -> },
            onCoverWebSelected = {},
            onCoverGallerySelected = { _, _ -> },
            onAddCard = {},
        )
    }
}
