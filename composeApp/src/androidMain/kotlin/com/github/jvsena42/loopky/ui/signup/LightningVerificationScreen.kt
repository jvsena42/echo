package com.github.jvsena42.loopky.ui.signup

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.LightningVerificationEffect
import com.github.jvsena42.loopky.presentation.signup.LightningVerificationUiState
import com.github.jvsena42.loopky.presentation.signup.LightningVerificationViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LightningVerificationRoute(onBack: () -> Unit, onDone: () -> Unit) {
    val viewModel: LightningVerificationViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is LightningVerificationEffect.CopyToClipboard -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    context.toast(R.string.signup_lightning_copied)
                }

                is LightningVerificationEffect.OpenWallet -> {
                    // `lightning:` is the BOLT11 URI scheme wallets register, and the wallet is on
                    // this device — which is why there is no QR to scan.
                    val intent = Intent(Intent.ACTION_VIEW, effect.uri.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val opened = intent.resolveActivity(context.packageManager) != null &&
                        runCatching { context.startActivity(intent) }.isSuccess

                    if (!opened) {
                        // No wallet installed. Copy the invoice rather than leaving the user with
                        // a button that silently does nothing — and say what actually happened,
                        // since claiming "invoice copied" without copying it is worse than useless.
                        clipboard.setText(AnnotatedString(effect.uri.removePrefix(LIGHTNING_SCHEME)))
                        context.toast(R.string.signup_lightning_no_wallet)
                    }
                }

                LightningVerificationEffect.NavigateToHandoff -> currentOnDone()
            }
        }
    }

    LightningVerificationScreen(
        state = state,
        onBack = onBack,
        onOpenWallet = viewModel::onOpenWalletClick,
        onCopy = viewModel::onCopyInvoiceClick,
        onNewInvoice = viewModel::createInvoice,
    )
}

@Composable
private fun LightningVerificationScreen(
    state: LightningVerificationUiState,
    onBack: () -> Unit,
    onOpenWallet: () -> Unit,
    onCopy: () -> Unit,
    onNewInvoice: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_lightning_title),
        subtitle = state.invoice
            ?.let { stringResource(R.string.signup_lightning_amount, it.amountSat) }
            .orEmpty(),
        onBack = onBack,
        error = state.error,
    ) {
        val invoice = state.invoice
        if (invoice != null) {
            Text(
                text = invoice.bolt11,
                color = colors.foregroundMuted,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceCard, RoundedCornerShape(12.dp))
                    .padding(14.dp)
                    .testTag("signup_lightning_invoice"),
            )
            Spacer(Modifier.height(20.dp))
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_lightning_open_wallet),
                onClick = onOpenWallet,
                modifier = Modifier.testTag("signup_lightning_open_wallet"),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCopy, modifier = Modifier.testTag("signup_lightning_copy")) {
                Text(stringResource(R.string.signup_lightning_copy), color = colors.accentSecondary, fontSize = 14.sp)
            }
        }

        if (state.isLoading || state.isAwaitingPayment) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = colors.accentPrimary, modifier = Modifier.testTag("signup_lightning_waiting"))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.signup_lightning_waiting), color = colors.foregroundMuted, fontSize = 13.sp)
        }

        if (state.canRetry) {
            Spacer(Modifier.height(16.dp))
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_lightning_new_invoice),
                onClick = onNewInvoice,
                modifier = Modifier.testTag("signup_lightning_new_invoice"),
            )
        }
    }
}

/** Stripped before copying, so the clipboard holds a bare BOLT11 the user can paste anywhere. */
private const val LIGHTNING_SCHEME = "lightning:"
