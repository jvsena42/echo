import SwiftUI

struct DeckTileView: View {
    let title: String
    let cardCount: Int
    let coverEmoji: String
    /// Caption after the card count — the author's name, or a tag on a profile grid.
    let authorLabel: String
    var showYouBadge: Bool = false
    var coverColor: Color = LoopkyColor.accentPrimarySoft
    var onTap: () -> Void = {}

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                // Cover area
                ZStack {
                    Rectangle()
                        .fill(coverColor)
                    Text(coverEmoji)
                        .font(.system(size: 48))
                }
                .frame(height: 120)
                .clipShape(
                    UnevenRoundedRectangle(
                        topLeadingRadius: 20,
                        topTrailingRadius: 20
                    )
                )

                // Body
                VStack(alignment: .leading, spacing: 6) {
                    Text(title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        Text(String(format: NSLocalizedString("component_deck_tile_card_count", comment: ""), cardCount))
                            .font(.system(size: 12))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        Text("·")
                            .font(.system(size: 12))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                        Text(authorLabel)
                            .font(.system(size: 12))
                            .foregroundColor(LoopkyColor.accentSecondary)
                            .lineLimit(1)
                        if showYouBadge {
                            YouBadge()
                        }
                    }
                }
                .padding(14)
            }
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(LoopkyColor.surfaceCard)
            )
            .shadow(color: LoopkyColor.shadowElevationMedium, radius: 24, x: 0, y: 8)
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    HStack(spacing: 14) {
        DeckTileView(
            title: "Spanish Basics",
            cardCount: 42,
            coverEmoji: "🇪🇸",
            authorLabel: "Cosmic-Crystal-Panda",
            showYouBadge: true
        )
        DeckTileView(
            title: "Anatomy 101",
            cardCount: 128,
            coverEmoji: "🧠",
            authorLabel: "Ada Lovelace",
            coverColor: LoopkyColor.accentSecondarySoft
        )
    }
    .padding()
    .background(LoopkyColor.surfacePrimary)
}
