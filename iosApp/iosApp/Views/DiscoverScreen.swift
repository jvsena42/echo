import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DiscoverView`.
///
/// Note the teardown call: androidx's own `ViewModel.clear()` is internal and is not exported to
/// Objective-C, so releasing a VM goes through `IosDependencies.clear(viewModel:)`.
struct DiscoverScreen: View {
    var onOpenProfile: (String) -> Void = { _ in }
    /// `(deckId, authorPubky)` — in that order. The two screens used to disagree.
    var onOpenDeck: (_ deckId: String, _ authorPubky: String) -> Void = { _, _ in }
    var onSearch: () -> Void = {}

    @State private var viewModel: DiscoverViewModel?
    @State private var uiState: DiscoverUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        DiscoverView(
            state: viewState,
            onTagTap: { selectTag(labelled: $0) },
            onSearchTap: { viewModel?.onSearch() },
            onPersonTap: { viewModel?.onOpenAuthor(pubky: $0) },
            onFollowTap: { viewModel?.onFollowToggle(pubky: $0) },
            onDeckTap: { author, deckId in
                viewModel?.onOpenDeck(authorPubky: author, deckId: deckId)
            },
            onRetryFollowing: { viewModel?.onRetryFollowing() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: DiscoverViewState {
        guard let state = uiState else { return DiscoverViewState() }
        return DiscoverViewState(
            topics: state.topics.items.map { KotlinInterop.tagLabel($0) },
            people: section(state.people) { person in
                let identity = IdentityData(person.identity)
                return DiscoverPersonData(
                    id: person.identity.pubky,
                    label: identity.label,
                    shortPubky: identity.shortPubky,
                    initial: identity.initial,
                    avatarUrl: identity.avatarUrl,
                    isFollowing: person.isFollowing,
                    isFollowPending: person.isFollowPending
                )
            },
            browse: section(state.browse, transform: deckData),
            following: section(state.following, transform: deckData),
            selectedTag: state.selectedTag.map { KotlinInterop.tagLabel($0) }
        )
    }

    private func deckData(_ deck: DiscoverDeck) -> DiscoverDeckData {
        DiscoverDeckData(
            deckId: deck.id,
            authorPubky: deck.authorPubky,
            title: deck.title,
            cardCount: Int(deck.cardCount),
            coverEmoji: deck.coverEmoji,
            authorLabel: IdentityData(deck.author).label,
            coverImage: deck.coverImage
        )
    }

    /// `Tag` is a Kotlin value class, so it crosses the bridge as an opaque `id` and cannot be
    /// rebuilt in Swift — `Tag(value:)` does not exist. The tag the user tapped is therefore
    /// looked up among the ones the state already carries and handed back unchanged.
    private func selectTag(labelled label: String?) {
        guard let label else {
            viewModel?.onTagSelected(tag: nil)
            return
        }
        let original = uiState?.topics.items.first { KotlinInterop.tagLabel($0) == label }
        viewModel?.onTagSelected(tag: original)
    }

    /// Item types erase to `Any` across the bridge, so each strip is mapped from its erased list.
    private func section<Wire: AnyObject, Item>(
        _ state: SectionState<Wire>,
        transform: (Wire) -> Item
    ) -> DiscoverSection<Item> {
        DiscoverSection(
            items: state.items.compactMap { ($0 as? Wire).map(transform) },
            isLoading: state.isLoading,
            errorMessage: state.error.map { ErrorCopy.message(for: $0) }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.discoverViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DiscoverUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as DiscoverEffectOpenProfile:
                onOpenProfile(open.pubky)
            case let open as DiscoverEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case is DiscoverEffectOpenSearch:
                onSearch()
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
