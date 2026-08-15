import SwiftUI

struct MainView: View {
    @State private var selectedTab: LoopkyTab = .study

    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}

    var body: some View {
        // Native `TabView` → system `UITabBar` (Liquid Glass on the iOS 26 SDK). We only tint it
        // with Loopky's accent. See `design/DESIGN_GUIDELINE.md §4` (native-first implementation).
        TabView(selection: $selectedTab) {
            HomeScreen(
                onOpenDeck: onDeckTap,
                onCreateDeck: onCreateDeckTap,
                onBrowseExamples: onImportTap,
                onStartStudy: {},
                onSignedOut: onSignedOut
            )
            .tabItem { Label(LoopkyTab.study.title, systemImage: LoopkyTab.study.iconName) }
            .tag(LoopkyTab.study)

            DecksScreen(
                onDeckTap: onDeckTap,
                onImportTap: onImportTap,
                onCreateDeckTap: onCreateDeckTap
            )
            .tabItem { Label(LoopkyTab.decks.title, systemImage: LoopkyTab.decks.iconName) }
            .tag(LoopkyTab.decks)

            DiscoverView()
                .tabItem { Label(LoopkyTab.discover.title, systemImage: LoopkyTab.discover.iconName) }
                .tag(LoopkyTab.discover)

            ProfileView()
                .tabItem { Label(LoopkyTab.profile.title, systemImage: LoopkyTab.profile.iconName) }
                .tag(LoopkyTab.profile)
        }
        .tint(LoopkyColor.accentPrimary)
        .toolbarBackground(LoopkyColor.navBarBackground, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        // Tab screens render their own in-content titles, so hide the NavigationStack's empty
        // navigation bar — otherwise it reserves space above each page title.
        .navigationBarHidden(true)
    }
}
