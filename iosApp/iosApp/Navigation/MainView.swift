import SwiftUI

struct MainView: View {
    @State private var selectedTab: EchoTab = .study

    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                HomeScreen(
                    onOpenDeck: onDeckTap,
                    onCreateDeck: onCreateDeckTap,
                    onBrowseExamples: onImportTap,
                    onStartStudy: {},
                    onSignedOut: onSignedOut
                )
                .tag(EchoTab.study)
                DecksScreen(
                    onDeckTap: onDeckTap,
                    onImportTap: onImportTap,
                    onCreateDeckTap: onCreateDeckTap
                )
                .tag(EchoTab.decks)
                DiscoverView()
                    .tag(EchoTab.discover)
                ProfileView()
                    .tag(EchoTab.profile)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            EchoTabBar(selectedTab: $selectedTab)
        }
    }
}
