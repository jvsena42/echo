package com.github.jvsena42.echo.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.jvsena42.echo.ui.decks.DecksRoute
import com.github.jvsena42.echo.ui.discover.DiscoverRoute
import com.github.jvsena42.echo.ui.home.HomeRoute
import com.github.jvsena42.echo.ui.profile.ProfileRoute
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onNavigateDeckDetail: (deckId: String, author: String?) -> Unit = { _, _ -> },
    onNavigateCreateDeck: () -> Unit = {},
    onNavigateImport: () -> Unit = {},
    onNavigateStudy: (String?) -> Unit = {},
    onNavigateProfile: (String) -> Unit = {},
    onNavigateSettings: () -> Unit = {},
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
                    onOpenDeck = { deckId -> onNavigateDeckDetail(deckId, null) },
                    onCreateDeck = onNavigateCreateDeck,
                    onStartStudy = { onNavigateStudy(null) },
                    onSignedOut = onSignOut,
                )
                EchoTab.DECKS -> DecksRoute(
                    onDeckClick = { deckId -> onNavigateDeckDetail(deckId, null) },
                    onImportClick = onNavigateImport,
                    onCreateDeckClick = onNavigateCreateDeck,
                )
                EchoTab.DISCOVER -> DiscoverRoute(
                    onOpenProfile = onNavigateProfile,
                    onOpenDeck = onNavigateDeckDetail,
                )
                EchoTab.PROFILE -> ProfileRoute(
                    onSignedOut = onSignOut,
                    onOpenSettings = onNavigateSettings,
                )
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
