package com.github.jvsena42.echo.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.presentation.settings.SettingsEffect
import com.github.jvsena42.echo.presentation.settings.SettingsUiState
import com.github.jvsena42.echo.presentation.settings.SettingsViewModel
import com.github.jvsena42.echo.ui.components.EchoLoadingScreen
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun SettingsRoute(
    onBack: () -> Unit = {},
    onSignedOut: () -> Unit = {},
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val viewModel = koinInject<SettingsViewModel> { parametersOf(appVersion) }
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    val currentSignedOut by rememberUpdatedState(onSignedOut)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                SettingsEffect.SignedOut -> currentSignedOut()
                is SettingsEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onCopyPubkyClick = viewModel::onCopyPubkyClick,
        onSignOutClick = viewModel::onSignOutClick,
    )
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onCopyPubkyClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfacePrimary),
        ) {
            EchoLoadingScreen(message = stringResource(R.string.settings_loading))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Header: back + title ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_content_description),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
        }

        // --- Identity section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_identity))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary),
        ) {
            SettingsValueRow(
                label = stringResource(R.string.settings_pubky_label),
                value = if (state.pubky.isNotBlank()) {
                    stringResource(
                        R.string.settings_pubky_truncated,
                        state.pubky.take(6),
                        state.pubky.takeLast(6),
                    )
                } else {
                    stringResource(R.string.settings_not_signed_in)
                },
                trailing = {
                    if (state.pubky.isNotBlank()) {
                        FilledTonalButton(
                            onClick = onCopyPubkyClick,
                            modifier = Modifier.testTag("settings_copy_pubky"),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surfaceCard,
                                contentColor = colors.foregroundMuted,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_copy),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
            )
            SettingsDivider()
            SettingsValueRow(
                label = stringResource(R.string.settings_homeserver_label),
                value = state.homeserver.ifBlank { stringResource(R.string.settings_homeserver_unknown) },
            )
        }

        // --- About section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_about))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary),
        ) {
            SettingsValueRow(
                label = stringResource(R.string.settings_app_version_label),
                value = state.appVersion.ifBlank { stringResource(R.string.settings_app_version_unknown) },
            )
        }

        // --- Sign out ---
        FilledTonalButton(
            onClick = { showSignOutDialog = true },
            modifier = Modifier
                .testTag("settings_signout")
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.dangerSoft,
                contentColor = colors.srsAgain,
            ),
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_sign_out),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // --- Sign out confirmation ---
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            containerColor = colors.surfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.settings_sign_out_dialog_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_sign_out_dialog_message),
                    fontSize = 14.sp,
                    color = colors.foregroundSecondary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOutClick()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_sign_out),
                        color = colors.srsAgain,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(text = stringResource(R.string.settings_cancel), color = colors.foregroundSecondary)
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = EchoTheme.colors.foregroundMuted,
    )
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    trailing: @Composable () -> Unit = {},
) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
                color = colors.foregroundMuted,
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundPrimary,
            )
        }
        trailing()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = EchoTheme.colors.borderSubtle,
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    EchoTheme {
        SettingsScreen(
            state = SettingsUiState(
                isLoading = false,
                pubky = "abcdef1234567890abcdef",
                displayName = "Ada Lovelace",
                homeserver = "homeserver.pubky.org",
                appVersion = "1.0.0",
            ),
            onBack = {},
            onCopyPubkyClick = {},
            onSignOutClick = {},
        )
    }
}
