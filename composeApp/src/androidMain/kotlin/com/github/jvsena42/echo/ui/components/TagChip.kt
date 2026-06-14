package com.github.jvsena42.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.echo.ui.theme.EchoTheme

/**
 * Echo tag chip: native Material 3 [InputChip] tinted with the secondary accent and keeping the
 * Echo `#` prefix as a brand touch. Removable when [onRemove] is supplied.
 */
@Composable
fun TagChip(
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val colors = EchoTheme.colors
    InputChip(
        selected = selected,
        onClick = onClick ?: {},
        modifier = modifier,
        label = {
            Text(
                text = "#$tag",
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
            )
        },
        trailingIcon = onRemove?.let {
            {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove tag",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = it),
                )
            }
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = colors.accentSecondarySoft,
            labelColor = colors.accentSecondary,
            trailingIconColor = colors.accentSecondary,
            selectedContainerColor = colors.accentSecondary,
            selectedLabelColor = colors.foregroundOnAccent,
            selectedTrailingIconColor = colors.foregroundOnAccent,
        ),
        border = null,
    )
}

@Preview
@Composable
private fun TagChipPreview() {
    EchoTheme {
        Row(
            modifier = Modifier
                .background(EchoTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            Column {
                TagChip(tag = "spanish")
                Spacer(modifier = Modifier.size(8.dp))
                TagChip(tag = "selected", selected = true)
                Spacer(modifier = Modifier.size(8.dp))
                TagChip(tag = "removable", onRemove = {})
            }
        }
    }
}
