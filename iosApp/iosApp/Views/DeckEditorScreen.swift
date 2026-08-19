import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DeckEditorView`.
/// `deckId == nil` creates a new deck.
struct DeckEditorScreen: View {
    var deckId: String?
    var onBack: () -> Void = {}
    var onEditCard: (String, String) -> Void = { _, _ in }
    /// A card that does not exist yet — the editor mints its id and appends it on save.
    var onNewCard: (String) -> Void = { _ in }
    var onSaved: (String) -> Void = { _ in }

    @State private var viewModel: DeckEditorViewModel?
    @State private var uiState: DeckEditorUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        DeckEditorView(
            isNew: uiState?.isNew ?? (deckId == nil),
            coverEmoji: uiState?.coverEmoji ?? "",
            title: uiState?.title ?? "",
            description: uiState?.description_ ?? "",
            tags: uiState?.tags ?? [],
            cards: (uiState?.cards ?? []).map {
                EditorCardData(
                    id: $0.id,
                    front: $0.frontText,
                    back: $0.backText,
                    hasImage: $0.hasImage,
                    hasAudio: $0.hasAudio
                )
            },
            totalCards: Int(uiState?.totalCards ?? 0),
            isLoadingCards: uiState?.isLoadingCards ?? false,
            hasMoreCards: uiState?.hasMoreCards ?? false,
            isSaving: uiState?.isSaving ?? false,
            titleError: uiState?.titleError,
            descriptionError: uiState?.descriptionError,
            error: uiState?.error,
            onTitleChanged: { viewModel?.onTitleChanged(text: $0) },
            onDescriptionChanged: { viewModel?.onDescriptionChanged(text: $0) },
            onAddTag: { viewModel?.onAddTag(tag: $0) },
            onRemoveTag: { viewModel?.onRemoveTag(tag: $0) },
            onAddCard: { viewModel?.onAddCard() },
            onCardTap: { viewModel?.onCardClick(cardId: $0) },
            onLoadMoreCards: { viewModel?.onLoadMoreCards() },
            onClose: { viewModel?.onCloseClick() },
            onSave: { viewModel?.onSaveClick() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.deckEditorViewModel(deckId: deckId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DeckEditorUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is DeckEditorEffectNavigateBack:
                onBack()
            case let editCard as DeckEditorEffectNavigateEditCard:
                onEditCard(editCard.deckId, editCard.cardId)
            case let newCard as DeckEditorEffectNavigateNewCard:
                onNewCard(newCard.deckId)
            case let saved as DeckEditorEffectSaveSuccess:
                onSaved(saved.deckId)
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
