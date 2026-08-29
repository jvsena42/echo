import SwiftUI

/// How much horizontal room the window has, in the three width buckets Loopky makes decisions on.
///
/// The iOS counterpart of Android's `WindowWidthClass` (#140), deliberately carrying the same three
/// names and the same 600/840 breakpoints so the two platforms stay legible to the same reader.
///
/// **Never ask "is this an iPad".** `UIDevice.current.userInterfaceIdiom` is a fact about the
/// hardware, and every interesting case here is a fact about the *window*: an iPad in Slide Over is
/// a phone-shaped column on the same device that is two panes wide a second later, and rotation
/// moves an iPad between [medium] and [expanded] while the app is running.
///
/// Two signals, because neither is enough on its own. The **size class** is what tells a landscape
/// iPhone from an iPad: an iPhone 17 Pro Max on its side is ~950pt wide and would read as
/// [expanded] by width alone, which would put a two-pane tablet layout on a phone. The **width** is
/// what tells an iPad in portrait from one in landscape — both are `.regular`, and that single
/// bucket cannot express the split Loopky's wide layouts are written against.
enum LoopkyWidthClass {
    /// Phones, and any pane narrow enough that the system calls it compact — Slide Over, and the
    /// smaller half of a Split View.
    case compact

    /// Tablets in portrait. 600pt until 840pt.
    case medium

    /// Tablets in landscape, and a full-width iPad Pro in portrait. 840pt and up.
    case expanded

    /// True once there is room to stand two panes beside each other.
    var isExpanded: Bool { self == .expanded }

    /// True for anything roomier than a phone — the two classes that get tablet treatment.
    var isAtLeastMedium: Bool { self != .compact }

    /// A compact size class pins the answer to [compact] whatever the width says — see the type
    /// docs for why that guard is load-bearing rather than defensive.
    static func of(width: CGFloat, sizeClass: UserInterfaceSizeClass?) -> LoopkyWidthClass {
        guard sizeClass != .compact else { return .compact }
        switch width {
        case ..<mediumLowerBound: return .compact
        case ..<expandedLowerBound: return .medium
        default: return .expanded
        }
    }

    private static let mediumLowerBound: CGFloat = 600
    private static let expandedLowerBound: CGFloat = 840
}

private struct LoopkyWidthClassKey: EnvironmentKey {
    /// Compact, so a view rendered outside `provideWindowSize()` — a `#Preview`, mostly — gets the
    /// phone layout rather than a tablet one it has no room for.
    static let defaultValue: LoopkyWidthClass = .compact
}

extension EnvironmentValues {
    var loopkyWidthClass: LoopkyWidthClass {
        get { self[LoopkyWidthClassKey.self] }
        set { self[LoopkyWidthClassKey.self] = newValue }
    }
}

extension View {
    /// Publishes the window's width class to the tree below. Applied once, at the root.
    ///
    /// Read through the environment rather than threaded down as a parameter, for the reason
    /// Android reads it from a CompositionLocal: the value is needed at the leaves — a grid's
    /// column count, a settings row's ceiling — far more often than in between, and every screen
    /// would otherwise grow a parameter it does nothing with but forward.
    func provideWindowSize() -> some View {
        modifier(WindowSizeProvider())
    }
}

private struct WindowSizeProvider: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            // `onGeometryChange` rather than wrapping the app in a `GeometryReader`: the reader
            // would become the root view's layout and flatten every screen against the top-leading
            // corner. This observes the size the root was already given, and changes nothing.
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .environment(
                \.loopkyWidthClass,
                LoopkyWidthClass.of(width: width, sizeClass: horizontalSizeClass)
            )
    }
}
