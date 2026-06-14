package com.github.jvsena42.echo.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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

    Scaffold(
        // The tab screens each apply their own status-bar inset, and the bottom bar consumes the
        // navigation-bar inset natively. Zero out the Scaffold's content insets so the status-bar
        // height isn't added twice above each page title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            EchoTabBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                },
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
    }
}
