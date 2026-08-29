import SwiftUI
import Shared

enum DeckRoute: Hashable, Identifiable {
    case detail(String, String?)
    case editor(String)
    case editorNew
    case editCard(String, String)
    case importPaste
    case importBulk
    case importTriage
    case importPublish
    /// `nil` means everything due across the library, which is what Home asks for.
    case study(String?)
    case settings
    case friendProfile(String)
    case followList(String, FollowSource)

    var id: String {
        switch self {
        case .detail(let id, let author): return "detail-\(id)-\(author ?? "self")"
        case .editor(let id): return "editor-\(id)"
        case .editorNew: return "editor-new"
        case .editCard(let deckId, let cardId): return "edit-\(deckId)-\(cardId)"
        case .importPaste: return "import-paste"
        case .importBulk: return "import-bulk"
        case .importTriage: return "import-triage"
        case .importPublish: return "import-publish"
        case .study(let id): return "study-\(id ?? "all")"
        case .settings: return "settings"
        case .friendProfile(let pubky): return "friend-\(pubky)"
        case .followList(let pubky, let source): return "follows-\(pubky)-\(source.name)"
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
                    onImportFileTap: { deckRoute = .importBulk },
                    onCreateDeckTap: { deckRoute = .editorNew },
                    onSignedOut: { isSignedIn = false },
                    onStartStudy: { deckRoute = .study(nil) },
                    onOpenSettings: { deckRoute = .settings },
                    onOpenProfile: { deckRoute = .friendProfile($0) },
                    onOpenFollows: { pubky, source in deckRoute = .followList(pubky, source) }
                )
                .navigationDestination(item: $deckRoute) { route in
                    switch route {
                    case .detail(let deckId, let author):
                        DeckDetailScreen(
                            deckId: deckId,
                            authorPubky: author,
                            onBack: { deckRoute = nil },
                            onEditDeck: { id in deckRoute = .editor(id) },
                            onStudy: { deckRoute = .study(deckId) },
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
                            onNext: { deckRoute = .importTriage }
                        )
                    case .importBulk:
                        BulkImportScreen(
                            onCancel: { deckRoute = nil },
                            onContinue: { deckRoute = .importTriage }
                        )
                    case .importTriage:
                        TriageScreen(
                            onBack: { deckRoute = nil },
                            onPublish: { deckRoute = .importPublish }
                        )
                    case .friendProfile(let pubky):
                        FriendProfileScreen(
                            pubky: pubky,
                            onBack: { deckRoute = nil },
                            onOpenDeck: { deckId, author in deckRoute = .detail(deckId, author) },
                            onOpenAuthor: { deckRoute = .friendProfile($0) }
                        )
                    case .followList(let pubky, let source):
                        FollowListScreen(
                            pubky: pubky,
                            source: source,
                            onOpenProfile: { deckRoute = .friendProfile($0) }
                        )
                    case .settings:
                        SettingsScreen(onSignedOut: { deckRoute = nil; isSignedIn = false })
                    case .study(let deckId):
                        StudySessionScreen(
                            deckId: deckId,
                            onClose: {
                                // Back to the deck it came from, or to the tabs when studying
                                // everything due.
                                deckRoute = deckId.map { .detail($0, nil) }
                            }
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
