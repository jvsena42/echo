package com.github.jvsena42.eco.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.eco.presentation.home.DeckSummary
import com.github.jvsena42.eco.presentation.home.HomeUiState
import com.github.jvsena42.eco.ui.theme.EchoTheme

/**
 * Mirrors Pencil node `xaQR5` — daily study state with hero card + deck list.
 */
@Composable
fun HomeContent(
    state: HomeUiState.Content,
    onStartStudyClick: () -> Unit,
    onDeckClick: (String) -> Unit,
) {
    DueTodayHeroCard(
        dueToday = state.dueToday,
        doneToday = state.doneToday,
        onStartStudyClick = onStartStudyClick,
    )
    TodaysDecksSection(decks = state.decks, onDeckClick = onDeckClick)
}

@Composable
private fun DueTodayHeroCard(
    dueToday: Int,
    doneToday: Int,
    onStartStudyClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val progress = if (dueToday == 0) 0f else (doneToday.toFloat() / dueToday).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x33FF5C00),
                spotColor = Color(0x33FF5C00),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimary)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "DUE TODAY",
            color = colors.accentPrimarySoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dueToday.toString(),
                color = colors.foregroundOnAccent,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 72.sp,
            )
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Text(
                    text = "cards",
                    color = colors.foregroundOnAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "to review",
                    color = colors.accentPrimarySoft,
                    fontSize = 13.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(progress = progress)
            Text(
                text = "$doneToday of $dueToday done · keep going!",
                color = colors.accentPrimarySoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceCard)
                .clickable(onClick = onStartStudyClick)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "\u25B6", color = colors.accentPrimary, fontSize = 16.sp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Start studying",
                    color = colors.accentPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x40FFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress)
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.foregroundOnAccent),
        )
    }
}

@Composable
private fun TodaysDecksSection(
    decks: List<DeckSummary>,
    onDeckClick: (String) -> Unit,
) {
    val colors = EchoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Today's decks",
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "See all",
                color = colors.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        decks.forEach { deck ->
            DeckRow(deck = deck, onClick = { onDeckClick(deck.id) })
        }
    }
}

@Composable
private fun DeckRow(deck: DeckSummary, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0x121A1326),
                spotColor = Color(0x121A1326),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accentPrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = deck.coverInitial.toString(),
                color = colors.accentPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = deck.title,
                color = colors.foregroundPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${deck.dueCount} due · ${deck.cardCount} cards",
                color = colors.foregroundMuted,
                fontSize = 13.sp,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accentPrimary)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = deck.dueCount.toString(),
                color = colors.foregroundOnAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
