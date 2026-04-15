package com.github.jvsena42.eco.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.eco.ui.components.EchoPrimaryButton
import com.github.jvsena42.eco.ui.theme.EchoTheme

/**
 * Mirrors Pencil node `nwHYV` — "No decks yet" empty state.
 */
@Composable
fun HomeEmptyContent(
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x141A1326),
                spotColor = Color(0x141A1326),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.accentPrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "\uD83D\uDCDA", fontSize = 64.sp)
        }
        Text(
            text = "No decks yet",
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Paste a list, import from a file, or start from scratch — your first deck is one tap away.",
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }

    Spacer(Modifier.height(4.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EchoPrimaryButton(
            label = "Create your first deck",
            onClick = onCreateDeckClick,
        )
        SecondaryButton(
            label = "Browse examples",
            onClick = onBrowseExamplesClick,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.accentPrimarySoft)
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.accentPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
