import SwiftUI

enum DecksViewState {
    case loading
    case empty
    case content(count: Int, decks: [DeckTileData])
    case error(String)
}

struct DeckTileData: Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
}

/// Pure layout — state comes from the shared `DecksLibraryViewModel` via `DecksScreen`.
struct DecksView: View {
    var state: DecksViewState = .loading
    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}

    private let columns = [
        GridItem(.flexible(), spacing: 14),
        GridItem(.flexible(), spacing: 14),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Text("decks_title")
                        .font(.system(size: 28, weight: .heavy))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Spacer()
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 20))
                        .foregroundColor(EchoColor.foregroundPrimary)
                }

                // Paste CTA
                Button(action: onImportTap) {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(Color.white.opacity(0.2))
                                .frame(width: 44, height: 44)
                            Image(systemName: "doc.on.clipboard")
                                .font(.system(size: 20))
                                .foregroundColor(.white)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text("decks_paste_cta_title")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                            Text("decks_paste_cta_subtitle")
                                .font(.system(size: 13))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        Spacer()
                        Image(systemName: "arrow.right")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.white)
                    }
                    .padding(22)
                    .background(
                        RoundedRectangle(cornerRadius: 28)
                            .fill(EchoColor.accentPrimary)
                    )
                    .shadow(color: EchoColor.shadowAccent, radius: 32, x: 0, y: 12)
                }
                .buttonStyle(.plain)

                switch state {
                case .loading:
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                case .empty:
                    emptyBlock
                case .content(let count, let decks):
                    // Section header
                    HStack {
                        Text(String(format: NSLocalizedString("decks_library_count", comment: ""), count))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(EchoColor.foregroundPrimary)
                        Spacer()
                        Text("decks_recent")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(EchoColor.accentPrimary)
                    }

                    // Deck grid
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(decks) { deck in
                            DeckTileView(
                                title: deck.title,
                                cardCount: deck.cardCount,
                                coverEmoji: deck.coverEmoji,
                                authorLabel: deck.authorLabel,
                                onTap: { onDeckTap(deck.id) }
                            )
                        }
                    }
                case .error(let message):
                    VStack(spacing: 8) {
                        Text("decks_error_title")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(EchoColor.foregroundPrimary)
                        Text(message)
                            .font(.system(size: 14))
                            .foregroundColor(EchoColor.foregroundMuted)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 40)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 100)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
    }

    private var emptyBlock: some View {
        VStack(spacing: 14) {
            Text("📚").font(.system(size: 48))
            Text("decks_empty_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundColor(EchoColor.foregroundPrimary)
            Text("decks_empty_subtitle")
                .font(.system(size: 14))
                .foregroundColor(EchoColor.foregroundMuted)
                .multilineTextAlignment(.center)
            Button("decks_empty_create", action: onCreateDeckTap)
                .buttonStyle(.echoCompactFilled)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 32)
    }
}

#Preview {
    DecksView(
        state: .content(
            count: 2,
            decks: [
                DeckTileData(id: "1", title: "Spanish Basics", cardCount: 42, coverEmoji: "🇪🇸", authorLabel: "@you"),
                DeckTileData(id: "2", title: "Anatomy 101", cardCount: 128, coverEmoji: "🧠", authorLabel: "@you"),
            ]
        )
    )
}
