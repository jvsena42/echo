package com.github.jvsena42.eco.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.jvsena42.eco.ui.decks.DecksRoute
import com.github.jvsena42.eco.ui.discover.DiscoverScreen
import com.github.jvsena42.eco.ui.home.HomeRoute
import com.github.jvsena42.eco.ui.profile.ProfileRoute
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onNavigateDeckDetail: (String) -> Unit = {},
    onNavigateCreateDeck: () -> Unit = {},
    onNavigateImport: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { EchoTab.entries.size })
    val scope = rememberCoroutineScope()
    val selectedTab = EchoTab.entries[pagerState.currentPage]

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (EchoTab.entries[page]) {
                EchoTab.STUDY -> HomeRoute(
                    onOpenDeck = onNavigateDeckDetail,
                    onCreateDeck = onNavigateCreateDeck,
                )
                EchoTab.DECKS -> DecksRoute(
                    onDeckClick = onNavigateDeckDetail,
                    onImportClick = onNavigateImport,
                    onCreateDeckClick = onNavigateCreateDeck,
                )
                EchoTab.DISCOVER -> DiscoverScreen()
                EchoTab.PROFILE -> ProfileRoute(onSignedOut = onSignOut)
            }
        }

        EchoTabBar(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
