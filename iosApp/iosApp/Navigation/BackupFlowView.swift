import SwiftUI

/// The backup flow, reached from Settings or the Profile nag once the account already exists.
///
/// Presented as a sheet rather than pushed. Android pushes it onto the same graph, but the
/// signed-in side here drives a single `navigationDestination(item:)`, and backup is four pushes
/// deep — a self-contained task the user finishes and dismisses is what a sheet is for on iOS.
struct BackupFlowView: View {
    var onClose: () -> Void

    @State private var path: [IdentityRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            BackupStartScreen(
                onBack: onClose,
                onDone: onClose,
                onPhrase: { path.append(.backupPhrase) },
                onFile: { path.append(.backupFile) },
                onRing: { path.append(.backupRing) }
            )
            .navigationDestination(for: IdentityRoute.self) { route in
                destination(route)
            }
        }
    }

    @ViewBuilder
    private func destination(_ route: IdentityRoute) -> some View {
        switch route {
        case .backupPhrase:
            BackupPhraseScreen(onBack: pop, onContinue: { path.append(.backupQuiz) })
        case .backupQuiz:
            // Back to the menu, not out: methods accumulate, and the menu now shows this one
            // ticked.
            BackupQuizScreen(onBack: pop, onDone: { path.removeAll() })
        case .backupFile:
            BackupFileScreen(onBack: pop, onDone: { path.removeAll() })
        case .backupRing:
            BackupRingScreen(onBack: pop, onDone: { path.removeAll() })
        default:
            EmptyView()
        }
    }

    private func pop() {
        if !path.isEmpty { path.removeLast() }
    }
}
