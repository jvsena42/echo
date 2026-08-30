import SwiftUI
import UniformTypeIdentifiers
import Shared

/// Export an Argon2id-encrypted `recovery.pkarr` through the system file picker.
struct BackupFileScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @State private var viewModel: BackupFileViewModel?
    @State private var uiState: BackupFileUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    /// Local while typing: routing every keystroke through Kotlin and back drops characters.
    @State private var passphrase = ""
    @State private var document: RecoveryFileDocument?
    @State private var exportName = RecoveryFile.defaultName
    @State private var isExporting = false

    var body: some View {
        SignupScaffold(
            title: "backup_file_title",
            subtitle: NSLocalizedString("backup_file_subtitle", comment: ""),
            errorTitle: uiState?.failed ?? false
                ? NSLocalizedString("backup_file_failed", comment: "")
                : nil,
            errorMessage: nil,
            onBack: leave
        ) {
            VStack(alignment: .leading, spacing: 0) {
                FieldLabel(text: "backup_file_passphrase")
                Spacer().frame(height: 8)
                PassphraseField(
                    text: $passphrase,
                    placeholder: nil,
                    isEnabled: !(uiState?.isCreating ?? false),
                    isError: uiState?.failed ?? false,
                    identifier: "backup_file_passphrase"
                )
                .onChange(of: passphrase) { _, value in
                    viewModel?.onPassphraseChange(value: value)
                }

                Spacer().frame(height: 8)
                // A nudge, not a gate: the repository accepts any non-empty passphrase, and a
                // short one the user actually remembers beats a strong one they do not.
                Text(strengthKey)
                    .font(.system(size: 12))
                    .foregroundStyle(strengthColor)

                Spacer().frame(height: 24)
                SignupPrimaryButton(
                    title: "backup_file_create",
                    isLoading: uiState?.isCreating ?? false,
                    isEnabled: uiState?.canCreate ?? false
                ) {
                    // Send the passphrase that is on screen before encrypting with it. The field
                    // owns its own @State and the ViewModel hears about edits only through
                    // .onChange, which has been seen to miss a value that arrives in one shot —
                    // and of everywhere that can happen, here is the worst: the file would be
                    // encrypted with something the user never typed, and nothing would say so
                    // until the day they needed it and it would not open.
                    viewModel?.onPassphraseChange(value: passphrase)
                    viewModel?.onCreateClick()
                }
                .accessibilityIdentifier("backup_file_create")
            }
        }
        .modifier(SecureScreenModifier())
        .fileExporter(
            isPresented: $isExporting,
            document: document,
            contentType: .pubkyRecovery,
            defaultFilename: exportName
        ) { result in
            // Only a confirmed write counts as a backup — a cancelled picker must not stop the
            // nag for a key nobody has a copy of.
            switch result {
            case .success: viewModel?.onFileSaved()
            case .failure: viewModel?.onFileSaveFailed()
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var strengthKey: LocalizedStringKey {
        switch uiState?.strength {
        case .weak: return "backup_strength_weak"
        case .fair: return "backup_strength_fair"
        case .strong: return "backup_strength_strong"
        default: return "backup_strength_too_short"
        }
    }

    private var strengthColor: Color {
        switch uiState?.strength {
        case .strong: return LoopkyColor.accentSecondary
        case .fair, .weak: return LoopkyColor.foregroundSecondary
        default: return LoopkyColor.foregroundMuted
        }
    }

    private func leave() {
        viewModel?.onLeave()
        onBack()
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupFileViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BackupFileUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let save as BackupEffectSaveFile:
                // The FFI hands back Base64; the file on disk is raw bytes. `RecoveryFile` owns
                // that conversion in both directions.
                guard let data = Data(base64Encoded: save.base64) else {
                    viewModel?.onFileSaveFailed()
                    return
                }
                document = RecoveryFileDocument(data: data)
                exportName = save.fileName
                isExporting = true
            case is BackupEffectDone:
                onDone()
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.onLeave()
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

extension UTType {
    /// Declared in Info.plist as an exported type. Falling back to `.data` would cost the
    /// extension on the saved file, not the save itself.
    static var pubkyRecovery: UTType {
        UTType("com.github.jvsena42.loopky.pkarr") ?? .data
    }
}

/// The exported blob, as raw bytes. Written by the picker, never by us — so "saved" means the
/// coordinator reported a write, not that we asked for one.
struct RecoveryFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.pubkyRecovery] }

    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
