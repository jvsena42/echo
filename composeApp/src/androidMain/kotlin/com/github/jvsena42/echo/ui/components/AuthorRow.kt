package com.github.jvsena42.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.echo.R
import com.github.jvsena42.echo.ui.theme.EchoTheme

@Composable
fun AuthorRow(
    name: String?,
    pubky: String,
    initial: Char,
    modifier: Modifier = Modifier,
    isOwned: Boolean = false,
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
) {
    val colors = EchoTheme.colors
    val pillShape = RoundedCornerShape(50)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accentSecondarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial.uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.W800,
                color = colors.accentSecondary,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Name column — owned decks read "@you" with no pubky subtitle.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isOwned) stringResource(R.string.component_author_row_you) else name ?: pubky,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!isOwned) {
                Text(
                    text = pubky,
                    fontSize = 11.sp,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Follow button — hidden for your own deck.
        if (!isOwned) {
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(
                        if (isFollowing) colors.accentSecondarySoft else colors.accentSecondary,
                    )
                    .clickable(onClick = onFollowClick)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isFollowing) {
                        stringResource(R.string.component_author_row_following)
                    } else {
                        stringResource(R.string.component_author_row_follow)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                    color = if (isFollowing) colors.accentSecondary else colors.foregroundOnAccent,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AuthorRowPreview() {
    EchoTheme {
        Column(
            modifier = Modifier
                .background(EchoTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            AuthorRow(
                name = "Ada Lovelace",
                pubky = "pubky:ada1xqz9...",
                initial = 'A',
                isFollowing = false,
                onFollowClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            AuthorRow(
                name = null,
                pubky = "pubky:byron7yt2...",
                initial = 'B',
                isFollowing = true,
                onFollowClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            AuthorRow(
                name = null,
                pubky = "pubky:you9xqz1...",
                initial = 'Y',
                isOwned = true,
            )
        }
    }
}
