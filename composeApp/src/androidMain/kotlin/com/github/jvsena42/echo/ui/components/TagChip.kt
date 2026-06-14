package com.github.jvsena42.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.echo.ui.theme.EchoTheme

@Composable
fun TagChip(
    tag: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val pillShape = RoundedCornerShape(50)
    val background = if (selected) colors.accentSecondary else colors.accentSecondarySoft
    val foreground = if (selected) colors.foregroundOnAccent else colors.accentSecondary

    Row(
        modifier = modifier
            .clip(pillShape)
            .background(background)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#",
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
            color = foreground,
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = tag,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = foreground,
        )

        if (onRemove != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove tag",
                tint = foreground,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRemove),
            )
        }
    }
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
