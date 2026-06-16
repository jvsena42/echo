package com.github.jvsena42.echo.ui.profile

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.presentation.profile.ProfileEffect
import com.github.jvsena42.echo.presentation.profile.ProfileUiState
import com.github.jvsena42.echo.presentation.profile.ProfileViewModel
import com.github.jvsena42.echo.ui.components.EchoLoadingScreen
import com.github.jvsena42.echo.ui.components.EchoPrimaryButton
import com.github.jvsena42.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun ProfileRoute(
    onSignedOut: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    DisposableEffect(viewModel) { onDispose { viewModel.onDispose() } }

    val currentSignedOut by rememberUpdatedState(onSignedOut)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                ProfileEffect.NavigateToOnboarding -> currentSignedOut()
                is ProfileEffect.ShareProfile -> { /* TODO: launch share intent */ }
                is ProfileEffect.ShowError -> { errorMessage = effect.message }
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        errorMessage = errorMessage,
        onOpenSettings = onOpenSettings,
        onEditProfileClick = viewModel::onEditProfileClick,
        onShareClick = viewModel::onShareClick,
        onSignOutClick = viewModel::onSignOutClick,
        onDismissEditSheet = viewModel::onDismissEditSheet,
        onEditNameChanged = viewModel::onEditNameChanged,
        onEditBioChanged = viewModel::onEditBioChanged,
        onSaveClick = viewModel::onSaveClick,
        onDismissError = { errorMessage = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    state: ProfileUiState,
    errorMessage: String?,
    onOpenSettings: () -> Unit,
    onEditProfileClick: () -> Unit,
    onShareClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onDismissEditSheet: () -> Unit,
    onEditNameChanged: (String) -> Unit,
    onEditBioChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = EchoTheme.colors

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfacePrimary),
        ) {
            EchoLoadingScreen(message = stringResource(R.string.profile_loading))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Nav title + settings entry point ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
            OutlinedIconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .testTag("profile_settings")
                    .size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundSecondary,
                ),
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.profile_settings_content_description),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // --- Profile section ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Avatar (Echo-brand custom)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = colors.shadowAccent,
                        spotColor = colors.shadowAccent,
                    )
                    .clip(CircleShape)
                    .background(colors.accentPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.avatarInitial.toString(),
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            // Display name
            Text(
                text = state.displayName ?: stringResource(R.string.profile_pubky_short, state.pubky.take(6)),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            // Pubky badge
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(
                            R.string.profile_pubky_truncated,
                            state.pubky.take(6),
                            state.pubky.takeLast(6),
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_share),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                },
                shape = RoundedCornerShape(50),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colors.surfaceSecondary,
                    labelColor = colors.foregroundMuted,
                    leadingIconContentColor = colors.foregroundMuted,
                ),
                border = null,
            )

            // Bio
            val bio = state.bio
            if (!bio.isNullOrBlank()) {
                Text(
                    text = bio,
                    fontSize = 13.sp,
                    color = colors.foregroundSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.width(280.dp),
                )
            }
        }

        // --- Action row ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EchoPrimaryButton(
                label = stringResource(R.string.profile_edit_profile),
                onClick = onEditProfileClick,
                modifier = Modifier.weight(1f),
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_edit),
                        contentDescription = null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )

            // Share button
            OutlinedIconButton(
                onClick = onShareClick,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundSecondary,
                ),
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_share),
                    contentDescription = stringResource(R.string.profile_share_content_description),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // --- Stats card ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileStatColumn(
                value = state.deckCount.toString(),
                label = stringResource(R.string.profile_stat_decks),
                valueColor = colors.foregroundPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileStatColumn(
                value = state.cardCount.toString(),
                label = stringResource(R.string.profile_stat_cards),
                valueColor = colors.accentPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileStatColumn(
                value = state.streakDays.toString(),
                label = stringResource(R.string.profile_stat_day_streak),
                valueColor = Color(0xFFFFC83D),
                modifier = Modifier.weight(1f),
            )
        }

        // --- Sign out ---
        FilledTonalButton(
            onClick = onSignOutClick,
            modifier = Modifier
                .testTag("profile_signout")
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
                text = stringResource(R.string.profile_sign_out),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // --- Error snackbar ---
    if (errorMessage != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar(
                containerColor = colors.dangerSoft,
                contentColor = colors.srsAgain,
                action = {
                    TextButton(
                        onClick = onDismissError,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.srsAgain),
                    ) {
                        Text(text = stringResource(R.string.profile_dismiss), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
            ) {
                Text(text = errorMessage, fontSize = 13.sp)
            }
        }
    }

    // --- Edit Profile Sheet ---
    if (state.showEditSheet) {
        EditProfileSheet(
            editName = state.editName,
            editBio = state.editBio,
            isSaving = state.isSaving,
            onDismiss = onDismissEditSheet,
            onNameChanged = onEditNameChanged,
            onBioChanged = onEditBioChanged,
            onSaveClick = onSaveClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(
    editName: String,
    editBio: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
) {
    val colors = EchoTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Sheet title
            Text(
                text = stringResource(R.string.profile_edit_profile_sheet_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            // Name field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_edit_name_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                OutlinedTextField(
                    value = editName,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.foregroundPrimary,
                    ),
                    placeholder = {
                        Text(
                            stringResource(R.string.profile_edit_name_placeholder),
                            fontSize = 15.sp,
                            color = colors.foregroundMuted,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                    ),
                )
            }

            // Bio field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_edit_bio_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                OutlinedTextField(
                    value = editBio,
                    onValueChange = onBioChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = colors.foregroundPrimary,
                        lineHeight = 22.sp,
                    ),
                    placeholder = {
                        Text(stringResource(R.string.profile_edit_bio_placeholder), fontSize = 15.sp, color = colors.foregroundMuted)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                    ),
                )
            }

            // Save button
            EchoPrimaryButton(
                label = stringResource(R.string.profile_save),
                onClick = onSaveClick,
                loading = isSaving,
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_save),
                        contentDescription = null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun ProfileStatColumn(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.W800,
            color = valueColor,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.W500,
            color = colors.foregroundMuted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ProfileScreenPreview() {
    EchoTheme {
        ProfileScreen(
            state = ProfileUiState(
                isLoading = false,
                displayName = "Ada Lovelace",
                pubky = "abcdef1234567890abcdef",
                bio = "Building decks about computing history.",
                avatarInitial = 'A',
                deckCount = 8,
                cardCount = 240,
                streakDays = 12,
            ),
            errorMessage = null,
            onOpenSettings = {},
            onEditProfileClick = {},
            onShareClick = {},
            onSignOutClick = {},
            onDismissEditSheet = {},
            onEditNameChanged = {},
            onEditBioChanged = {},
            onSaveClick = {},
            onDismissError = {},
        )
    }
}
