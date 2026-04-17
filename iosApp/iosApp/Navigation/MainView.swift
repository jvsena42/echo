import SwiftUI

struct MainView: View {
    @State private var selectedTab: EchoTab = .study

    var greetingName: String

    var body: some View {
        ZStack(alignment: .bottom) {
            Group {
                switch selectedTab {
                case .study:
                    HomeView(greetingName: greetingName, state: .empty)
                case .decks:
                    DecksView()
                case .discover:
                    DiscoverView()
                case .profile:
                    ProfileView()
                }
            }

            EchoTabBar(selectedTab: $selectedTab)
        }
    }
}
