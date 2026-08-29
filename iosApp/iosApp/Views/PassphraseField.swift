import SwiftUI

/// A masked passphrase field with a reveal toggle.
///
/// Mirrors Android's `PassphraseField`, including two decisions that are load-bearing:
///
/// **The reveal state is `@State`, and resets when the app is backgrounded.** Restoring a
/// *visible* passphrase onto a screen someone is returning to is the one moment nobody chose to
/// expose it — Android uses `remember`, not `rememberSaveable`, for exactly this.
///
/// **`textContentType` is deliberately unset.** This is the iOS half of #148: on Android,
/// `KeyboardType.Password` was added to stop the IME learning the passphrase and turned out to be
/// the strongest possible hint to an autofill service that the field is worth *saving*. Setting
/// `.password` here is the same mistake — it is what raises "Save this password to iCloud
/// Keychain?". Autocorrect and capitalisation are off instead, which is what actually keeps the
/// text out of the keyboard's dictionary.
struct PassphraseField: View {
    @Binding var text: String
    var placeholder: LocalizedStringKey?
    var isEnabled: Bool = true
    var isError: Bool = false
    /// Goes on the field itself, not the row around it: an identifier on the container is not a
    /// text target, so automation finds it and types into nothing.
    var identifier: String?

    @State private var isRevealed = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if isRevealed {
                    TextField(placeholder ?? "", text: $text)
                } else {
                    SecureField(placeholder ?? "", text: $text)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 15))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .disabled(!isEnabled)
            .accessibilityIdentifier(identifier ?? "")

            Button {
                isRevealed.toggle()
            } label: {
                Image(systemName: isRevealed ? "eye.slash" : "eye")
                    .font(.system(size: 15))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .buttonStyle(.plain)
            .disabled(!isEnabled)
            .accessibilityLabel(Text(isRevealed ? "passphrase_hide" : "passphrase_reveal"))
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(isError ? LoopkyColor.danger : LoopkyColor.borderSubtle, lineWidth: 1)
        )
        .onChange(of: scenePhase) { _, phase in
            // Re-mask on the way out, so returning to the screen never shows it already revealed.
            if phase != .active { isRevealed = false }
        }
    }
}

/// A single-line text field styled like the signup screens' inputs.
struct SignupTextField: View {
    @Binding var text: String
    var placeholder: LocalizedStringKey?
    var isEnabled: Bool = true
    var isError: Bool = false
    var keyboard: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .never

    var body: some View {
        TextField(placeholder ?? "", text: $text)
            .font(.system(size: 15))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .keyboardType(keyboard)
            .textInputAutocapitalization(capitalization)
            .autocorrectionDisabled()
            .disabled(!isEnabled)
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isError ? LoopkyColor.danger : LoopkyColor.borderSubtle, lineWidth: 1)
            )
    }
}

/// A small uppercase field label, as every identity screen uses above its input.
struct FieldLabel: View {
    var text: LocalizedStringKey

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .bold))
            .kerning(0.6)
            .foregroundStyle(LoopkyColor.foregroundMuted)
    }
}
