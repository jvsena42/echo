import SwiftUI

struct MainView: View {
    @State private var selectedTab: EchoTab = .study

    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}

    var body: some View {
        // Native `TabView` → system `UITabBar` (Liquid Glass on the iOS 26 SDK). We only tint it
        // with Echo's accent. See `design/DESIGN_GUIDELINE.md §4` (native-first implementation).
        TabView(selection: $selectedTab) {
            HomeScreen(
                onOpenDeck: onDeckTap,
                onCreateDeck: onCreateDeckTap,
                onBrowseExamples: onImportTap,
                onStartStudy: {},
                onSignedOut: onSignedOut
            )
            .tabItem { Label(EchoTab.study.title, systemImage: EchoTab.study.iconName) }
            .tag(EchoTab.study)

            DecksScreen(
                onDeckTap: onDeckTap,
                onImportTap: onImportTap,
                onCreateDeckTap: onCreateDeckTap
            )
            .tabItem { Label(EchoTab.decks.title, systemImage: EchoTab.decks.iconName) }
            .tag(EchoTab.decks)

            DiscoverView()
                .tabItem { Label(EchoTab.discover.title, systemImage: EchoTab.discover.iconName) }
                .tag(EchoTab.discover)

            ProfileView()
                .tabItem { Label(EchoTab.profile.title, systemImage: EchoTab.profile.iconName) }
                .tag(EchoTab.profile)
        }
        .tint(EchoColor.accentPrimary)
        .toolbarBackground(EchoColor.navBarBackground, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
    }
}
