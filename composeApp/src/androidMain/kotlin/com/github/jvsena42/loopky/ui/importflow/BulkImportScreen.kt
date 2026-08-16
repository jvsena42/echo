package com.github.jvsena42.loopky.ui.importflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.importflow.BulkImportEffect
import com.github.jvsena42.loopky.presentation.importflow.BulkImportUiState
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.SampleCard
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
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
    val context = LocalContext.current

    // The effect collector outlives a recomposition that swaps these lambdas, so capture them
    // through rememberUpdatedState rather than closing over the originals.
    val currentBack by rememberUpdatedState(onBack)
    val currentContinue by rememberUpdatedState(onContinue)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BulkImportEffect.Continue -> currentContinue()
                BulkImportEffect.NavigateBack -> currentBack()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Read on the caller's side: file access is a platform concern, and the shared ViewModel
        // takes plain text so the same summary works for any future source.
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Could not open that file.")
            name to text
        }
            .onSuccess { (name, text) -> viewModel.onFileLoaded(name, text) }
            .onFailure { viewModel.onFileReadFailed(it.message ?: "Could not read that file.") }
    }

    BulkImportScreen(
        state = state,
        onPickFile = { picker.launch(arrayOf("text/*", "text/plain", "text/tab-separated-values")) },
        onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkImportScreen(
    state: BulkImportUiState,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Scaffold(
        modifier = modifier,
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.bulk_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onCancel,
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
                is BulkImportUiState.Parsing -> ParsingIndicator(state.fileName)
                is BulkImportUiState.Ready -> Summary(state, onConfirm)
                is BulkImportUiState.Error -> ErrorState(state.message, onPickFile)
            }
        }
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
    Button(
        onClick = onPickFile,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
    ) { Text(stringResource(R.string.bulk_pick_file)) }
}

@Composable
private fun ParsingIndicator(fileName: String) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(64.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.accentPrimary)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.bulk_parsing, fileName),
        fontSize = 14.sp,
        color = colors.foregroundSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Summary(state: BulkImportUiState.Ready, onConfirm: () -> Unit) {
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
    Button(
        onClick = onConfirm,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
    ) {
        Text(pluralStringResource(R.plurals.bulk_import_cards, state.cardCount, state.cardCount))
    }
    Spacer(Modifier.height(24.dp))
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
private fun ErrorState(message: String, onPickFile: () -> Unit) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(48.dp))
    Text(
        text = message,
        fontSize = 15.sp,
        color = colors.foregroundPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onPickFile,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.bulk_pick_file)) }
}
