import SwiftUI
import Shared

/// Export the key into Pubky Ring.
///
/// **Loopky keeps its copy.** Ring imports the key; it does not take custody of it, and a phrase
/// already written down stays valid.
struct BackupRingScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    @State private var viewModel: BackupRingViewModel?
    @State private var uiState: BackupRingUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    /// Only offered once the export link has actually been opened — the confirm is the user's
    /// word about what happened on the other side, and there is nothing to confirm before that.
    @State private var didExport = false

    var body: some View {
        SignupScaffold(
            title: "backup_ring_title",
            subtitle: NSLocalizedString("backup_ring_subtitle", comment: ""),
            errorTitle: uiState?.failed ?? false
                ? NSLocalizedString("restore_error_unreadable_title", comment: "")
                : nil,
            errorMessage: nil,
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 12) {
                if uiState?.ringInstalled ?? false {
                    SignupPrimaryButton(title: "backup_ring_open") {
                        viewModel?.onExportClick()
                    }
                    .accessibilityIdentifier("backup_ring_open")

                    // A separate tap, deliberately: we cannot see whether Ring accepted the key,
                    // so marking it backed up on the open would record a backup that may not
                    // exist.
                    if didExport {
                        SignupPrimaryButton(title: "backup_ring_confirm") {
                            viewModel?.onExportConfirmed()
                        }
                        .accessibilityIdentifier("backup_ring_confirm")
                    }
                } else {
                    Text("backup_ring_missing")
                        .font(.system(size: 14))
                        .foregroundStyle(LoopkyColor.foregroundSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    SignupPrimaryButton(title: "backup_ring_install") {
                        viewModel?.onInstallRingClick()
                    }
                    .accessibilityIdentifier("backup_ring_install")
                }
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
        .onChange(of: scenePhase) { _, phase in
            // Re-probe on return from the App Store, so installing Ring does not leave the screen
            // insisting it is missing.
            if phase == .active { viewModel?.onScreenResumed() }
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.backupRingViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BackupRingUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let deeplink as BackupEffectOpenDeeplink:
                // Straight out to the OS. **Never logged and never staged in a clipboard** — this
                // URL carries the recovery phrase.
                // Confirm is offered only if the link actually opened; a Ring that is not there
                // has exported nothing to confirm.
                if open(deeplink.url) {
                    didExport = true
                } else {
                    viewModel?.onRingUnavailable()
                }
            case let install as BackupEffectOpenInstallPage:
                _ = open(install.url)
            case is BackupEffectDone:
                onDone()
            default:
                break
            }
        }
    }

    @discardableResult
    private func open(_ raw: String) -> Bool {
        guard let url = URL(string: raw), UIApplication.shared.canOpenURL(url) else { return false }
        openURL(url)
        return true
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
