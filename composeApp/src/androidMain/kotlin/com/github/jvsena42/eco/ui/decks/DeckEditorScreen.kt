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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.decks.DeckEditorEffect
import com.github.jvsena42.eco.presentation.decks.DeckEditorUiState
import com.github.jvsena42.eco.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.eco.presentation.decks.EditableCardModel
import com.github.jvsena42.eco.ui.components.TagChip
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun DeckEditorRoute(
    deckId: String?,
    onBack: () -> Unit = {},
    onEditCard: (deckId: String, cardId: String) -> Unit = { _, _ -> },
    onSaved: (deckId: String) -> Unit = {},
) {
    val viewModel = koinInject<DeckEditorViewModel> { parametersOf(deckId) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }

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

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 1. Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Close button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceCard)
                    .clickable(onClick = onCloseClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = colors.foregroundPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = if (state.isNew) "New Deck" else "Edit Deck",
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

        // 2. Metadata card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0x0D1A1326),
                    spotColor = Color(0x0D1A1326),
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
                            contentDescription = "Cover",
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
                        text = "DECK TITLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.8.sp,
                        color = colors.foregroundMuted,
                    )

                    BasicTextField(
                        value = state.title,
                        onValueChange = onTitleChanged,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W700,
                            color = colors.foregroundPrimary,
                        ),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.title.isEmpty()) {
                                    Text(
                                        text = "Enter deck title...",
                                        fontSize = 16.sp,
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

            // Description section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "DESCRIPTION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )

                BasicTextField(
                    value = state.description,
                    onValueChange = onDescriptionChanged,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = colors.foregroundSecondary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.description.isEmpty()) {
                                Text(
                                    text = "Add a description...",
                                    fontSize = 14.sp,
                                    color = colors.foregroundMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            // Tags section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "TAGS (PUBKY)",
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

        // 3. Cards section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cards (${state.cards.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.W800,
                color = colors.foregroundPrimary,
            )
        }

        // 4. Card list
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.cards.forEach { card ->
                CardRow(
                    card = card,
                    onClick = { onCardClick(card.id) },
                )
            }
        }

        // 5. Add card button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = 1.5.dp,
                    color = colors.accentPrimary,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onAddCard)
                .padding(vertical = 16.dp, horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add card",
                tint = colors.accentPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Add card",
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                color = colors.accentPrimary,
            )
        }

        // 6. Error toast
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
                ambientColor = Color(0x0D1A1326),
                spotColor = Color(0x0D1A1326),
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
            contentDescription = "Reorder",
            tint = colors.foregroundMuted,
            modifier = Modifier.size(18.dp),
        )

        // Text column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.frontText.ifEmpty { "Front" },
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                color = if (card.frontText.isEmpty()) colors.foregroundMuted else colors.foregroundPrimary,
                maxLines = 1,
            )
            Text(
                text = card.backText.ifEmpty { "Back" },
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
                        contentDescription = "Has image",
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (card.hasAudio) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Has audio",
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
