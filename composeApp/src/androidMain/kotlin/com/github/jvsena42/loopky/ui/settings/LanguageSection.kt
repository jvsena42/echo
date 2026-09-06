package com.github.jvsena42.loopky.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.locale.AppLocale
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The app-language row and its picker.
 *
 * Deliberately outside [SettingsViewModel]: the language is the device's setting rather than
 * Loopky's (see [AppLocale]), so there is nothing to hold in shared state and nothing to sync. On
 * API 33+ the value on screen is read back from the framework, which means a change made in the
 * system's own Settings screen shows up here without Loopky being told about it.
 */
@Composable
internal fun LanguageSection(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val selected = AppLocale.current(context)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPicker = true }
                .testTag("settings_language")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_language_label),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foregroundPrimary,
                )
                Text(
                    text = selected?.let(AppLocale::displayName)
                        ?: stringResource(R.string.settings_language_system),
                    fontSize = 12.sp,
                    color = colors.foregroundSecondary,
                )
                Text(
                    text = stringResource(
                        if (AppLocale.isDeviceManaged) {
                            R.string.settings_language_description_system
                        } else {
                            R.string.settings_language_description_app
                        },
                    ),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.foregroundMuted,
                )
            }
            FilledTonalButton(
                onClick = { showPicker = true },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundMuted,
                ),
            ) {
                Text(
                    text = stringResource(R.string.settings_language_change),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showPicker) {
        LanguagePickerDialog(
            selected = selected,
            onDismiss = { showPicker = false },
            onPick = { tag ->
                showPicker = false
                if (tag != selected) {
                    AppLocale.set(context, tag)
                    // The framework recreates the activity itself once it owns the preference;
                    // below 33 nothing does, and the screen would keep rendering the old
                    // language until something else happened to recreate it.
                    if (!AppLocale.isDeviceManaged) (context as? Activity)?.recreate()
                }
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        title = {
            Text(
                text = stringResource(R.string.settings_language_dialog_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundPrimary,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // "System default" first and always available: it is the only entry that keeps
                // working when a later release adds a language the device already prefers.
                LanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    isSelected = selected == null,
                    testTag = "settings_language_option_system",
                    onClick = { onPick(null) },
                )
                AppLocale.SUPPORTED.forEach { tag ->
                    LanguageOption(
                        label = AppLocale.displayName(tag),
                        isSelected = selected == tag,
                        testTag = "settings_language_option_$tag",
                        onClick = { onPick(tag) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_language_dialog_cancel),
                    color = colors.foregroundSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

@Composable
private fun LanguageOption(
    label: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accentPrimary),
        )
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.foregroundPrimary,
        )
    }
}
