package com.github.jvsena42.loopky.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingEffect
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingUiState
import com.github.jvsena42.loopky.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.loopky.ui.components.FoxPlate
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LICENSE_URL
import com.github.jvsena42.loopky.ui.util.PRIVACY_POLICY_URL
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.rememberAppVersion
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingRoute(onNavigateHome: () -> Unit, onCreatePubky: () -> Unit) {
    val viewModel = koinViewModel<OnboardingViewModel>()
    OnboardingScreen(
        viewModel = viewModel,
        onNavigateHome = onNavigateHome,
        onCreatePubky = onCreatePubky,
    )
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onNavigateHome: () -> Unit,
    onCreatePubky: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnNavigateHome by rememberUpdatedState(onNavigateHome)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is OnboardingEffect.OpenDeeplink -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val canResolve = intent.resolveActivity(context.packageManager) != null
                    if (!canResolve) {
                        Log.w("Loopky/OnboardingScreen", "No handler for ${effect.url} — Pubky Ring not installed")
                        viewModel.onDeeplinkUnavailable()
                    } else {
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.w("Loopky/OnboardingScreen", "startActivity ActivityNotFoundException", e)
                            viewModel.onDeeplinkUnavailable()
                        }
                    }
                }
                is OnboardingEffect.OpenInstallPage -> {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                OnboardingEffect.NavigateHome -> currentOnNavigateHome()
            }
        }
    }

    OnboardingContent(
        state = state,
        onSignInClick = viewModel::onSignInClick,
        onGetRingClick = viewModel::onGetRingClick,
        onCreatePubky = onCreatePubky,
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onSignInClick: () -> Unit,
    onGetRingClick: () -> Unit,
    onCreatePubky: () -> Unit,
) {
    if (state is OnboardingUiState.Restoring) {
        SplashContent()
        return
    }

    val colors = LoopkyTheme.colors
    val isWorking = state is OnboardingUiState.Starting ||
        state is OnboardingUiState.AwaitingApproval ||
        state is OnboardingUiState.Verifying

    // Local rather than in the ViewModel on purpose. OnboardingUiState is a sealed interface over
    // modes (Restoring/Starting/AwaitingApproval/…), so a cross-cutting flag would have to be
    // carried on every one of them to say something none of them is about. Nothing is persisted
    // across launches either: this screen is only reachable while signed out, so the question is
    // asked once per account, which is when consent is actually meant to be given.
    var policyAccepted by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BrandRow()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FoxPlate(
                size = 160.dp,
                shape = CircleShape,
                glyphSize = 96.sp,
                containerColor = colors.accentPrimarySoft,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.brand_tagline),
                color = colors.foregroundPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.onboarding_hero_subtitle),
                color = colors.foregroundSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )
        }

        CtaBlock(
            state = state,
            isWorking = isWorking,
            policyAccepted = policyAccepted,
            onSignInClick = onSignInClick,
            onGetRingClick = onGetRingClick,
            onCreatePubky = onCreatePubky,
        )

        // Foot of the screen, under the calls to action: the gate has to be visible before the
        // buttons are usable, but it is fine print rather than a step, and putting it between the
        // hero and the primary button pushed the thing people came here to tap down the page.
        PolicyConsentRow(
            accepted = policyAccepted,
            enabled = !isWorking,
            onAcceptedChange = { policyAccepted = it },
        )

        // Last, because a bug report from someone who cannot get past this screen is exactly the
        // one where knowing the build matters.
        Text(
            text = stringResource(R.string.onboarding_app_version, rememberAppVersion()),
            modifier = Modifier.testTag("onboarding_app_version"),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BrandRow() {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.onboarding_brand_name),
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun CtaBlock(
    state: OnboardingUiState,
    isWorking: Boolean,
    policyAccepted: Boolean,
    onSignInClick: () -> Unit,
    onGetRingClick: () -> Unit,
    onCreatePubky: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoopkyPrimaryButton(
            label = when (state) {
                OnboardingUiState.Starting,
                OnboardingUiState.AwaitingApproval -> stringResource(R.string.onboarding_signin_waiting)
                OnboardingUiState.Verifying -> stringResource(R.string.onboarding_signin_verifying)
                else -> stringResource(R.string.onboarding_signin_default)
            },
            // Also the recovery path: a failed approval consumes the FFI's auth flow, so retrying
            // means starting a whole new one. Clearing the error first would only cost a tap (#59).
            onClick = onSignInClick,
            loading = isWorking,
            enabled = !isWorking && policyAccepted,
            modifier = Modifier.testTag("onboarding_signin"),
            leadingIcon = {
                Text(
                    text = "\uD83D\uDD11",
                    fontSize = 18.sp,
                )
            },
        )
        Text(
            text = stringResource(R.string.onboarding_no_email_notice),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (state is OnboardingUiState.Error) {
            Text(
                text = errorMessage(state.reason),
                color = colors.danger,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        // The second entry point: signing in assumes an account already exists, and on a
        // token-gated homeserver most new users do not have one. Gated by the same consent, since
        // it is the path that creates an account rather than merely entering one.
        TextButton(
            onClick = onCreatePubky,
            enabled = !isWorking && policyAccepted,
            modifier = Modifier.testTag("onboarding_create_pubky"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_create_pubky),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(
            onClick = onGetRingClick,
            enabled = !isWorking,
            modifier = Modifier.testTag("onboarding_get_ring"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_get_ring),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The consent gate on both account entry points.
 *
 * Google Play requires the privacy policy to be agreed to at the point an account is created, so
 * this sits above the buttons rather than behind a link somewhere in Settings, and blocks them
 * until it is ticked. "Get Pubky Ring" stays live throughout: sending someone to a Play listing is
 * not consent to anything.
 *
 * The two document names inside the sentence are real links. They are located by [indexOf] rather
 * than assembled from fragments so a translation can put them wherever its grammar wants; a name
 * that a translator rewords simply stops being a link, which is a missing underline rather than a
 * broken screen.
 */
@Composable
private fun PolicyConsentRow(
    accepted: Boolean,
    enabled: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    val privacyLabel = stringResource(R.string.onboarding_policy_privacy)
    val licenseLabel = stringResource(R.string.onboarding_policy_license)
    val sentence = stringResource(R.string.onboarding_policy_consent, privacyLabel, licenseLabel)

    val label = remember(sentence, privacyLabel, licenseLabel, colors.accentPrimary) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(
                color = colors.accentPrimary,
                textDecoration = TextDecoration.Underline,
            ),
        )
        buildAnnotatedString {
            append(sentence)
            listOf(privacyLabel to PRIVACY_POLICY_URL, licenseLabel to LICENSE_URL).forEach { (name, url) ->
                val start = sentence.indexOf(name)
                if (start >= 0) {
                    addLink(
                        LinkAnnotation.Url(url, linkStyles) { context.openUrl(url) },
                        start,
                        start + name.length,
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_policy_consent")
            .toggleable(
                value = accepted,
                enabled = enabled,
                role = Role.Checkbox,
                // Off the Checkbox itself so the label is part of the target. Taps on the two
                // links are handled by the text, which sits below this in the hierarchy.
                onValueChange = onAcceptedChange,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = accepted,
            // Null so the Row above owns the click; a Checkbox with its own handler would swallow
            // the tap and leave the label inert.
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accentPrimary,
                uncheckedColor = colors.foregroundMuted,
            ),
        )
        Text(
            text = label,
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * The branded splash. Drawn while the persisted session is being read back, and deliberately a
 * continuation of the system splash window (same cream surface, same fox on the same accent
 * circle, same position) so the two read as one screen — this one just adds the words.
 */
@Composable
private fun SplashContent() {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .padding(horizontal = 32.dp)
            .testTag("splash"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FoxPlate(
            size = 160.dp,
            shape = CircleShape,
            glyphSize = 96.sp,
            containerColor = colors.accentPrimarySoft,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_brand_name),
            color = colors.foregroundPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.brand_tagline),
            color = colors.foregroundSecondary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Preview
@Composable
private fun SplashContentPreview() {
    LoopkyTheme {
        SplashContent()
    }
}

@Preview
@Composable
private fun OnboardingContentPreview() {
    LoopkyTheme {
        OnboardingContent(
            state = OnboardingUiState.Idle,
            onSignInClick = {},
            onGetRingClick = {},
            onCreatePubky = {},
        )
    }
}
