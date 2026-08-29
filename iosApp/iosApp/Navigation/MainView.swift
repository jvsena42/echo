import SwiftUI

struct MainView: View {
    @State private var selectedTab: LoopkyTab = .study

    /// `(deckId, authorPubky)`; the author is `nil` for a deck you own.
    var onDeckTap: (String, String?) -> Void = { _, _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}
    var onStartStudy: () -> Void = {}

    /// Search is presented over the tabs rather than pushed: it is a way to reach a screen, not a
    /// place in the tab hierarchy, and dismissing it must return to whatever tab asked for it.
    @State private var isSearching = false

    var body: some View {
        // Native `TabView` → system `UITabBar` (Liquid Glass on the iOS 26 SDK). We only tint it
        // with Loopky's accent, rather than rebuilding the chrome from primitives.
        TabView(selection: $selectedTab) {
            HomeScreen(
                onOpenDeck: { onDeckTap($0, nil) },
                onCreateDeck: onCreateDeckTap,
                onBrowseExamples: onImportTap,
                onStartStudy: onStartStudy,
                onSignedOut: onSignedOut
            )
            .tabItem { Label(LoopkyTab.study.title, systemImage: LoopkyTab.study.iconName) }
            .tag(LoopkyTab.study)

            DecksScreen(
                onDeckTap: { onDeckTap($0, nil) },
                onImportTap: onImportTap,
                onCreateDeckTap: onCreateDeckTap
            )
            .tabItem { Label(LoopkyTab.decks.title, systemImage: LoopkyTab.decks.iconName) }
            .tag(LoopkyTab.decks)

            DiscoverScreen(
                onOpenDeck: { deckId, author in onDeckTap(deckId, author) },
                onSearch: { isSearching = true }
            )
            .tabItem { Label(LoopkyTab.discover.title, systemImage: LoopkyTab.discover.iconName) }
            .tag(LoopkyTab.discover)

            ProfileScreen(onSignedOut: onSignedOut)
                .tabItem { Label(LoopkyTab.profile.title, systemImage: LoopkyTab.profile.iconName) }
                .tag(LoopkyTab.profile)
        }
        .tint(LoopkyColor.accentPrimary)
        .toolbarBackground(LoopkyColor.navBarBackground, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        // Tab screens render their own in-content titles, so hide the NavigationStack's empty
        // navigation bar — otherwise it reserves space above each page title.
        .navigationBarHidden(true)
        .sheet(isPresented: $isSearching) {
            SearchScreen(
                onOpenProfile: { _ in isSearching = false },
                onOpenDeck: { deckId, author in
                    isSearching = false
                    onDeckTap(deckId, author)
                }
            )
        }
    }
}
