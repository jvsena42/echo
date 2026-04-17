import SwiftUI

struct MainView: View {
    @State private var selectedTab: EchoTab = .study

    var greetingName: String
    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                HomeView(greetingName: greetingName, state: .empty)
                    .tag(EchoTab.study)
                DecksView(
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
