package com.github.jvsena42.eco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.github.jvsena42.eco.ui.theme.EchoTheme

/**
 * Mirrors Pencil node `xShKh` — pill, `$accent-primary` fill, soft orange outer shadow,
 * leading icon + bold label.
 */
@Composable
fun EchoPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    val interactive = enabled && !loading
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = CircleShape,
                ambientColor = Color(0x40FF5C00),
                spotColor = Color(0x40FF5C00),
            )
            .clip(CircleShape)
            .background(if (interactive) colors.accentPrimary else colors.accentPrimary.copy(alpha = 0.6f))
            .clickable(enabled = interactive, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.foregroundOnAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = colors.foregroundOnAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        } else {
            if (leadingIcon != null) {
                leadingIcon()
                Text(
                    text = label,
                    color = colors.foregroundOnAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            } else {
                Text(
                    text = label,
                    color = colors.foregroundOnAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        }
    }
}
