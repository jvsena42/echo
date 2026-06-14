import SwiftUI

/// Native-first Echo button styles.
///
/// Per CLAUDE.md "Native-first UI", we extend SwiftUI's `ButtonStyle` protocol — the
/// sanctioned customisation point — and apply Echo brand tokens to it, rather than
/// rebuilding tap chrome from `Capsule` + `.onTapGesture` at every call site. These mirror
/// the Android `EchoPrimaryButton`/`EchoSecondaryButton` components so both platforms share
/// one definition of the brand buttons.

/// Filled accent pill — primary call to action.
struct EchoFilledButtonStyle: ButtonStyle {
    var fill: Color = EchoColor.accentPrimary
    var foreground: Color = .white
    var fontSize: CGFloat = 17
    var verticalPadding: CGFloat = 18

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundColor(foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, verticalPadding)
            .background(Capsule().fill(fill))
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Soft accent pill — secondary action.
struct EchoSoftButtonStyle: ButtonStyle {
    var fill: Color = EchoColor.accentPrimarySoft
    var foreground: Color = EchoColor.accentPrimary
    var fontSize: CGFloat = 16
    var verticalPadding: CGFloat = 18

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundColor(foreground)
            .frame(maxWidth: .infinity)
            .padding(.vertical, verticalPadding)
            .background(Capsule().fill(fill))
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Compact filled pill — small inline actions (e.g. a "Save" in a header).
struct EchoCompactFilledButtonStyle: ButtonStyle {
    var fill: Color = EchoColor.accentPrimary
    var foreground: Color = .white

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(foreground)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Capsule().fill(fill))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// Outlined rounded button — tertiary actions (add card, media pickers, "+ Add" tag).
struct EchoOutlineButtonStyle: ButtonStyle {
    var stroke: Color = EchoColor.accentPrimary
    var foreground: Color = EchoColor.accentPrimary
    var cornerRadius: CGFloat = 14
    var lineWidth: CGFloat = 1.5
    var fontSize: CGFloat = 15
    var fillWidth: Bool = true

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
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

extension ButtonStyle where Self == EchoFilledButtonStyle {
    static var echoFilled: EchoFilledButtonStyle { EchoFilledButtonStyle() }
}

extension ButtonStyle where Self == EchoSoftButtonStyle {
    static var echoSoft: EchoSoftButtonStyle { EchoSoftButtonStyle() }
}

extension ButtonStyle where Self == EchoCompactFilledButtonStyle {
    static var echoCompactFilled: EchoCompactFilledButtonStyle { EchoCompactFilledButtonStyle() }
}

extension ButtonStyle where Self == EchoOutlineButtonStyle {
    static var echoOutline: EchoOutlineButtonStyle { EchoOutlineButtonStyle() }
}
