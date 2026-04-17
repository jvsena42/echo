package com.github.jvsena42.eco.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.jvsena42.eco.ui.decks.DecksScreen
import com.github.jvsena42.eco.ui.discover.DiscoverScreen
import com.github.jvsena42.eco.ui.home.HomeRoute
import com.github.jvsena42.eco.ui.profile.ProfileScreen

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(EchoTab.STUDY) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTab) {
            EchoTab.STUDY -> HomeRoute()
            EchoTab.DECKS -> DecksScreen()
            EchoTab.DISCOVER -> DiscoverScreen()
            EchoTab.PROFILE -> ProfileScreen()
        }

        EchoTabBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
