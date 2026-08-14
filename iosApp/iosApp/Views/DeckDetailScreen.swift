import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DeckDetailView`.
struct DeckDetailScreen: View {
    let deckId: String
    var authorPubky: String?
    var onBack: () -> Void = {}
    var onEditDeck: (String) -> Void = { _ in }
    var onStudy: () -> Void = {}
    var onDeleted: () -> Void = {}

    @State private var viewModel: DeckDetailViewModel?
    @State private var uiState: DeckDetailUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var shareItem: ShareItem?

    var body: some View {
        DeckDetailView(
            state: viewState,
            onBack: { viewModel?.onBackClick() },
            onEdit: { viewModel?.onEditClick() },
            onDelete: { viewModel?.onDeleteDeck() },
            onShare: { viewModel?.onShareClick() },
            onStudy: { viewModel?.onStudyClick() }
        )
        .alert("deck_detail_delete_dialog_title", isPresented: deleteConfirmBinding) {
            Button("deck_detail_delete_cancel", role: .cancel) { viewModel?.onDismissDelete() }
            Button("deck_detail_delete_confirm", role: .destructive) { viewModel?.onConfirmDelete() }
        } message: {
            Text("deck_detail_delete_dialog_message")
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.text])
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: DeckDetailViewState {
        switch uiState {
        case let content as DeckDetailUiStateContent:
            return .content(DeckDetailContent(
                title: content.title,
                description: content.description_,
                coverEmoji: content.coverEmoji,
                coverImageUrl: content.coverImageUrl,
                coverImageBase64: content.coverImageBase64,
                authorName: content.authorName,
                authorPubky: content.authorPubky,
                authorInitial: KotlinInterop.charToString(content.authorInitial),
                isOwned: content.isOwned,
                tags: content.tags,
                totalCards: Int(content.totalCards),
                dueCards: Int(content.dueCards),
                masteredPercent: content.masteredPercent,
                cards: content.cardPreviews.map {
                    CardPreviewData(id: $0.id, front: $0.frontText, back: $0.backText)
                }
            ))
        case let error as DeckDetailUiStateError:
            return .error(ErrorCopy.message(for: error.reason))
        default:
            return .loading
        }
    }

    private var deleteConfirmBinding: Binding<Bool> {
        Binding(
            get: { (uiState as? DeckDetailUiStateContent)?.showDeleteConfirm == true },
            set: { if !$0 { viewModel?.onDismissDelete() } }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.deckDetailViewModel(deckId: deckId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DeckDetailUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is DeckDetailEffectNavigateBack:
                onBack()
            case let edit as DeckDetailEffectNavigateEditDeck:
                onEditDeck(edit.deckId)
            case is DeckDetailEffectNavigateStudy:
                onStudy()
            case let share as DeckDetailEffectShare:
                // Matches Android: "<title> on Echo" beats a bare pubky:// manifest URL.
                shareItem = ShareItem(text: "\(share.title) on Echo\n\(share.uri)")
            case is DeckDetailEffectDeleted:
                onDeleted()
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
