package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The two ways Loopky points at pubky.app.
 *
 * A Loopky account *is* a Pubky account — the same `profile.json`, the same follow graph, the same
 * key — and until now nothing in the app said so out loud. Both of these are deliberately quiet:
 * the network underneath is worth knowing about, but it is not what someone opened a flashcards
 * app to do.
 *
 * Neither builds its own URL. The address comes from the ViewModel, which reads it off
 * `PubkyEnvironment` — a debug build points at staging, where its account actually exists.
 */

/**
 * The disc the mark sits on. pubky.app's own black, because the brand lime is unreadable on
 * anything lighter — but kept small. A full 48dp black circle was the highest-contrast thing on a
 * cream screen, which had a secondary action outshouting the primary one beside it.
 */
private val PUBKY_PLATE = Color(0xFF0B0B0B)

/**
 * The button that leaves for pubky.app: the logo as pubky.app draws it, inside the same outlined
 * 48dp circle Copy and Share use, so it carries no more weight in the row than they do.
 *
 * The mark keeps its brand colours — someone who has seen it elsewhere should recognise it here
 * without reading anything — but only the badge is dark, not the whole button.
 */
@Composable
fun PubkyAppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val label = stringResource(R.string.pubky_app_open_profile)

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(onClick = onClick)
            // The mark carries the meaning visually and has no text of its own, so the label goes
            // on the button rather than on the image inside it.
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        PubkyBadge(size = 26.dp)
    }
}

/**
 * The self-profile call to action: one soft row explaining what the button above it does, for the
 * person who has no reason to know that the key they signed in with is also a social account.
 *
 * A card rather than a banner, and it never claims a Loopky deck appears there — it does not. What
 * travels is the profile and, when they choose to announce one, the post.
 */
@Composable
fun PubkyAppProfileCta(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .clickable(onClick = onClick)
            .testTag("profile_pubky_app_cta")
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same badge the button wears, on the card's own surface rather than filling it.
        PubkyBadge(size = 30.dp)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.pubky_app_cta_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.pubky_app_cta_body),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.foregroundMuted,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = colors.foregroundMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The logo as pubky.app draws it: the lime mark on its black disc, sized to [size].
 *
 * Drawn with [Image] rather than [Icon] because the drawable carries its own brand colour, and an
 * `Icon` would repaint it with the surrounding content colour.
 */
@Composable
private fun PubkyBadge(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PUBKY_PLATE),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_pubky),
            contentDescription = null,
            modifier = Modifier.size(size * MARK_INSET),
        )
    }
}

/** The mark's share of its disc — the logo needs breathing room or it reads as a smudge. */
private const val MARK_INSET = 0.66f

@Preview
@Composable
private fun PubkyAppLinkPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PubkyAppIconButton(onClick = {})
            PubkyAppProfileCta(onClick = {})
        }
    }
}
