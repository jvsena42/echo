import SwiftUI

enum DeckRoute: Hashable, Identifiable {
    case detail(String)
    case editor(String)
    case editorNew
    case editCard(String, String)
    case importPaste
    case importPublish

    var id: String {
        switch self {
        case .detail(let id): return "detail-\(id)"
        case .editor(let id): return "editor-\(id)"
        case .editorNew: return "editor-new"
        case .editCard(let deckId, let cardId): return "edit-\(deckId)-\(cardId)"
        case .importPaste: return "import-paste"
        case .importPublish: return "import-publish"
        }
    }
}

/// Temporary root. Holds the navigation stack and owns the "am I signed in?" state.
///
/// Once the shared `OnboardingViewModel` is accessible through SKIE, this view should
/// observe `vm.state` and drive navigation from emitted effects rather than the local
/// `@State` used here.
struct RootView: View {
    @Environment(\.openURL) private var openURL
    @State private var isSignedIn: Bool = false
    @State private var pubky: String? = nil
    @State private var deckRoute: DeckRoute? = nil

    var body: some View {
        NavigationStack {
            if isSignedIn {
                MainView(
                    greetingName: pubky.map { "pk:\($0.prefix(6))" } ?? "there",
                    onDeckTap: { deckId in deckRoute = .detail(deckId) },
                    onImportTap: { deckRoute = .importPaste },
                    onCreateDeckTap: { deckRoute = .editorNew }
                )
                .navigationDestination(item: $deckRoute) { route in
                    switch route {
                    case .detail(let deckId):
                        DeckDetailView(deckId: deckId, onBack: { deckRoute = nil })
                    case .editor(let deckId):
                        DeckEditorView(deckId: deckId, onBack: { deckRoute = nil })
                    case .editorNew:
                        DeckEditorView(onBack: { deckRoute = nil })
                    case .editCard(let deckId, let cardId):
                        EditCardView(deckId: deckId, cardId: cardId, onBack: { deckRoute = nil })
                    case .importPaste:
                        PasteView(
                            onCancel: { deckRoute = nil },
                            onNext: { deckRoute = .importPublish }
                        )
                    case .importPublish:
                        PublishDeckView(
                            onBack: { deckRoute = .importPaste },
                            onPublished: { deckId in deckRoute = .detail(deckId) }
                        )
                    }
                }
            } else {
                OnboardingView(
                    onSignInTapped: handleSignIn,
                    onInstallTapped: handleInstall,
                )
            }
        }
        .onOpenURL { url in
            // TODO: parse echo://login-callback query params and forward to the shared VM
            // once wired. For now just log so the deeplink registration can be verified.
            print("[Echo] received deeplink: \(url.absoluteString)")
        }
    }

    private func handleSignIn() {
        // TODO: call shared `OnboardingViewModel.onSignInClick()` via SKIE bridge and open
        // the returned auth URL via `UIApplication.shared.open(_:)`.
    }

    private func handleInstall() {
        if let url = URL(string: "https://pubky.org/ring") {
            openURL(url)
        }
    }
}
