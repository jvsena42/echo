package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.home.DeckSummary
import com.github.jvsena42.loopky.presentation.home.HomeUiState
import com.github.jvsena42.loopky.ui.components.DeckCover
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Mirrors Pencil node `xaQR5` — daily study state with hero card + deck list.
 */
@Composable
fun HomeContent(
    state: HomeUiState.Content,
    onStartStudyClick: () -> Unit,
    onSeeAllDecksClick: () -> Unit,
    onDeckClick: (String) -> Unit,
) {
    if (state.isCaughtUp) {
        CaughtUpHeroCard(nextDueAtMillis = state.nextDueAtMillis)
    } else {
        DueTodayHeroCard(
            dueToday = state.dueToday,
            doneToday = state.doneToday,
            onStartStudyClick = onStartStudyClick,
        )
    }
    TodaysDecksSection(
        decks = state.decks,
        onSeeAllClick = onSeeAllDecksClick,
        onDeckClick = onDeckClick,
    )
}

/**
 * Shown when the user owns decks but has nothing due. Previously this fell through to the
 * zero-decks empty state, which told someone who had just finished a session to "create or
 * import a deck to start your first study session".
 */
@Composable
private fun CaughtUpHeroCard(nextDueAtMillis: Long?) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimarySoft)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "\uD83C\uDF89", fontSize = 40.sp)
        Text(
            text = stringResource(R.string.home_caught_up_title),
            color = colors.foregroundPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = nextDueAtMillis
                ?.let { stringResource(R.string.home_caught_up_next_due, relativeFromNow(it)) }
                ?: stringResource(R.string.home_caught_up_no_next_due),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Coarse "in 4h" / "in 2d" phrasing — an exact timestamp would be noise here. */
@Composable
private fun relativeFromNow(millis: Long): String {
    val delta = (millis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0 -> pluralStringResource(R.plurals.duration_days, days.toInt(), days.toInt())
        hours > 0 -> pluralStringResource(R.plurals.duration_hours, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
    }
}

@Composable
private fun DueTodayHeroCard(
    dueToday: Int,
    doneToday: Int,
    onStartStudyClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val progress = if (dueToday == 0) 0f else (doneToday.toFloat() / dueToday).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = colors.shadowAccent,
                spotColor = colors.shadowAccent,
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimary)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_due_today),
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
                    text = stringResource(R.string.home_cards),
                    color = colors.foregroundOnAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.home_to_review),
                    color = colors.accentPrimarySoft,
                    fontSize = 13.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(progress = progress)
            Text(
                text = stringResource(R.string.home_progress_done, doneToday, dueToday),
                color = colors.accentPrimarySoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Button(
            onClick = onStartStudyClick,
            modifier = Modifier
                .testTag("home_start_study")
                .fillMaxWidth(),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(vertical = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.surfaceCard,
                contentColor = colors.accentPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.home_start_studying),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val colors = LoopkyTheme.colors
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50)),
        color = colors.foregroundOnAccent,
        trackColor = Color(0x40FFFFFF),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun TodaysDecksSection(
    decks: List<DeckSummary>,
    onSeeAllClick: () -> Unit,
    onDeckClick: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_todays_decks),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.home_see_all),
                color = colors.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSeeAllClick)
                    .testTag("home_see_all")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        decks.forEach { deck ->
            DeckRow(deck = deck, onClick = { onDeckClick(deck.id) })
        }
    }
}

@Composable
private fun DeckRow(deck: DeckSummary, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = colors.shadowElevationMedium,
                spotColor = colors.shadowElevationMedium,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeckCover(
            coverImage = deck.coverImage,
            deckId = deck.id,
            authorPubky = deck.authorPubky,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(56.dp),
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
                // Clamped so today's rows are the same height whatever the deck is called; an
                // imported Anki title can run to a sentence.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.home_deck_due_cards,
                    deck.cardCount,
                    deck.dueCount,
                    deck.cardCount,
                ),
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

@Preview
@Composable
private fun HomeContentPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            HomeContent(
                state = HomeUiState.Content(
                    identity = PubkyIdentity("alex1xqz9", "Alex", avatarUrl = null, bio = null),
                    dueToday = 24,
                    doneToday = 9,
                    decks = listOf(
                        DeckSummary(
                            id = "1",
                            title = "Spanish Basics",
                            authorPubky = "alex1xqz9",
                            cardCount = 60,
                            dueCount = 12,
                            coverInitial = 'S',
                        ),
                        DeckSummary(
                            id = "2",
                            title = "Kanji N5",
                            authorPubky = "friend1xqz9",
                            cardCount = 103,
                            dueCount = 8,
                            coverInitial = 'K',
                        ),
                    ),
                ),
                onStartStudyClick = {},
                onSeeAllDecksClick = {},
                onDeckClick = {},
            )
        }
    }
}
