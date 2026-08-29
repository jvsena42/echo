import SwiftUI

/// Native-first Loopky button styles.
///
/// Per CLAUDE.md "Native-first UI", we extend SwiftUI's `ButtonStyle` protocol — the
/// sanctioned customisation point — and apply Loopky brand tokens to it, rather than
/// rebuilding tap chrome from `Capsule` + `.onTapGesture` at every call site. These mirror
/// the Android `LoopkyPrimaryButton`/`LoopkySecondaryButton` components so both platforms share
/// one definition of the brand buttons.

/// Filled accent pill — primary call to action.
struct LoopkyFilledButtonStyle: ButtonStyle {
    var fill: Color = LoopkyColor.accentPrimary
    var foreground: Color = .white
    var fontSize: CGFloat = 17
    var verticalPadding: CGFloat = 18

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundColor(foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, verticalPadding)
            .background(Capsule().fill(fill))
            // A ButtonStyle does not dim on its own: without this every disabled primary in the
            // app — Send code, Restore account, Create file — renders identically to a live one
            // and invites the tap it will then swallow.
            .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Soft accent pill — secondary action.
struct LoopkySoftButtonStyle: ButtonStyle {
    var fill: Color = LoopkyColor.accentPrimarySoft
    var foreground: Color = LoopkyColor.accentPrimary
    var fontSize: CGFloat = 16
    var verticalPadding: CGFloat = 18

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundColor(foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, verticalPadding)
            .background(Capsule().fill(fill))
            // A ButtonStyle does not dim on its own: without this every disabled primary in the
            // app — Send code, Restore account, Create file — renders identically to a live one
            // and invites the tap it will then swallow.
            .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Compact filled pill — small inline actions (e.g. a "Save" in a header).
struct LoopkyCompactFilledButtonStyle: ButtonStyle {
    var fill: Color = LoopkyColor.accentPrimary
    var foreground: Color = .white

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(foreground)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Capsule().fill(fill))
            // A ButtonStyle does not dim on its own: without this every disabled primary in the
            // app — Send code, Restore account, Create file — renders identically to a live one
            // and invites the tap it will then swallow.
            .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
    }
}

/// Outlined rounded button — tertiary actions (add card, media pickers, "+ Add" tag).
struct LoopkyOutlineButtonStyle: ButtonStyle {
    var stroke: Color = LoopkyColor.accentPrimary
    var foreground: Color = LoopkyColor.accentPrimary
    var cornerRadius: CGFloat = 14
    var lineWidth: CGFloat = 1.5
    var fontSize: CGFloat = 15
    var fillWidth: Bool = true

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundColor(foreground)
            .frame(maxWidth: fillWidth ? .infinity : nil)
            .padding(.vertical, 14)
            .padding(.horizontal, fillWidth ? 0 : 14)
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(stroke, lineWidth: lineWidth)
            )
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.4)
    }
}

extension ButtonStyle where Self == LoopkyFilledButtonStyle {
    static var loopkyFilled: LoopkyFilledButtonStyle { LoopkyFilledButtonStyle() }
}

extension ButtonStyle where Self == LoopkySoftButtonStyle {
    static var loopkySoft: LoopkySoftButtonStyle { LoopkySoftButtonStyle() }
}

extension ButtonStyle where Self == LoopkyCompactFilledButtonStyle {
    static var loopkyCompactFilled: LoopkyCompactFilledButtonStyle { LoopkyCompactFilledButtonStyle() }
}

extension ButtonStyle where Self == LoopkyOutlineButtonStyle {
    static var loopkyOutline: LoopkyOutlineButtonStyle { LoopkyOutlineButtonStyle() }
}
