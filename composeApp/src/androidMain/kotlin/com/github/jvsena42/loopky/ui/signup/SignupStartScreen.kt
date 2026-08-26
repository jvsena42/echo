package com.github.jvsena42.loopky.ui.signup

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.SignupStartEffect
import com.github.jvsena42.loopky.presentation.signup.SignupStartUiState
import com.github.jvsena42.loopky.presentation.signup.SignupStartViewModel
import com.github.jvsena42.loopky.presentation.signup.TokenRedeemer
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SignupStartRoute(
    onBack: () -> Unit,
    onSms: () -> Unit,
    onLightning: () -> Unit,
    onInviteCode: () -> Unit,
    onCreateLocally: () -> Unit,
    redeemer: TokenRedeemer = TokenRedeemer.PubkyRing,
) {
    // Keyed on the redeemer so switching spender rebuilds the ViewModel rather than reusing one
    // that already decided Ring was required.
    val viewModel: SignupStartViewModel = koinViewModel(key = redeemer.name) { parametersOf(redeemer) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SignupStartEffect.OpenInstallPage -> {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, effect.url.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    // Installing Ring happens in the Play Store, so the answer to "is it there yet" can only
    // change while this screen is away. The ViewModel no-ops unless it is currently blocked.
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose { }
    }

    SignupStartScreen(
        state = state,
        onBack = onBack,
        onSms = onSms,
        onLightning = onLightning,
        onInviteCode = onInviteCode,
        onCreateLocally = onCreateLocally,
        // The local flow must not offer itself.
        showCreateLocally = redeemer == TokenRedeemer.PubkyRing,
        onInstallRing = viewModel::onInstallRingClick,
    )
}

@Composable
private fun SignupStartScreen(
    state: SignupStartUiState,
    onBack: () -> Unit,
    onSms: () -> Unit,
    onLightning: () -> Unit,
    onInviteCode: () -> Unit,
    onCreateLocally: () -> Unit,
    showCreateLocally: Boolean,
    onInstallRing: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_start_title),
        subtitle = stringResource(R.string.signup_start_subtitle),
        onBack = onBack,
    ) {
        if (!state.isRingInstalled) {
            RingRequiredCard(onInstallRing = onInstallRing, onCreateLocally = onCreateLocally)
            Spacer(Modifier.height(24.dp))
        }

        // Every method below ends in a token only Pubky Ring can spend, so while it is missing
        // they all say the same thing rather than each screen discovering it separately.
        val unavailableLabel = if (state.hasRedeemer) {
            stringResource(R.string.signup_card_unavailable)
        } else {
            stringResource(R.string.signup_card_needs_ring)
        }

        MethodCard(
            title = stringResource(R.string.signup_sms_card_title),
            price = stringResource(R.string.signup_sms_card_price),
            note = stringResource(R.string.signup_sms_card_note),
            enabled = state.isSmsEnabled,
            unavailableLabel = unavailableLabel,
            testTag = "signup_method_sms",
            onClick = onSms,
        )
        Spacer(Modifier.height(16.dp))
        MethodCard(
            title = stringResource(R.string.signup_lightning_card_title),
            // Two strings rather than concatenation: the parenthetical belongs elsewhere in
            // other languages. No quote — in flight, failed, or geoblocked — renders exactly the
            // sats-only string it always did.
            price = state.lightningPriceSat
                ?.let { sats ->
                    state.fiatPrice
                        ?.let { stringResource(R.string.signup_lightning_card_price_fiat, sats, it) }
                        ?: stringResource(R.string.signup_lightning_card_price, sats)
                }
                ?: stringResource(R.string.signup_lightning_card_price_unknown),
            note = stringResource(R.string.signup_lightning_card_note),
            enabled = state.isLightningEnabled,
            unavailableLabel = unavailableLabel,
            testTag = "signup_method_lightning",
            onClick = onLightning,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(
            onClick = onInviteCode,
            enabled = state.hasRedeemer,
            modifier = Modifier.testTag("signup_method_invite"),
        ) {
            Text(
                text = stringResource(R.string.signup_invite_link),
                // Explicit rather than inherited: a coloured `Text` overrides the button's own
                // disabled tint, and a link that looks live but does nothing is worse than none.
                color = if (state.hasRedeemer) colors.accentSecondary else colors.foregroundMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Offered whether or not Ring is installed. It used to live only inside RingRequiredCard,
        // which meant the local route existed *only* for users who did not have Ring — someone who
        // has it could never choose to keep the key here, and the choice this issue is about was
        // invisible to them.
        if (showCreateLocally) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onCreateLocally,
                modifier = Modifier.testTag("signup_create_locally_option"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.foregroundSecondary),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_create_local),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * The gate that has to come before Homegate is asked anything.
 *
 * A token is single-use and costs an SMS attempt or sats, and only Pubky Ring can redeem one — so
 * this is stated *here*, on the way in, rather than at the hand-off after the user has paid.
 */
@Composable
private fun RingRequiredCard(onInstallRing: () -> Unit, onCreateLocally: () -> Unit) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.accentPrimary, RoundedCornerShape(16.dp))
            .background(colors.accentPrimarySoft, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag("signup_ring_required"),
    ) {
        Text(
            text = stringResource(R.string.signup_ring_required_title),
            modifier = Modifier.testTag("signup_ring_required_title"),
            color = colors.foregroundPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.signup_ring_required_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))
        LoopkyPrimaryButton(
            label = stringResource(R.string.signup_ring_required_install),
            onClick = onInstallRing,
            modifier = Modifier.testTag("signup_ring_install"),
        )
        Spacer(Modifier.height(8.dp))
        // No "I've installed it" button: coming back from the Play Store resumes the screen, and
        // the check runs again then.
        Text(
            text = stringResource(R.string.signup_ring_required_return),
            color = colors.foregroundMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        // The way out of what used to be a dead end. Ring is still the recommendation above; this
        // exists so someone who will not install a second app is not simply stuck (#147).
        TextButton(
            onClick = onCreateLocally,
            modifier = Modifier.testTag("signup_create_locally"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
        ) {
            Text(
                text = stringResource(R.string.signup_create_locally),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * A disabled card still says *why*, and is never hidden — "it isn't offered here" is answerable,
 * whereas a missing row leaves the user asking why they cannot see what a friend can.
 */
@Composable
private fun MethodCard(
    title: String,
    price: String,
    note: String,
    enabled: Boolean,
    unavailableLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(16.dp))
            .background(colors.surfaceCard, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp)
            .testTag(testTag),
    ) {
        Text(
            text = title,
            color = if (enabled) colors.foregroundPrimary else colors.foregroundMuted,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Tagged: this is the value that shows which environment the build is talking to —
            // staging and production quote different prices.
            text = if (enabled) price else unavailableLabel,
            modifier = Modifier.testTag(testTag + "_price"),
            color = if (enabled) colors.accentPrimary else colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        // Deliberately no storage figure. The quota is set by the signup token the homeserver
        // issues (`storage_quota_mb`), so it varies by token and by server — printing a number
        // here would be copy that silently goes stale.
        Text(text = note, color = colors.foregroundMuted, fontSize = 12.sp)
    }
}

@Preview
@Composable
private fun SignupStartRingMissingPreview() {
    LoopkyTheme {
        SignupStartScreen(
            state = SignupStartUiState(isLoading = false, isRingInstalled = false, hasRedeemer = false),
            onBack = {},
            onSms = {},
            onLightning = {},
            onInviteCode = {},
            onCreateLocally = {},
            showCreateLocally = true,
            onInstallRing = {},
        )
    }
}
