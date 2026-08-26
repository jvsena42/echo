package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.LocalSignupEffect
import com.github.jvsena42.loopky.presentation.signup.LocalSignupUiState
import com.github.jvsena42.loopky.presentation.signup.LocalSignupViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

/**
 * Redeem the signup token in Loopky: mint a key here, register it, sign in.
 *
 * The terminal step of the local route, and the sibling of [SignupHandoffRoute] — everything
 * before it (the human check and its three methods) is the identical flow.
 */
@Composable
fun LocalSignupRoute(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalSignupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnCreated by rememberUpdatedState(onCreated)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                LocalSignupEffect.NavigateBackup -> currentOnCreated()
            }
        }
    }

    LocalSignupScreen(
        state = state,
        onRetry = viewModel::onRetryClick,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun LocalSignupScreen(
    state: LocalSignupUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_local_title),
        subtitle = stringResource(R.string.signup_local_subtitle),
        onBack = onBack,
        error = state.error,
        modifier = modifier,
    ) {
        if (state.isWorking) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("signup_local_progress"),
                color = colors.accentPrimary,
            )
        }
        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            // Retrying re-reads the stored token rather than minting a new one, and the key is
            // already saved — so a retry registers the *same* pubky instead of creating a second
            // identity and stranding the first.
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_local_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("signup_local_retry"),
            )
        }
        state.pubky?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it, color = colors.foregroundMuted, fontSize = 12.sp)
        }
    }
}

@Preview
@Composable
private fun LocalSignupPreview() {
    LoopkyTheme {
        LocalSignupScreen(state = LocalSignupUiState(), onRetry = {}, onBack = {})
    }
}
