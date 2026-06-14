import Foundation

/// The four primary destinations. Drives the native `TabView` in `MainView`.
enum EchoTab: String, CaseIterable {
    case study
    case decks
    case discover
    case profile

    /// SF Symbol for the native tab item.
    var iconName: String {
        switch self {
        case .study: return "flame.fill"
        case .decks: return "square.stack.3d.up.fill"
        case .discover: return "safari.fill"
        case .profile: return "person.fill"
        }
    }

    /// Title-cased label shown under the native tab item.
    var title: String {
        switch self {
        case .study: return "Study"
        case .decks: return "Decks"
        case .discover: return "Discover"
        case .profile: return "Profile"
        }
    }
}
