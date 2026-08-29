import SwiftUI

enum DeckRoute: Hashable, Identifiable {
    case detail(String, String?)
    case editor(String)
    case editorNew
    case editCard(String, String)
    case importPaste
    case importPublish

    var id: String {
        switch self {
        case .detail(let id, let author): return "detail-\(id)-\(author ?? "self")"
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
    @State private var pubky: String?
    @State private var deckRoute: DeckRoute?

    var body: some View {
        NavigationStack {
            if isSignedIn {
                MainView(
                    onDeckTap: { deckId, author in deckRoute = .detail(deckId, author) },
                    onImportTap: { deckRoute = .importPaste },
                    onCreateDeckTap: { deckRoute = .editorNew },
                    onSignedOut: { isSignedIn = false }
                )
                .navigationDestination(item: $deckRoute) { route in
                    switch route {
                    case .detail(let deckId, let author):
                        DeckDetailScreen(
                            deckId: deckId,
                            authorPubky: author,
                            onBack: { deckRoute = nil },
                            onEditDeck: { id in deckRoute = .editor(id) },
                            onStudy: {},
                            onDeleted: { deckRoute = nil }
                        )
                    case .editor(let deckId):
                        DeckEditorScreen(
                            deckId: deckId,
                            onBack: { deckRoute = nil },
                            onEditCard: { d, c in deckRoute = .editCard(d, c) },
                            // Blank card id: the card editor mints one and appends on save.
                            onNewCard: { d in deckRoute = .editCard(d, "") },
                            onSaved: { id in deckRoute = .detail(id, nil) }
                        )
                    case .editorNew:
                        DeckEditorScreen(
                            deckId: nil,
                            onBack: { deckRoute = nil },
                            onEditCard: { d, c in deckRoute = .editCard(d, c) },
                            onNewCard: { d in deckRoute = .editCard(d, "") },
                            onSaved: { id in deckRoute = .detail(id, nil) }
                        )
                    case .editCard(let deckId, let cardId):
                        EditCardScreen(deckId: deckId, cardId: cardId, onBack: { deckRoute = nil })
                    case .importPaste:
                        PasteScreen(
                            onCancel: { deckRoute = nil },
                            onNext: { deckRoute = .importPublish }
                        )
                    case .importPublish:
                        PublishDeckScreen(
                            onBack: { deckRoute = .importPaste },
                            onPublished: { deckId in deckRoute = .detail(deckId, nil) }
                        )
                    }
                }
            } else {
                OnboardingScreen(onSignedIn: { isSignedIn = true })
            }
        }
        .onOpenURL { url in
            // The auth flow completes via the FFI's awaitAuthApproval (relay polling), so the
            // callback deeplink only needs to bring Loopky back to the foreground.
            print("[Loopky] received deeplink: \(url.absoluteString)")
        }
    }
}
