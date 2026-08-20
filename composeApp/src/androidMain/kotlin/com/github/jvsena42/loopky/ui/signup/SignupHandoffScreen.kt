package com.github.jvsena42.loopky.ui.signup

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.SignupHandoffEffect
import com.github.jvsena42.loopky.presentation.signup.SignupHandoffUiState
import com.github.jvsena42.loopky.presentation.signup.SignupHandoffViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignupHandoffRoute(onBack: () -> Unit, onSignedUp: () -> Unit, onSignIn: () -> Unit) {
    val viewModel: SignupHandoffViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentOnSignedUp by rememberUpdatedState(onSignedUp)
    val currentOnSignIn by rememberUpdatedState(onSignIn)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SignupHandoffEffect.OpenDeeplink -> {
                    val intent = Intent(Intent.ACTION_VIEW, effect.url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Resolve first: without Pubky Ring there is nowhere for the deeplink to go,
                    // and the flow would otherwise sit waiting for an approval that cannot happen.
                    if (intent.resolveActivity(context.packageManager) == null) {
                        viewModel.onDeeplinkUnavailable()
                    } else {
                        runCatching { context.startActivity(intent) }
                            .onFailure { if (it is ActivityNotFoundException) viewModel.onDeeplinkUnavailable() }
                    }
                }

                is SignupHandoffEffect.CopyToClipboard -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    context.toast(R.string.signup_handoff_code_copied)
                }

                SignupHandoffEffect.NavigateHome -> currentOnSignedUp()
                SignupHandoffEffect.NavigateSignIn -> currentOnSignIn()
            }
        }
    }

    SignupHandoffScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::onRetryClick,
        onCopyToken = viewModel::onCopyTokenClick,
        onUseExisting = viewModel::onUseExistingPubkyClick,
    )
}

@Composable
private fun SignupHandoffScreen(
    state: SignupHandoffUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCopyToken: () -> Unit,
    onUseExisting: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_handoff_title),
        subtitle = stringResource(R.string.signup_handoff_subtitle),
        onBack = onBack,
        error = state.error,
    ) {
        if (state.isWorking) {
            CircularProgressIndicator(color = colors.accentPrimary, modifier = Modifier.testTag("signup_handoff_progress"))
        }

        if (state.error != null) {
            Spacer(Modifier.height(20.dp))
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_handoff_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("signup_handoff_retry"),
            )
            Spacer(Modifier.height(10.dp))
            // Said plainly, because "try again" after paying reads as "pay again" otherwise.
            Text(
                text = stringResource(R.string.signup_handoff_token_safe),
                color = colors.foregroundMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        if (state.token != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCopyToken, modifier = Modifier.testTag("signup_handoff_copy")) {
                Text(text = stringResource(R.string.signup_handoff_copy_code), color = colors.accentSecondary, fontSize = 14.sp)
            }
        }

        TextButton(onClick = onUseExisting, modifier = Modifier.testTag("signup_handoff_use_existing")) {
            Text(text = stringResource(R.string.signup_handoff_use_existing), color = colors.foregroundMuted, fontSize = 13.sp)
        }
    }
}
