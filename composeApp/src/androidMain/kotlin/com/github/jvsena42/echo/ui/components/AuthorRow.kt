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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.echo.ui.theme.EchoTheme

@Composable
fun AuthorRow(
    name: String?,
    pubky: String,
    initial: Char,
    modifier: Modifier = Modifier,
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

        // Name column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: pubky,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pubky,
                fontSize = 11.sp,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Follow button
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
                text = if (isFollowing) "Following" else "Follow",
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = if (isFollowing) colors.accentSecondary else colors.foregroundOnAccent,
            )
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
        }
    }
}
