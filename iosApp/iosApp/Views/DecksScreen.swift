import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DecksView`.
struct DecksScreen: View {
    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}

    @State private var viewModel: DecksLibraryViewModel?
    @State private var uiState: DecksLibraryUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        DecksView(
            state: viewState,
            onDeckTap: { viewModel?.onDeckClick(deckId: $0) },
            onImportTap: { viewModel?.onImportClick() },
            onCreateDeckTap: { viewModel?.onCreateDeckClick() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: DecksViewState {
        switch uiState {
        case is DecksLibraryUiStateEmpty:
            return .empty
        case let content as DecksLibraryUiStateContent:
            return .content(
                count: Int(content.deckCount),
                decks: content.decks.map { deck in
                    DeckTileData(
                        id: deck.id,
                        title: deck.title,
                        cardCount: Int(deck.cardCount),
                        coverEmoji: deck.coverEmoji,
                        authorLabel: deck.authorLabel
                    )
                }
            )
        case let error as DecksLibraryUiStateError:
            return .error(error.message)
        default:
            return .loading
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.decksLibraryViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DecksLibraryUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let detail as DecksLibraryEffectNavigateDeckDetail:
                onDeckTap(detail.deckId)
            case is DecksLibraryEffectNavigateImport:
                onImportTap()
            case is DecksLibraryEffectNavigateCreateDeck:
                onCreateDeckTap()
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.onDispose()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
