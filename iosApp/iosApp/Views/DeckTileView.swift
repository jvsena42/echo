import SwiftUI

struct DeckTileView: View {
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
    var coverColor: Color = EchoColor.accentPrimarySoft
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
                        .foregroundColor(EchoColor.foregroundPrimary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        Text("\(cardCount) cards")
                            .font(.system(size: 12))
                            .foregroundColor(EchoColor.foregroundMuted)
                        Text("·")
                            .font(.system(size: 12))
                            .foregroundColor(EchoColor.foregroundMuted)
                        Text(authorLabel)
                            .font(.system(size: 12))
                            .foregroundColor(EchoColor.accentSecondary)
                    }
                }
                .padding(14)
            }
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(EchoColor.surfaceCard)
            )
            .shadow(color: Color.black.opacity(0.07), radius: 24, x: 0, y: 8)
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
            authorLabel: "@you"
        )
        DeckTileView(
            title: "Anatomy 101",
            cardCount: 128,
            coverEmoji: "🧠",
            authorLabel: "@you",
            coverColor: EchoColor.accentSecondarySoft
        )
    }
    .padding()
    .background(EchoColor.surfacePrimary)
}
