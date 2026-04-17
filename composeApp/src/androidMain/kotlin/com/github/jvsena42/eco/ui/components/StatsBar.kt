package com.github.jvsena42.eco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.eco.ui.theme.EchoTheme

@Composable
fun StatsBar(
    totalCards: Int,
    dueCards: Int,
    masteredPercent: String,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Total
        StatColumn(
            value = totalCards.toString(),
            label = "Total",
            valueColor = colors.foregroundPrimary,
            mutedColor = colors.foregroundMuted,
            modifier = Modifier.weight(1f),
        )

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(colors.borderSubtle),
        )

        // Due
        StatColumn(
            value = dueCards.toString(),
            label = "Due",
            valueColor = colors.accentPrimary,
            mutedColor = colors.foregroundMuted,
            modifier = Modifier.weight(1f),
        )

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(colors.borderSubtle),
        )

        // Mastered
        StatColumn(
            value = masteredPercent,
            label = "Mastered",
            valueColor = colors.srsGood,
            mutedColor = colors.foregroundMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
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
            color = mutedColor,
        )
    }
}
