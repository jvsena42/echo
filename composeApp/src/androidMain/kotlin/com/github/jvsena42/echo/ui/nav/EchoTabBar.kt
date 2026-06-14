package com.github.jvsena42.echo.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.github.jvsena42.echo.ui.theme.EchoTheme

/**
 * Bottom navigation built on Material 3 Expressive's [ShortNavigationBar]. We keep the native
 * component (insets, ripple, indicator, expressive selection motion, a11y) and only tint it with
 * Echo's brand tokens. See `design/DESIGN_GUIDELINE.md §4` (native-first implementation).
 */
@Composable
fun EchoTabBar(
    selectedTab: EchoTab,
    onTabSelected: (EchoTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    ShortNavigationBar(
        modifier = modifier,
        containerColor = colors.surfacePrimary,
        contentColor = colors.foregroundPrimary,
    ) {
        EchoTab.entries.forEach { tab ->
            ShortNavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
                colors = ShortNavigationBarItemDefaults.colors(
                    selectedIconColor = colors.foregroundOnAccent,
                    selectedTextColor = colors.foregroundPrimary,
                    selectedIndicatorColor = colors.accentPrimary,
                    unselectedIconColor = colors.foregroundMuted,
                    unselectedTextColor = colors.foregroundMuted,
                ),
                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Preview
@Composable
private fun EchoTabBarPreview() {
    EchoTheme {
        Box {
            EchoTabBar(
                selectedTab = EchoTab.DECKS,
                onTabSelected = {},
            )
        }
    }
}
