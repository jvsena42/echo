import SwiftUI
import Shared

/// The twelve words, blurred until asked for.
///
/// Seeing them is not what marks the key backed up — the quiz behind Continue is. Someone who
/// tapped past a screen of words has not written them down.
struct BackupPhraseScreen: View {
    var onBack: () -> Void
    var onContinue: () -> Void

    @State private var viewModel: BackupPhraseViewModel?
    @State private var uiState: BackupPhraseUiState?
    @State private var stateSink: FlowEffectSink?

    private var words: [String] { (uiState?.words as? [String]) ?? [] }
    private var isRevealed: Bool { uiState?.revealed ?? false }

    var body: some View {
        SignupScaffold(
            title: "backup_phrase_title",
            subtitle: NSLocalizedString("backup_phrase_subtitle", comment: ""),
            errorTitle: uiState?.failed ?? false
                ? NSLocalizedString("restore_error_unreadable_title", comment: "")
                : nil,
            errorMessage: nil,
            onBack: leave
        ) {
            VStack(alignment: .leading, spacing: 20) {
                SeedPhraseWarning(text: "backup_phrase_warning")

                if uiState?.isLoading ?? true {
                    ProgressView().controlSize(.regular).tint(LoopkyColor.accentPrimary)
                } else {
                    // Reveal is on the words themselves, as on Android — a second full-width
                    // primary beside Continue gives two equally loud buttons and no hierarchy.
                    wordGrid
                    // Continue only once the words have actually been shown: the quiz behind it
                    // asks for words nobody has seen otherwise.
                    SignupPrimaryButton(
                        title: "backup_phrase_continue",
                        isEnabled: isRevealed,
                        action: onContinue
                    )
                    .accessibilityIdentifier("backup_phrase_continue")
                }
            }
        }
        .modifier(SecureScreenModifier())
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    /// The words, blurred until asked for, with the reveal as its own control on top.
    ///
    /// The grid is `.accessibilityHidden` while blurred — otherwise the guard is visual only and
    /// VoiceOver reads the phrase out to whoever is listening — so the reveal has to be a separate
    /// element, or it would be unreachable to exactly the users the blur hides the words from.
    private var wordGrid: some View {
        ZStack {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                    HStack(spacing: 8) {
                        Text(verbatim: "\(index + 1)")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                            .frame(width: 18, alignment: .trailing)
                        Text(verbatim: word)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(LoopkyColor.foregroundPrimary)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(LoopkyColor.surfaceCard))
                }
            }
            .blur(radius: isRevealed ? 0 : 8)
            .accessibilityHidden(!isRevealed)
            .accessibilityIdentifier("backup_phrase_words")

            // On the words themselves, as on Android — a second full-width primary beside Continue
            // gives two equally loud buttons and no hierarchy.
            if !isRevealed {
                Button("backup_phrase_reveal") { viewModel?.onRevealClick() }
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(LoopkyColor.surfaceCard))
                    .shadow(color: LoopkyColor.shadowElevationMedium, radius: 8, y: 2)
                    .accessibilityIdentifier("backup_phrase_reveal")
            }
        }
    }

    private func leave() {
        viewModel?.onLeave()
        onBack()
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupPhraseViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BackupPhraseUiState }
    }

    private func detach() {
        // The words are dropped as the screen goes away; they live no longer than it does.
        viewModel?.onLeave()
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}
