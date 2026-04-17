package com.github.jvsena42.eco.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.eco.presentation.profile.ProfileEffect
import com.github.jvsena42.eco.presentation.profile.ProfileUiState
import com.github.jvsena42.eco.presentation.profile.ProfileViewModel
import com.github.jvsena42.eco.ui.theme.EchoTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
fun ProfileRoute(
    onSignedOut: () -> Unit = {},
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
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.accentPrimary)
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
        // --- Nav title ---
        Text(
            text = "Profile",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )

        // --- Profile section ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x33FF5C00),
                        spotColor = Color(0x33FF5C00),
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
                text = state.displayName ?: "pk:${state.pubky.take(6)}",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            // Pubky badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.surfaceSecondary)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_share),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.foregroundMuted,
                )
                Text(
                    text = "pk:${state.pubky.take(6)}…${state.pubky.takeLast(6)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foregroundMuted,
                )
            }

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
            // Edit Profile button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x33FF5C00),
                        spotColor = Color(0x33FF5C00),
                    )
                    .clip(CircleShape)
                    .background(colors.accentPrimary)
                    .clickable(onClick = onEditProfileClick)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_edit),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Edit Profile",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Share button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.borderSubtle, CircleShape)
                    .clickable(onClick = onShareClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_share),
                    contentDescription = "Share",
                    tint = colors.foregroundSecondary,
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
                label = "Decks",
                valueColor = colors.foregroundPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileStatColumn(
                value = state.cardCount.toString(),
                label = "Cards",
                valueColor = colors.accentPrimary,
                modifier = Modifier.weight(1f),
            )
            ProfileStatColumn(
                value = state.streakDays.toString(),
                label = "Day streak",
                valueColor = Color(0xFFFFC83D),
                modifier = Modifier.weight(1f),
            )
        }

        // --- Sign out ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.dangerSoft)
                .clickable(onClick = onSignOutClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                contentDescription = null,
                tint = colors.srsAgain,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Sign out",
                color = colors.srsAgain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // --- Error banner ---
    if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(EchoTheme.colors.dangerSoft)
                    .clickable(onClick = onDismissError)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = errorMessage,
                    fontSize = 13.sp,
                    color = EchoTheme.colors.srsAgain,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Dismiss",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = EchoTheme.colors.srsAgain,
                )
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
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.borderSubtle),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Sheet title
            Text(
                text = "Edit Profile",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            // Name field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "NAME",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                BasicTextField(
                    value = editName,
                    onValueChange = onNameChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                        .background(colors.surfacePrimary)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.foregroundPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (editName.isEmpty()) {
                                Text("Your name", fontSize = 15.sp, color = colors.foregroundMuted)
                            }
                            inner()
                        }
                    },
                )
            }

            // Bio field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "BIO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                BasicTextField(
                    value = editBio,
                    onValueChange = onBioChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                        .background(colors.surfacePrimary)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = colors.foregroundPrimary,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.height(100.dp)) {
                            if (editBio.isEmpty()) {
                                Text("Tell us about yourself...", fontSize = 15.sp, color = colors.foregroundMuted)
                            }
                            inner()
                        }
                    },
                )
            }

            // Save button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 24.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x33FF5C00),
                        spotColor = Color(0x33FF5C00),
                    )
                    .clip(CircleShape)
                    .background(
                        if (!isSaving) colors.accentPrimary
                        else colors.accentPrimary.copy(alpha = 0.6f),
                    )
                    .clickable(enabled = !isSaving, onClick = onSaveClick)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_save),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
