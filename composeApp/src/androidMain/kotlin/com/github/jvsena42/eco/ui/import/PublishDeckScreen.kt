package com.github.jvsena42.eco.ui.import_flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.import_flow.PublishDeckEffect
import com.github.jvsena42.eco.presentation.import_flow.PublishDeckUiState
import com.github.jvsena42.eco.presentation.import_flow.PublishDeckViewModel
import com.github.jvsena42.eco.ui.components.EchoPrimaryButton
import com.github.jvsena42.eco.ui.components.TagChip
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun PublishDeckRoute(
    onBack: () -> Unit = {},
    onPublished: (deckId: String) -> Unit = {},
) {
    val viewModel = koinInject<PublishDeckViewModel>()
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    val currentBack by rememberUpdatedState(onBack)
    val currentPublished by rememberUpdatedState(onPublished)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                PublishDeckEffect.NavigateBack -> currentBack()
                is PublishDeckEffect.Published -> currentPublished(effect.deckId)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    PublishDeckScreen(
        state = state,
        onTitleChanged = viewModel::onTitleChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onAddTag = viewModel::onAddTag,
        onRemoveTag = viewModel::onRemoveTag,
        onPublishClick = viewModel::onPublishClick,
        onBackClick = viewModel::onBackClick,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PublishDeckScreen(
    state: PublishDeckUiState,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onPublishClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    var showTagSheet by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceCard)
                    .clickable(onClick = onBackClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = colors.foregroundPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("New deck", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = colors.foregroundPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(40.dp))
        }

        // Cards ready badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.srsGood.copy(alpha = 0.15f))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Check, null, tint = colors.srsGood, modifier = Modifier.size(20.dp))
            Column {
                Text("${state.cardCount} cards ready", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
            }
        }

        // Cover
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentPrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.coverEmoji.ifBlank { "📚" },
                    fontSize = 32.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("COVER", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🖼️", fontSize = 14.sp)
                    Text("Change", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.foregroundPrimary)
                }
            }
        }

        // Title
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TITLE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
            BasicTextField(
                value = state.title,
                onValueChange = onTitleChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (state.title.isEmpty()) Text("Deck title", fontSize = 16.sp, color = colors.foregroundMuted)
                        inner()
                    }
                },
            )
        }

        // Description
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DESCRIPTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
            BasicTextField(
                value = state.description,
                onValueChange = onDescriptionChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundSecondary),
                cursorBrush = SolidColor(colors.accentPrimary),
                decorationBox = { inner ->
                    Box {
                        if (state.description.isEmpty()) Text("Add a short description...", fontSize = 14.sp, color = colors.foregroundMuted)
                        inner()
                    }
                },
            )
        }

        // Tags
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TAGS", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.tags.forEach { tag ->
                    TagChip(tag = tag, onRemove = { onRemoveTag(tag) })
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(50))
                        .clickable { showTagSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add tag",
                        tint = colors.foregroundMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "Add",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foregroundMuted,
                    )
                }
            }
        }

        // Public on Pubky notice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accentSecondarySoft)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🌐", fontSize = 18.sp)
            Column {
                Text("Public on your profile", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
                Text("Anyone can find this deck on Discover.", fontSize = 12.sp, color = colors.foregroundSecondary)
            }
        }

        // Publish button
        EchoPrimaryButton(
            label = "Publish deck",
            onClick = onPublishClick,
            loading = state.isPublishing,
            enabled = state.title.isNotBlank() && !state.isPublishing,
            modifier = Modifier.fillMaxWidth(),
        )

        // Error
        state.error?.let { errorText ->
            Text(errorText, fontSize = 14.sp, color = colors.danger, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showTagSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTagSheet = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.borderSubtle)
                    )
                }
            },
        ) {
            val suggestedTags = remember {
                listOf("language", "beginner", "travel", "daily")
            }.filter { it !in state.tags }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Add Tag",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfacePrimary)
                        .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "#",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accentSecondary,
                    )
                    BasicTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.foregroundPrimary,
                        ),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (tagInput.isEmpty()) {
                                    Text(
                                        "Type a tag\u2026",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.foregroundMuted,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }

                if (suggestedTags.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "SUGGESTED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = colors.foregroundMuted,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            suggestedTags.forEach { tag ->
                                TagChip(tag = tag, onClick = { onAddTag(tag) })
                            }
                        }
                    }
                }

                if (state.tags.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "CURRENT TAGS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = colors.foregroundMuted,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.tags.forEach { tag ->
                                TagChip(tag = tag, onRemove = { onRemoveTag(tag) })
                            }
                        }
                    }
                }

                EchoPrimaryButton(
                    label = "Add Tag",
                    onClick = {
                        onAddTag(tagInput)
                        tagInput = ""
                    },
                    enabled = tagInput.isNotBlank(),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = colors.foregroundOnAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}
