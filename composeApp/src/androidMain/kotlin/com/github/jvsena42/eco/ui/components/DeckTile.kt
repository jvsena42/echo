package com.github.jvsena42.eco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.eco.ui.theme.EchoTheme

@Composable
fun DeckTile(
    title: String,
    cardCount: Int,
    coverEmoji: String,
    authorLabel: String,
    coverColor: Color = EchoTheme.colors.accentPrimarySoft,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = Color(0x261A1326),
                spotColor = Color(0x261A1326),
            )
            .clip(shape)
            .background(colors.surfaceCard)
            .clickable(onClick = onClick),
    ) {
        // Cover area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    color = coverColor,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = coverEmoji,
                fontSize = 48.sp,
            )
        }

        // Body
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Meta row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$cardCount cards",
                    fontSize = 12.sp,
                    color = colors.foregroundMuted,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "\u00B7",
                    fontSize = 12.sp,
                    color = colors.foregroundMuted,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = authorLabel,
                    fontSize = 12.sp,
                    color = colors.accentSecondary,
                )
            }
        }
    }
}
