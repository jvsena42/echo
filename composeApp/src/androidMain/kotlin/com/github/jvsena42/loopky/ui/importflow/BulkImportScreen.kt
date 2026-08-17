package com.github.jvsena42.loopky.ui.importflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.presentation.importflow.BulkImportEffect
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import com.github.jvsena42.loopky.presentation.importflow.BulkImportUiState
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.SampleCard
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.bulkImportErrorMessage
import com.github.jvsena42.loopky.ui.components.bulkImportErrorTitle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Bulk file import. Deliberately a summary rather than the swipe queue paste uses: nobody
 * reviews a 20,000-card Anki export one card at a time (spec §5.4).
 */
@Composable
fun BulkImportRoute(
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val viewModel = koinViewModel<BulkImportViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    // Cancelled if the user navigates away mid-read; the ViewModel is cleared with the
    // destination too, so nothing outlives the screen.
    val scope = rememberCoroutineScope()

    // The effect collector outlives a recomposition that swaps these lambdas, so capture them
    // through rememberUpdatedState rather than closing over the originals.
    val currentBack by rememberUpdatedState(onBack)
    val currentContinue by rememberUpdatedState(onContinue)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BulkImportEffect.Continue -> currentContinue()
                BulkImportEffect.NavigateBack -> currentBack()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onFileReadStarted()
        // File access is a platform concern, and the shared ViewModel takes plain text so the
        // same summary works for any future source. The read itself is off the main thread —
        // it used to run right here in the callback, where a multi-MB .apkg froze the UI.
        scope.launch {
            resolver.readPickedFile(uri)
                .onSuccess { file ->
                    // Sniffed from the content, not the extension: a picked .apkg often arrives
                    // with a content:// uri that carries no useful name at all.
                    if (ApkgReader.canRead(file.bytes)) {
                        viewModel.onApkgLoaded(file.name, file.bytes)
                    } else {
                        // A photo or a PDF hits an invalid sequence within a few bytes. Without
                        // this it decoded to U+FFFD soup and "parsed" into plausible junk cards.
                        runCatching { file.bytes.decodeToString(throwOnInvalidSequence = true) }
                            .onSuccess { viewModel.onFileLoaded(file.name, it) }
                            .onFailure { viewModel.onFileReadFailed(BulkImportError.NotText) }
                    }
                }
                .onFailure { err ->
                    viewModel.onFileReadFailed(
                        (err as? FileReadException)?.reason ?: BulkImportError.Unreadable,
                    )
                }
        }
    }

    // Anything is selectable because .apkg has no registered MIME type — SAF providers variously
    // report application/octet-stream, application/zip, or nothing, so a narrowed filter greys
    // out legitimate Anki decks. Content sniffing above does the type check instead.
    val pickFile = { picker.launch(arrayOf("*/*")) }

    BulkImportScreen(
        state = state,
        onPickFile = { pickFile() },
        onSeparatorOverride = viewModel::onSeparatorOverride,
        onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkImportScreen(
    state: BulkImportUiState,
    onPickFile: () -> Unit,
    onSeparatorOverride: (Separator) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    var showSeparatorSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.bulk_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("bulk_cancel"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) { Text(stringResource(R.string.bulk_cancel)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state) {
                BulkImportUiState.Idle -> PickFilePrompt(onPickFile)
                BulkImportUiState.Reading -> BusyIndicator(stringResource(R.string.bulk_reading))
                is BulkImportUiState.Parsing ->
                    BusyIndicator(stringResource(R.string.bulk_parsing, state.fileName))
                is BulkImportUiState.Ready -> Summary(
                    state = state,
                    onConfirm = onConfirm,
                    onPickFile = onPickFile,
                    onSeparatorClick = { showSeparatorSheet = true },
                )
                is BulkImportUiState.Error -> ErrorState(state.reason, onPickFile)
            }
        }
    }

    if (showSeparatorSheet) {
        val current = (state as? BulkImportUiState.Ready)?.separator
        SeparatorOverrideSheet(
            current = current,
            onPick = {
                showSeparatorSheet = false
                onSeparatorOverride(it)
            },
            onDismiss = { showSeparatorSheet = false },
        )
    }
}

@Composable
private fun PickFilePrompt(onPickFile: () -> Unit) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(48.dp))
    Text(
        text = stringResource(R.string.bulk_pick_hint),
        fontSize = 15.sp,
        color = colors.foregroundSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    LoopkyPrimaryButton(
        label = stringResource(R.string.bulk_pick_file),
        onClick = onPickFile,
        modifier = Modifier.testTag("bulk_pick_file"),
    )
}

@Composable
private fun BusyIndicator(message: String) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(64.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.accentPrimary)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = message,
        fontSize = 14.sp,
        color = colors.foregroundSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag("bulk_busy")
            .fillMaxWidth(),
    )
}

@Composable
private fun Summary(
    state: BulkImportUiState.Ready,
    onConfirm: () -> Unit,
    onPickFile: () -> Unit,
    onSeparatorClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.bulk_summary_heading),
        fontSize = 24.sp,
        fontWeight = FontWeight.W700,
        color = colors.foregroundPrimary,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = pluralStringResource(R.plurals.bulk_cards_parsed, state.cardCount, state.cardCount),
        fontSize = 16.sp,
        color = colors.foregroundPrimary,
    )

    Spacer(Modifier.height(12.dp))
    // What the parser decided, and — as on the paste screen — a way to disagree with it.
    SeparatorChip(separator = state.separator, onClick = onSeparatorClick)

    // Everything the parse dropped, stated rather than silently swallowed.
    if (state.skippedCount > 0) {
        Caption(stringResource(R.string.bulk_skipped, state.skippedCount))
    }
    if (state.duplicatesCollapsed > 0) {
        Caption(stringResource(R.string.bulk_duplicates, state.duplicatesCollapsed))
    }
    if (state.truncatedCount > 0) {
        Caption(stringResource(R.string.bulk_truncated, state.truncatedCount))
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.bulk_sample_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 1.5.sp,
        color = colors.foregroundSecondary,
    )
    Spacer(Modifier.height(8.dp))
    state.sample.forEach { SampleRow(it) }

    Spacer(Modifier.height(32.dp))
    LoopkyPrimaryButton(
        label = pluralStringResource(R.plurals.bulk_import_cards, state.cardCount, state.cardCount),
        onClick = onConfirm,
        enabled = state.canImport,
        modifier = Modifier.testTag("bulk_import"),
    )
    Spacer(Modifier.height(8.dp))
    // Changing your mind used to mean Cancel and start the whole flow over.
    LoopkySecondaryButton(
        text = stringResource(R.string.bulk_pick_another),
        onClick = onPickFile,
        modifier = Modifier
            .testTag("bulk_repick")
            .fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SeparatorChip(separator: Separator, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.bulk_detected_separator, stringResource(separatorLabel(separator))),
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        color = colors.accentPrimary,
        modifier = Modifier
            .testTag("bulk_separator_chip")
            .background(colors.accentPrimarySoft, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SampleRow(card: SampleCard) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.surfaceSecondary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(card.front, fontSize = 15.sp, fontWeight = FontWeight.W600, color = colors.foregroundPrimary)
        Text(card.back, fontSize = 14.sp, color = colors.foregroundSecondary)
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = LoopkyTheme.colors.foregroundSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ErrorState(reason: BulkImportError, onPickFile: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    LoopkyErrorBlock(
        title = bulkImportErrorTitle(reason),
        message = bulkImportErrorMessage(reason),
        onRetry = onPickFile,
        retryLabel = stringResource(R.string.bulk_pick_file),
        modifier = Modifier.testTag("bulk_error"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportIdlePreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Idle,
            onPickFile = {},
            onSeparatorOverride = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportParsingPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Parsing("japanese_core.apkg"),
            onPickFile = {},
            onSeparatorOverride = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportReadyPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Ready(
                fileName = "japanese_core.apkg",
                separator = Separator.Tab,
                cardCount = 1_842,
                skippedCount = 6,
                duplicatesCollapsed = 12,
                truncatedCount = 0,
                sample = listOf(
                    SampleCard(front = "日本", back = "Japan"),
                    SampleCard(front = "本", back = "book, origin"),
                    SampleCard(front = "人", back = "person"),
                ),
            ),
            onPickFile = {},
            onSeparatorOverride = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportErrorPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Error(BulkImportError.NotText),
            onPickFile = {},
            onSeparatorOverride = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}
