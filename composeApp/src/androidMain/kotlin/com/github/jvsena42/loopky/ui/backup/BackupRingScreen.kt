package com.github.jvsena42.loopky.ui.backup

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.backup.BackupEffect
import com.github.jvsena42.loopky.presentation.backup.BackupRingUiState
import com.github.jvsena42.loopky.presentation.backup.BackupRingViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyOutlinedButton
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

/**
 * Export the key into Pubky Ring over `pubkyring://`.
 *
 * Ring **imports** the key; it does not take custody. Loopky keeps its copy and its session, and
 * the phrase the user wrote down is still valid — so nothing on this screen may suggest the key
 * moved or that another copy stopped existing.
 */
@Composable
fun BackupRingRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)
    val context = LocalContext.current

    SecureScreen()

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is BackupEffect.OpenDeeplink -> {
                    // Straight to startActivity. The URL carries the recovery phrase, so it is
                    // never logged and never staged in a clipboard — recents snapshots and IME
                    // clipboard history both read anything that passes through one.
                    val intent = Intent(Intent.ACTION_VIEW, effect.url.toUri())
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        viewModel.onRingUnavailable()
                    }
                }
                BackupEffect.Done -> currentOnDone()
                else -> Unit
            }
        }
    }

    BackupRingScreen(
        state = state,
        onExport = viewModel::onExportClick,
        onConfirm = viewModel::onExportConfirmed,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun BackupRingScreen(
    state: BackupRingUiState,
    onExport: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.backup_ring_title),
        subtitle = stringResource(R.string.backup_ring_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (!state.ringInstalled) {
            Text(
                text = stringResource(R.string.backup_ring_missing),
                modifier = Modifier.testTag("backup_ring_missing"),
                color = colors.foregroundSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
        }

        LoopkyPrimaryButton(
            label = stringResource(R.string.backup_ring_open),
            onClick = onExport,
            enabled = state.ringInstalled,
            modifier = Modifier.testTag("backup_ring_open"),
        )
        Spacer(Modifier.height(12.dp))
        // We cannot see the other side of the deeplink, so this is the user's word for it — which
        // is why it is a separate, deliberate tap rather than something inferred from the launch.
        LoopkyOutlinedButton(
            label = stringResource(R.string.backup_ring_confirm),
            onClick = onConfirm,
            modifier = Modifier.testTag("backup_ring_confirm"),
        )
    }
}

@Preview
@Composable
private fun BackupRingPreview() {
    LoopkyTheme {
        BackupRingScreen(
            state = BackupRingUiState(ringInstalled = true),
            onExport = {}, onConfirm = {}, onBack = {},
        )
    }
}
