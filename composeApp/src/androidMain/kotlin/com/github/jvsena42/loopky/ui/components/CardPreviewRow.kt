package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
fun CardPreviewRow(
    frontText: String,
    backText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = colors.shadowElevationLow,
                spotColor = colors.shadowElevationLow,
            )
            .clip(shape)
            .background(colors.surfaceCard)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = frontText,
            fontSize = 15.sp,
            fontWeight = FontWeight.W700,
            color = colors.foregroundPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = backText,
            fontSize = 13.sp,
            color = colors.foregroundMuted,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun CardPreviewRowPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            CardPreviewRow(
                frontText = "Hola",
                backText = "Hello",
                onClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            CardPreviewRow(
                frontText = "Buenos días",
                backText = "Good morning",
            )
        }
    }
}
