import SwiftUI
import Shared
import UniformTypeIdentifiers

/// Sign in from an encrypted recovery file.
struct RestoreFileScreen: View {
    var onBack: () -> Void
    var onRestored: () -> Void
    var onUnregistered: (String) -> Void

    @State private var viewModel: RestoreFileViewModel?
    @State private var uiState: RestoreFileUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var passphrase = ""
    @State private var isPicking = false

    var body: some View {
        SignupScaffold(
            title: "restore_file_title",
            subtitle: NSLocalizedString("restore_file_subtitle", comment: ""),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 0) {
                Button { isPicking = true } label: {
                    Text(verbatim: uiState?.fileName
                        ?? NSLocalizedString("restore_file_choose", comment: ""))
                }
                .buttonStyle(.loopkyOutline)
                .accessibilityIdentifier("restore_file_choose")

                if uiState?.fileName != nil {
                    Spacer().frame(height: 20)
                    FieldLabel(text: "restore_file_passphrase_label")
                    Spacer().frame(height: 8)
                    PassphraseField(
                        text: $passphrase,
                        placeholder: "restore_file_passphrase_placeholder",
                        isEnabled: !isChecking,
                        isError: uiState?.outcome is RestoreOutcomeWrongPassphrase,
                        identifier: "restore_file_passphrase"
                    )
                    .onChange(of: passphrase) { _, value in
                        viewModel?.onPassphraseChange(passphrase: value)
                    }

                    Spacer().frame(height: 24)
                    SignupPrimaryButton(
                        title: isChecking ? "restore_phrase_checking" : "restore_file_submit",
                        isLoading: isChecking,
                        isEnabled: uiState?.canSubmit ?? false,
                        action: {
                            // The passphrase on screen is the one to try — see BackupFileScreen.
                            viewModel?.onPassphraseChange(passphrase: passphrase)
                            viewModel?.onSubmit()
                        }
                    )
                    .accessibilityIdentifier("restore_file_submit")
                }

                if let outcome = uiState?.outcome {
                    Spacer().frame(height: 20)
                    RestoreOutcomeBlock(outcome: outcome)
                }
            }
        }
        // A passphrase is on screen.
        .modifier(SecureScreenModifier())
        .fileImporter(
            isPresented: $isPicking,
            // Any type, on purpose: a recovery file has no registered UTI, and providers report it
            // as data, as text, or as nothing at all. Narrowing this hides the very file wanted.
            allowedContentTypes: [.data, .item],
            allowsMultipleSelection: false
        ) { result in
            handlePick(result)
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var isChecking: Bool { uiState?.isChecking ?? false }

    /// Reads the file as **Base64**, because that is what the FFI's decrypt takes — while the file
    /// on disk is raw bytes. `RecoveryFile` owns that conversion; see the note there.
    private func handlePick(_ result: Result<[URL], Error>) {
        guard let url = try? result.get().first else {
            viewModel?.onFileUnreadable()
            return
        }
        switch RecoveryFile.read(url) {
        case .success(let picked):
            viewModel?.onFilePicked(fileName: picked.name, base64: picked.base64)
        case .failure:
            viewModel?.onFileUnreadable()
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.restoreFileViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? RestoreFileUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is RestoreEffectNavigateHome:
                onRestored()
            case let unregistered as RestoreEffectNavigateUnregistered:
                onUnregistered(unregistered.pubky)
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.onLeaveUnlessCorrecting()
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
