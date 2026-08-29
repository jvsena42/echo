import SwiftUI
import Shared

/// Pronunciation practice, as a sheet over the card.
///
/// Three phases, one sheet: listening, right, wrong. `Idle` presents nothing — the shared
/// ViewModel's phase *is* the presentation state, so there is no second copy of it here.
struct SpeakSheet: View {
    /// Held erased and matched against the concrete classes: `SpeakPhase` is a sealed interface,
    /// so it crosses as an Objective-C protocol and a generic cast to it silently yields nil.
    let phase: Any?
    var onRetry: () -> Void = {}
    var onContinue: () -> Void = {}
    var onDismiss: () -> Void = {}

    var body: some View {
        VStack(spacing: 16) {
            switch phase {
            case let listening as SpeakPhaseListening:
                listeningBody(listening.expected)
            case let correct as SpeakPhaseCorrect:
                correctBody(correct.heard)
            case let wrong as SpeakPhaseWrong:
                wrongBody(heard: wrong.heard, expected: wrong.expected)
            default:
                EmptyView()
            }
        }
        .padding(24)
        .contentPane(PaneWidth.focused)
        .background(LoopkyColor.surfacePrimary)
        .presentationDetents([.height(340)])
        .presentationDragIndicator(.visible)
    }

    private func listeningBody(_ target: String) -> some View {
        VStack(spacing: 14) {
            Text("speak_listening_prompt")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            // What is shown is the card as written, asides included: the note is the context worth
            // seeing, even though the matcher drops it before comparing.
            Text(verbatim: target)
                .font(.system(size: 26, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Image(systemName: "waveform")
                .font(.system(size: 40))
                .foregroundStyle(LoopkyColor.accentPrimary)
                .symbolEffect(.variableColor.iterative, options: .repeating)
                .padding(.vertical, 8)
            Button("speak_listening_hint", action: onDismiss)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .accessibilityIdentifier("speak_cancel")
        }
    }

    private func correctBody(_ heard: String) -> some View {
        VStack(spacing: 12) {
            Text("🎉").font(.system(size: 44))
            Text("speak_correct_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("speak_you_said_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.6)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text(verbatim: heard)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button("speak_continue", action: onContinue)
                .buttonStyle(.loopkyFilled)
                .accessibilityIdentifier("speak_continue")
        }
    }

    /// A miss says what was heard *and* what was wanted, and offers another go.
    ///
    /// It grades nothing: the four SRS buttons stay exactly as available as they were, because a
    /// pronunciation attempt is practice, not a verdict on whether the card was known.
    private func wrongBody(heard: String, expected: String) -> some View {
        VStack(spacing: 10) {
            Text("speak_wrong_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            VStack(spacing: 2) {
                Text("speak_you_said")
                    .font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(verbatim: heard)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.srsHard)
            }
            VStack(spacing: 2) {
                Text("speak_correct_label")
                    .font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(verbatim: expected)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.srsGood)
            }
            Button("speak_try_again", action: onRetry)
                .buttonStyle(.loopkyFilled)
                .padding(.top, 4)
                .accessibilityIdentifier("speak_retry")
        }
    }
}
