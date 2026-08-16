import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DiscoverView`.
///
/// Note the teardown call: the shared ViewModels extend the multiplatform
/// `androidx.lifecycle.ViewModel`, which exposes `clear()` — there is no `onDispose()`. The older
/// iOS screens still call `onDispose()` and have not been rebuilt against the framework since the
/// androidx migration; they will need the same correction.
struct DiscoverScreen: View {
    var onOpenProfile: (String) -> Void = { _ in }
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onAddFriend: () -> Void = {}

    @State private var viewModel: DiscoverViewModel?
    @State private var uiState: DiscoverUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        DiscoverView(
            state: viewState,
            onTagTap: { viewModel?.onTagSelected(tag: $0.map { Tag(value: $0) }) },
            onAddFriendTap: { viewModel?.onAddFriend() },
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
            authorLabel: IdentityData(deck.author).label
        )
    }

    /// Generics erase across the bridge, so each strip is mapped from its erased item list.
    private func section<Wire, Item>(
        _ state: SectionState<AnyObject>,
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
            case is DiscoverEffectOpenAddFriend:
                onAddFriend()
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.clear()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
