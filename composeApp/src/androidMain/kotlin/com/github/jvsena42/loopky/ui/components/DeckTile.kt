package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.decks.DeckRelation
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
fun DeckTile(
    title: String,
    cardCount: Int,
    coverEmoji: String,
    /** Caption after the card count — the author's name, or a tag on a profile grid. */
    authorLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverColor: Color = LoopkyTheme.colors.accentPrimarySoft,
    /**
     * How the signed-in user relates to this deck. Drives the badge beside the author name, which
     * is the only thing distinguishing a deck you wrote from one you follow or forked — and they
     * differ in whether they are editable and whether they receive the author's updates.
     */
    relation: DeckRelation = DeckRelation.None,
    /** The author has published changes since this followed deck was last opened. */
    hasUpdate: Boolean = false,
    onAuthorClick: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = colors.shadowElevationXHigh,
                spotColor = colors.shadowElevationXHigh,
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
                    text = pluralStringResource(R.plurals.card_count, cardCount, cardCount),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = (
                        if (onAuthorClick != null) {
                            Modifier.clickable(onClick = onAuthorClick)
                        } else {
                            Modifier
                        }
                        ).weight(1f, fill = false),
                )
                relationBadge(relation)?.let { label ->
                    Spacer(modifier = Modifier.width(6.dp))
                    RelationBadge(label = label, hasUpdate = hasUpdate)
                }
            }
        }
    }
}

/**
 * The badge text for a relation, or null when there is nothing to say. A deck you merely browse
 * ([DeckRelation.None]) carries no badge — the author's name already says whose it is.
 */
@Composable
private fun relationBadge(relation: DeckRelation): String? = when (relation) {
    DeckRelation.Owned -> stringResource(R.string.identity_you_badge)
    DeckRelation.Followed -> stringResource(R.string.deck_tile_followed_badge)
    DeckRelation.Cloned -> stringResource(R.string.deck_tile_cloned_badge)
    DeckRelation.None -> null
}

/**
 * Pill beside the author name. Carries a dot when a followed deck has moved since you last opened
 * it — enough to notice, not enough to nag, and it clears itself when the deck is opened.
 */
@Composable
private fun RelationBadge(label: String, hasUpdate: Boolean) {
    val colors = LoopkyTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (hasUpdate) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentPrimary),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.W700,
            color = colors.accentSecondary,
        )
    }
}

@Preview
@Composable
private fun DeckTilePreview() {
    LoopkyTheme {
        Box(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            DeckTile(
                title = "Spanish Basics",
                cardCount = 42,
                coverEmoji = "🇪🇸",
                authorLabel = "Ada Lovelace",
                onClick = {},
                relation = DeckRelation.Followed,
                hasUpdate = true,
                onAuthorClick = {},
                modifier = Modifier.width(180.dp),
            )
        }
    }
}
