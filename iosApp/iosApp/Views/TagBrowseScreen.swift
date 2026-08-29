import SwiftUI
import Shared

/// Every deck on Loopky carrying one tag — where a tag chip leads.
///
/// The chips have rendered since the first deck-detail screen and did nothing: `tagBrowseViewModel`
/// was bound and never called, so a tag was decoration.
struct TagBrowseScreen: View {
    let tag: String
    var onBack: () -> Void = {}
    /// `(deckId, authorPubky)` — the order every other screen uses.
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onOpenProfile: (String) -> Void = { _ in }

    @State private var viewModel: TagBrowseViewModel?
    @State private var uiState: TagBrowseUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            content
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
            }
            .accessibilityLabel(Text("tag_browse_back"))
            .accessibilityIdentifier("tag_browse_back")

            Text(verbatim: String(
                format: NSLocalizedString("tag_browse_title", comment: ""), tag
            ))
            .font(.system(size: 22, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .lineLimit(1)

            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private var content: some View {
        switch uiState {
        case is TagBrowseUiStateLoading:
            centred { ProgressView().tint(LoopkyColor.accentPrimary) }
        case is TagBrowseUiStateEmpty:
            centred { empty }
        case let loaded as TagBrowseUiStateContent:
            grid(decks: loaded.decks.compactMap { $0 as? DiscoverDeck })
        default:
            centred { ProgressView().tint(LoopkyColor.accentPrimary) }
        }
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Text(verbatim: String(
                format: NSLocalizedString("tag_browse_empty_title", comment: ""), tag
            ))
            .font(.system(size: 18, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .multilineTextAlignment(.center)

            Text("tag_browse_empty_subtitle")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 32)
    }

    private func grid(decks: [DiscoverDeck]) -> some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                ForEach(decks, id: \.id) { deck in
                    DeckTileView(
                        title: deck.title,
                        cardCount: Int(deck.cardCount),
                        coverEmoji: deck.coverEmoji,
                        authorLabel: IdentityData(deck.author).label,
                        coverImage: deck.coverImage,
                        authorPubky: deck.authorPubky,
                        deckId: deck.id,
                        onTap: {
                            viewModel?.onOpenDeck(authorPubky: deck.authorPubky, deckId: deck.id)
                        }
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
    }

    private func centred<Body: View>(@ViewBuilder _ body: () -> Body) -> some View {
        VStack {
            Spacer()
            body()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.tagBrowseViewModel(tag: tag)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? TagBrowseUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as TagBrowseEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case let open as TagBrowseEffectOpenProfile:
                onOpenProfile(open.pubky)
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
