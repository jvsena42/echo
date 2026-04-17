import SwiftUI

struct DecksView: View {
    var onDeckTap: (String) -> Void = { _ in }
    var onImportTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}

    // Static preview data until VM is wired via SKIE
    private let previewDecks: [DeckTileData] = [
        DeckTileData(id: "1", title: "Spanish Basics", cardCount: 42, coverEmoji: "🇪🇸", authorLabel: "@you", coverColor: nil),
        DeckTileData(id: "2", title: "Anatomy 101", cardCount: 128, coverEmoji: "🧠", authorLabel: "@you", coverColor: Color(red: 0.914, green: 0.878, blue: 1.0)),
        DeckTileData(id: "3", title: "Guitar Chords", cardCount: 24, coverEmoji: "🎸", authorLabel: "@you", coverColor: Color(red: 1.0, green: 0.914, blue: 0.702)),
        DeckTileData(id: "4", title: "Hiragana", cardCount: 46, coverEmoji: "🇯🇵", authorLabel: "@you", coverColor: Color(red: 0.820, green: 0.961, blue: 0.890)),
        DeckTileData(id: "5", title: "Physics Formulas", cardCount: 31, coverEmoji: "⚛️", authorLabel: "@you", coverColor: Color(red: 0.894, green: 0.878, blue: 1.0)),
        DeckTileData(id: "6", title: "Wine Regions", cardCount: 18, coverEmoji: "🍷", authorLabel: "@you", coverColor: Color(red: 1.0, green: 0.851, blue: 0.851)),
    ]

    private let columns = [
        GridItem(.flexible(), spacing: 14),
        GridItem(.flexible(), spacing: 14),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Text("Your decks")
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
                            Text("Paste to import")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                            Text("Turn any list into a deck")
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
                    .shadow(color: EchoColor.accentPrimary.opacity(0.2), radius: 32, x: 0, y: 12)
                }
                .buttonStyle(.plain)

                // Section header
                HStack {
                    Text("Library · \(previewDecks.count)")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Spacer()
                    Text("Recent")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(EchoColor.accentPrimary)
                }

                // Deck grid
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(previewDecks) { deck in
                        DeckTileView(
                            title: deck.title,
                            cardCount: deck.cardCount,
                            coverEmoji: deck.coverEmoji,
                            authorLabel: deck.authorLabel,
                            coverColor: deck.coverColor ?? EchoColor.accentPrimarySoft,
                            onTap: { onDeckTap(deck.id) }
                        )
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 100)
        }
        .background(EchoColor.surfacePrimary.ignoresSafeArea())
    }
}

private struct DeckTileData: Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
    let coverColor: Color?
}

#Preview {
    DecksView()
}
