package com.github.jvsena42.loopky.ui.profile

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.presentation.profile.ProfileEffect
import com.github.jvsena42.loopky.presentation.profile.ProfileUiState
import com.github.jvsena42.loopky.presentation.profile.ProfileViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.ProfileHero
import com.github.jvsena42.loopky.ui.components.ProfileStat
import com.github.jvsena42.loopky.ui.components.ProfileStatsCard
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.shareText
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileRoute(
    onSignedOut: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFollows: (pubky: String, source: FollowSource) -> Unit = { _, _ -> },
) {
    val viewModel = koinViewModel<ProfileViewModel>()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentSignedOut by rememberUpdatedState(onSignedOut)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                ProfileEffect.NavigateToOnboarding -> currentSignedOut()
                is ProfileEffect.ShareProfile -> context.shareText(
                    text = effect.uri,
                    chooserTitle = context.getString(R.string.share_profile_chooser_title),
                )
                is ProfileEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
                is ProfileEffect.ShowError -> { errorMessage = effect.message }
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOpenFollows by rememberUpdatedState(onOpenFollows)
    ProfileScreen(
        state = state,
        errorMessage = errorMessage,
        onOpenSettings = onOpenSettings,
        onOpenFollows = { source ->
            state.identity?.let { currentOpenFollows(it.pubky, source) }
        },
        onEditProfileClick = viewModel::onEditProfileClick,
        onShareClick = viewModel::onShareClick,
        onCopyPubky = viewModel::onCopyPubky,
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
    onOpenFollows: (FollowSource) -> Unit,
    onEditProfileClick: () -> Unit,
    onShareClick: () -> Unit,
    onCopyPubky: () -> Unit,
    onSignOutClick: () -> Unit,
    onDismissEditSheet: () -> Unit,
    onEditNameChanged: (String) -> Unit,
    onEditBioChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    // Settings confirms sign-out and reassures that decks stay on the homeserver; this button
    // used to sign out immediately, which is a surprising outcome for one stray tap.
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.settings_sign_out_dialog_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOutClick()
                    },
                    modifier = Modifier.testTag("profile_signout_confirm"),
                ) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    val colors = LoopkyTheme.colors

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfacePrimary),
        ) {
            LoopkyLoadingScreen(message = stringResource(R.string.profile_loading))
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
        // The same hero someone else's profile draws, so your own picture shows up here too —
        // this screen used to hand-roll an initial-only circle and was the one avatar slot the
        // pubky.app file-URI fix could not reach. No "You" badge: that marks you inside someone
        // else's context, and everything on this tab is already yours.
        val identity = state.identity
        if (identity != null) {
            ProfileHero(
                identity = identity,
                onPubkyClick = onCopyPubky,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --- Action row ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoopkyPrimaryButton(
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
        ProfileStatsCard(
            stats = listOf(
                ProfileStat(
                    value = state.deckCount.toString(),
                    label = stringResource(R.string.profile_stat_decks),
                    valueColor = colors.foregroundPrimary,
                ),
                ProfileStat(
                    value = state.cardCount.toString(),
                    label = stringResource(R.string.profile_stat_cards),
                    valueColor = colors.accentPrimary,
                ),
                ProfileStat(
                    value = state.dueCount.toString(),
                    label = stringResource(R.string.profile_stat_due),
                    valueColor = colors.srsGood,
                ),
            ),
        )

        // --- People card ---
        // A second strip rather than five columns in the first: on a phone that reduces every
        // label to unreadable, and these two answer a different question than your library does.
        val pending = stringResource(R.string.profile_stat_pending)
        ProfileStatsCard(
            stats = listOf(
                ProfileStat(
                    value = state.followingCount?.toString() ?: pending,
                    label = stringResource(R.string.profile_stat_following),
                    valueColor = colors.foregroundPrimary,
                    onClick = { onOpenFollows(FollowSource.FOLLOWING) },
                    testTag = "profile_stat_following",
                ),
                ProfileStat(
                    value = state.followerCount?.toString() ?: pending,
                    label = stringResource(R.string.profile_stat_followers),
                    valueColor = colors.accentPrimary,
                    onClick = { onOpenFollows(FollowSource.FOLLOWERS) },
                    testTag = "profile_stat_followers",
                ),
            ),
        )

        // --- Sign out ---
        FilledTonalButton(
            onClick = { confirmSignOut = true },
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
    val colors = LoopkyTheme.colors

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
            LoopkyPrimaryButton(
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

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun ProfileScreenPreview() {
    LoopkyTheme {
        ProfileScreen(
            state = ProfileUiState(
                isLoading = false,
                identity = PubkyIdentity(
                    pubky = "abcdef1234567890abcdef",
                    displayName = "Ada Lovelace",
                    avatarUrl = null,
                    bio = "Building decks about computing history.",
                ),
                deckCount = 8,
                cardCount = 240,
                dueCount = 12,
            ),
            errorMessage = null,
            onOpenSettings = {},
            onOpenFollows = {},
            onEditProfileClick = {},
            onShareClick = {},
            onCopyPubky = {},
            onSignOutClick = {},
            onDismissEditSheet = {},
            onEditNameChanged = {},
            onEditBioChanged = {},
            onSaveClick = {},
            onDismissError = {},
        )
    }
}
