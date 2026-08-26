package com.github.jvsena42.loopky.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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
import com.github.jvsena42.loopky.presentation.onboarding.RingHandoff
import com.github.jvsena42.loopky.ui.components.FoxPlate
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.QrCode
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LICENSE_URL
import com.github.jvsena42.loopky.ui.util.PRIVACY_POLICY_URL
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.rememberAppVersion
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingRoute(
    onNavigateHome: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onUnregistered: (String) -> Unit,
) {
    val viewModel = koinViewModel<OnboardingViewModel>()
    OnboardingScreen(
        viewModel = viewModel,
        onNavigateHome = onNavigateHome,
        onCreatePubky = onCreatePubky,
        onRestore = onRestore,
        onUnregistered = onUnregistered,
    )
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onNavigateHome: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onUnregistered: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnNavigateHome by rememberUpdatedState(onNavigateHome)
    val currentOnUnregistered by rememberUpdatedState(onUnregistered)

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
                is OnboardingEffect.NavigateUnregistered -> currentOnUnregistered(effect.pubky)
            }
        }
    }

    OnboardingContent(
        state = state,
        onSignInClick = viewModel::onSignInClick,
        onCreatePubky = onCreatePubky,
        onRestore = onRestore,
        onOpenRingHere = viewModel::onOpenRingOnThisDevice,
        onCancelSignIn = viewModel::onCancelSignIn,
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onSignInClick: (RingHandoff) -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onOpenRingHere: () -> Unit,
    onCancelSignIn: () -> Unit,
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
    // asked once per account, which is when consent is actually meant to be given. Starts ticked —
    // the policy is stated in the label above it, and un-ticking is the deliberate act.
    var policyAccepted by rememberSaveable { mutableStateOf(true) }

    val widthClass = windowWidthClass()
    // The whole reason this screen knows about window size. A phone's key is in Ring on that same
    // phone, so the deeplink is the shortest path; a tablet's owner keeps their key on their phone,
    // where the deeplink cannot reach, so the way in is a code that phone can scan. Ring being
    // installed *here* doesn't change it — the panel offers that as a second option rather than
    // guessing, because a tablet that happens to have Ring may still not have this user's key.
    val handoff = if (widthClass.isAtLeastMedium) RingHandoff.AnotherDevice else RingHandoff.ThisDevice
    val awaitingScan = (state as? OnboardingUiState.AwaitingApproval)
        ?.takeIf { it.handoff == RingHandoff.AnotherDevice }

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

        // Landscape tablets get the hero and the sign-in side by side. Stacked, the same content
        // on a 1280x800 window puts the fox against the ceiling and the button against the floor
        // with a screen's worth of cream between them; side by side each half is a normal size.
        if (widthClass.isExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hero(modifier = Modifier.widthIn(max = HERO_MAX_WIDTH))
                SignInPanel(
                    state = state,
                    awaitingScan = awaitingScan,
                    isWorking = isWorking,
                    policyAccepted = policyAccepted,
                    onPolicyAcceptedChange = { policyAccepted = it },
                    onSignInClick = { onSignInClick(handoff) },
                    onCreatePubky = onCreatePubky,
                    onRestore = onRestore,
                    onOpenRingHere = onOpenRingHere,
                    onCancelSignIn = onCancelSignIn,
                    // Scrollable, because this Row bounds the panel to the window height and a
                    // Column that overflows a bounded parent clips in silence — no error, no
                    // ellipsis, just a button sliced in half. A landscape phone is the tightest
                    // case, and the panel grew a third door (#147).
                    modifier = Modifier
                        .widthIn(max = PaneWidth.Focused)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Hero()
            }
            SignInPanel(
                state = state,
                awaitingScan = awaitingScan,
                isWorking = isWorking,
                policyAccepted = policyAccepted,
                onPolicyAcceptedChange = { policyAccepted = it },
                onSignInClick = { onSignInClick(handoff) },
                onCreatePubky = onCreatePubky,
                onRestore = onRestore,
                onOpenRingHere = onOpenRingHere,
                onCancelSignIn = onCancelSignIn,
                modifier = Modifier.contentPane(PaneWidth.Focused),
            )
        }

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

/** Fox, tagline, subtitle — the half of the screen that is pure brand. */
@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier,
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
}

/**
 * Everything that acts: the call to action (or the QR handoff that replaces it) plus the consent
 * gate that governs it.
 *
 * One composable rather than two so the two layouts above cannot drift — the consent tick and the
 * button it enables have to stay together, and on the wide layout they belong in the same column
 * rather than one of them stranded under the hero.
 */
@Composable
private fun SignInPanel(
    state: OnboardingUiState,
    awaitingScan: OnboardingUiState.AwaitingApproval?,
    isWorking: Boolean,
    policyAccepted: Boolean,
    onPolicyAcceptedChange: (Boolean) -> Unit,
    onSignInClick: () -> Unit,
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
    onOpenRingHere: () -> Unit,
    onCancelSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (awaitingScan != null) {
            RingScanPanel(
                authUrl = awaitingScan.authUrl,
                ringInstalledHere = awaitingScan.ringInstalledHere,
                onOpenRingHere = onOpenRingHere,
                onCancel = onCancelSignIn,
            )
        } else {
            CtaBlock(
                state = state,
                isWorking = isWorking,
                policyAccepted = policyAccepted,
                onSignInClick = onSignInClick,
                onCreatePubky = onCreatePubky,
                onRestore = onRestore,
            )
            // Under the calls to action: the gate has to be visible before the buttons are usable,
            // but it is fine print rather than a step, and putting it between the hero and the
            // primary button pushed the thing people came here to tap down the page.
            PolicyConsentRow(
                accepted = policyAccepted,
                enabled = !isWorking,
                onAcceptedChange = onPolicyAcceptedChange,
            )
        }
    }
}

/**
 * The tablet way in: the pending authorisation as a QR code for Pubky Ring on the user's phone.
 *
 * The code encodes the same one-shot `pubkyauth://` URL the deeplink would have carried, and the
 * relay poll behind it is the same one — Ring does not care whether it was opened by a tap here or
 * a camera over there. The consent tick is deliberately absent: it was already ticked to get to
 * this state, and re-asking mid-handoff would be a second gate on a decision already made.
 */
@Composable
private fun RingScanPanel(
    authUrl: String,
    ringInstalledHere: Boolean,
    onOpenRingHere: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("onboarding_ring_qr")
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_qr_title),
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_qr_body),
            color = colors.foregroundSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        // A white plate under the code regardless of theme: a QR inverted for dark mode is not a
        // QR any scanner will read, and the quiet zone is this padding rather than encoded margin.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            QrCode(content = authUrl, size = QR_SIZE)
        }
        Text(
            text = stringResource(R.string.onboarding_qr_waiting),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        // Only when something on this device actually claims pubkyauth://. Offering it otherwise
        // is a button that opens nothing, on the one screen where a dead end means the user cannot
        // get into the app at all.
        if (ringInstalledHere) {
            LoopkySecondaryButton(
                text = stringResource(R.string.onboarding_qr_open_here),
                onClick = onOpenRingHere,
                modifier = Modifier.testTag("onboarding_qr_open_here"),
            )
        }
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(authUrl))
                Toast.makeText(context, R.string.onboarding_qr_copied, Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_qr_copy),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag("onboarding_qr_cancel"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.foregroundMuted),
        ) {
            Text(
                text = stringResource(R.string.onboarding_qr_cancel),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
    onCreatePubky: () -> Unit,
    onRestore: () -> Unit,
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
                is OnboardingUiState.AwaitingApproval,
                -> stringResource(R.string.onboarding_signin_waiting)
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
        // token-gated homeserver most new users do not have one. Deliberately never disabled — the
        // signup flow behind it is where a missing Pubky Ring is handled, so a dead-end here is
        // the one thing that leaves a new user with nowhere to go.
        TextButton(
            onClick = onCreatePubky,
            modifier = Modifier.testTag("onboarding_create_pubky"),
            // Purple rather than the brand orange: the primary button directly above it is orange,
            // and two orange calls to action stacked read as one control with a stray second line.
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_create_pubky),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // The third door, and it matters as much as the second: today a user who *has* a pubky but
        // whose Pubky Ring is on a lost or dead phone has no route back into Loopky at all. Ranked
        // below the other two because it is the least common case, not because it is a fallback —
        // for the person who needs it, it is the only thing on this screen that works.
        TextButton(
            onClick = onRestore,
            modifier = Modifier.testTag("onboarding_restore"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.foregroundSecondary),
        ) {
            Text(
                text = stringResource(R.string.onboarding_restore),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * The consent gate on sign-in.
 *
 * Google Play requires the privacy policy to be agreed to at the point an account is created, so it
 * is stated on this screen rather than behind a link somewhere in Settings, and un-ticking it
 * blocks sign-in. It starts ticked; the create-account path stays live either way, since the flow
 * behind it asks again before anything is created.
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
            onCreatePubky = {},
            onRestore = {},
            onOpenRingHere = {},
            onCancelSignIn = {},
        )
    }
}

private val QR_SIZE = 220.dp
private val HERO_MAX_WIDTH = 420.dp
