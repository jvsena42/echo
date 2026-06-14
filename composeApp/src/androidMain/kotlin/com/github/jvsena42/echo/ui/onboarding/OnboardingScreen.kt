package com.github.jvsena42.echo.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.presentation.onboarding.OnboardingEffect
import com.github.jvsena42.echo.presentation.onboarding.OnboardingUiState
import com.github.jvsena42.echo.presentation.onboarding.OnboardingViewModel
import com.github.jvsena42.echo.ui.components.EchoPrimaryButton
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun OnboardingRoute(onNavigateHome: () -> Unit) {
    val viewModel = koinInject<OnboardingViewModel>()
    DisposableEffect(viewModel) {
        onDispose { viewModel.onDispose() }
    }
    OnboardingScreen(
        viewModel = viewModel,
        onNavigateHome = onNavigateHome,
    )
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onNavigateHome: () -> Unit,
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
                        Log.w("Echo/OnboardingScreen", "No handler for ${effect.url} — Pubky Ring not installed")
                        viewModel.onDeeplinkUnavailable()
                    } else {
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.w("Echo/OnboardingScreen", "startActivity ActivityNotFoundException", e)
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
        onRetry = viewModel::onRetry,
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onSignInClick: () -> Unit,
    onGetRingClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = EchoTheme.colors
    val isWorking = state is OnboardingUiState.Starting ||
        state is OnboardingUiState.AwaitingApproval ||
        state is OnboardingUiState.Verifying

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
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "\uD83E\uDD8A", fontSize = 96.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.onboarding_hero_title),
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
            onSignInClick = onSignInClick,
            onGetRingClick = onGetRingClick,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun BrandRow() {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accentPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "\uD83E\uDD8A", fontSize = 24.sp)
        }
        Spacer(Modifier.width(10.dp))
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
    onSignInClick: () -> Unit,
    onGetRingClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EchoPrimaryButton(
            label = when (state) {
                OnboardingUiState.Starting,
                OnboardingUiState.AwaitingApproval -> stringResource(R.string.onboarding_signin_waiting)
                OnboardingUiState.Verifying -> stringResource(R.string.onboarding_signin_verifying)
                else -> stringResource(R.string.onboarding_signin_default)
            },
            onClick = {
                if (state is OnboardingUiState.Error) onRetry() else onSignInClick()
            },
            loading = isWorking,
            enabled = !isWorking,
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
                text = state.message,
                color = colors.danger,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
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

@Preview
@Composable
private fun OnboardingContentPreview() {
    EchoTheme {
        OnboardingContent(
            state = OnboardingUiState.Idle,
            onSignInClick = {},
            onGetRingClick = {},
            onRetry = {},
        )
    }
}
