import SwiftUI

enum EchoTab: String, CaseIterable {
    case study = "STUDY"
    case decks = "DECKS"
    case discover = "DISCOVER"
    case profile = "PROFILE"

    var iconName: String {
        switch self {
        case .study: return "flame.fill"
        case .decks: return "square.stack.3d.up.fill"
        case .discover: return "safari.fill"
        case .profile: return "person.fill"
        }
    }
}

struct EchoTabBar: View {
    @Binding var selectedTab: EchoTab

    private let pillColor = Color(red: 26 / 255, green: 19 / 255, blue: 38 / 255)
    private let inactiveColor = Color(red: 154 / 255, green: 147 / 255, blue: 163 / 255)

    var body: some View {
        HStack(spacing: 0) {
            ForEach(EchoTab.allCases, id: \.self) { tab in
                tabItem(tab: tab)
            }
        }
        .padding(4)
        .frame(height: 62)
        .background(pillColor)
        .clipShape(Capsule())
        .padding(.horizontal, 21)
        .padding(.bottom, 21)
        .padding(.top, 12)
    }

    @ViewBuilder
    private func tabItem(tab: EchoTab) -> some View {
        let isSelected = tab == selectedTab
        let contentColor = isSelected ? Color.white : inactiveColor

        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                selectedTab = tab
            }
        } label: {
            VStack(spacing: 4) {
                Image(systemName: tab.iconName)
                    .font(.system(size: 18))
                    .frame(width: 20, height: 20)
                Text(tab.rawValue)
                    .font(.system(size: 10, weight: isSelected ? .bold : .semibold))
                    .tracking(0.5)
            }
            .foregroundColor(contentColor)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                Capsule()
                    .fill(isSelected ? EchoColor.accentPrimary : Color.clear)
            )
        }
        .buttonStyle(.plain)
    }
}
