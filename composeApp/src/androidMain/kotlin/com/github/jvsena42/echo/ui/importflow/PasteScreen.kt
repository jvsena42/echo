package com.github.jvsena42.echo.ui.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.domain.model.Separator
import com.github.jvsena42.echo.presentation.importflow.PasteImportEffect
import com.github.jvsena42.echo.presentation.importflow.PasteImportUiState
import com.github.jvsena42.echo.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.echo.presentation.importflow.PreviewCard
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun PasteRoute(
    onCancel: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val viewModel = koinInject<PasteImportViewModel>()
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    val currentCancel by rememberUpdatedState(onCancel)
    val currentNext by rememberUpdatedState(onNext)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                PasteImportEffect.NavigateBack -> currentCancel()
                PasteImportEffect.NavigatePublish -> currentNext()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    PasteScreen(
        state = state,
        onTextChanged = viewModel::onTextChanged,
        onNextClick = viewModel::onNextClick,
        onCancelClick = viewModel::onCancelClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteScreen(
    state: PasteImportUiState,
    onTextChanged: (String) -> Unit,
    onNextClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val colors = EchoTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Paste cards",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancelClick,
                        modifier = Modifier.testTag("paste_cancel"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = "Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    TextButton(
                        onClick = onNextClick,
                        enabled = state.isParsed,
                        modifier = Modifier.testTag("paste_next"),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colors.accentPrimary,
                            disabledContentColor = colors.foregroundMuted,
                        ),
                    ) {
                        Text(text = "Next", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Text field
            OutlinedTextField(
                value = state.rawText,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .testTag("paste_input")
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundPrimary),
                placeholder = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Paste your list here",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.foregroundMuted,
                        )
                        Text(
                            "one card per line",
                            fontSize = 13.sp,
                            color = colors.foregroundMuted.copy(alpha = 0.6f),
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderSubtle,
                    cursorColor = colors.accentPrimary,
                    focusedContainerColor = colors.surfaceCard,
                    unfocusedContainerColor = colors.surfaceCard,
                ),
            )

            // Separator chip + card count
            if (state.isParsed && state.detectedSeparator != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Detected: ${separatorLabel(state.detectedSeparator)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                        },
                        shape = RoundedCornerShape(50),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = colors.accentSecondarySoft,
                            labelColor = colors.accentSecondary,
                            leadingIconContentColor = colors.accentSecondary,
                        ),
                        border = null,
                    )
                    Text(
                        "${state.cardCount} cards",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.foregroundMuted,
                    )
                }
            }

            // Preview cards
            if (state.isParsed && state.previewCards.isNotEmpty()) {
                Text("PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.previewCards) { index, card ->
                        PreviewCardItem(index = index + 1, total = state.cardCount, front = card.front, back = card.back)
                    }
                }
            }

            // Example cards (when empty)
            if (state.rawText.isEmpty()) {
                Text("PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = colors.foregroundMuted)
                Text(
                    "TRY PASTING SOMETHING LIKE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                ExampleCard(title = "Vocab list", separator = "em-dash", lines = listOf("hola — hello", "gracias — thank you"))
                ExampleCard(
                    title = "Glossary",
                    separator = "colon",
                    lines = listOf("mitosis: cell division", "osmosis: water moves across a membrane"),
                )
                ExampleCard(title = "Notion table", separator = "markdown", lines = listOf("| capital | France |", "| currency | euro |"))
            }

            // Public notice
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔗", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "This deck will be public on your profile",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.accentSecondary,
                )
            }

            // Error
            state.error?.let { errorText ->
                Text(errorText, fontSize = 14.sp, color = colors.danger, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PreviewCardItem(index: Int, total: Int, front: String, back: String) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier
            .width(160.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$index/$total", fontSize = 11.sp, color = colors.foregroundMuted)
        Text(front.ifBlank { "—" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
        Box(Modifier.width(32.dp).height(2.dp).background(colors.accentPrimary))
        Text(back.ifBlank { "—" }, fontSize = 14.sp, color = colors.foregroundMuted)
    }
}

@Composable
private fun ExampleCard(title: String, separator: String, lines: List<String>) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
            Text(
                separator, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.accentPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentPrimarySoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        lines.forEach { line -> Text(line, fontSize = 13.sp, color = colors.foregroundSecondary) }
    }
}

private fun separatorLabel(sep: Separator?): String = when (sep) {
    Separator.Tab -> "tab"
    Separator.Semicolon -> "semicolon"
    Separator.Pipe -> "pipe"
    Separator.EmDash -> "em-dash"
    Separator.Colon -> "colon"
    Separator.Comma -> "comma"
    Separator.BlankLine -> "blank lines"
    Separator.MarkdownTable -> "markdown"
    Separator.SingleColumn -> "single column"
    else -> "auto"
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun PasteScreenPreview() {
    EchoTheme {
        PasteScreen(
            state = PasteImportUiState(
                rawText = "hola — hello\ngracias — thank you",
                detectedSeparator = Separator.EmDash,
                cardCount = 2,
                previewCards = listOf(
                    PreviewCard(front = "hola", back = "hello"),
                    PreviewCard(front = "gracias", back = "thank you"),
                ),
                isParsed = true,
            ),
            onTextChanged = {},
            onNextClick = {},
            onCancelClick = {},
        )
    }
}
