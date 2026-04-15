import SwiftUI

struct GreetingHeader: View {
    let name: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hello,")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(EchoColor.foregroundMuted)
            Text("\(name) 👋")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(EchoColor.foregroundPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct EmptyStateCard: View {
    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                RoundedRectangle(cornerRadius: 28)
                    .fill(EchoColor.accentPrimarySoft)
                    .frame(width: 140, height: 140)
                Text("📚").font(.system(size: 64))
            }
            Text("No decks yet")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(EchoColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("Paste a list, import from a file, or start from scratch — your first deck is one tap away.")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(EchoColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(4)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.vertical, 36)
        .background(
            RoundedRectangle(cornerRadius: 28).fill(EchoColor.surfaceCard)
        )
        .shadow(color: Color.black.opacity(0.08), radius: 24, x: 0, y: 10)
    }
}

struct HomeCtaButtons: View {
    let onCreateDeck: () -> Void
    let onBrowseExamples: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button(action: onCreateDeck) {
                Text("Create your first deck")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                    .background(Capsule().fill(EchoColor.accentPrimary))
                    .shadow(color: EchoColor.accentPrimary.opacity(0.25), radius: 24, x: 0, y: 8)
            }
            Button(action: onBrowseExamples) {
                Text("Browse examples")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(EchoColor.accentPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                    .background(Capsule().fill(EchoColor.accentPrimarySoft))
            }
        }
    }
}

struct DueTodayHeroCard: View {
    let dueToday: Int
    let doneToday: Int
    let onStartStudy: () -> Void

    private var progress: CGFloat {
        guard dueToday > 0 else { return 0 }
        return min(1, max(0, CGFloat(doneToday) / CGFloat(dueToday)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("DUE TODAY")
                .font(.system(size: 11, weight: .bold))
                .kerning(1)
                .foregroundColor(EchoColor.accentPrimarySoft)
            HStack(alignment: .bottom) {
                Text("\(dueToday)")
                    .font(.system(size: 72, weight: .heavy))
                    .foregroundColor(.white)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("cards")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                    Text("to review")
                        .font(.system(size: 13))
                        .foregroundColor(EchoColor.accentPrimarySoft)
                }
                .padding(.bottom, 12)
            }
            VStack(alignment: .leading, spacing: 6) {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.25)).frame(height: 8)
                        Capsule().fill(Color.white).frame(width: geo.size.width * progress, height: 8)
                    }
                }
                .frame(height: 8)
                Text("\(doneToday) of \(dueToday) done · keep going!")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(EchoColor.accentPrimarySoft)
            }
            Button(action: onStartStudy) {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill").font(.system(size: 16))
                    Text("Start studying").font(.system(size: 17, weight: .bold))
                }
                .foregroundColor(EchoColor.accentPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(Capsule().fill(EchoColor.surfaceCard))
            }
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 28).fill(EchoColor.accentPrimary))
        .shadow(color: EchoColor.accentPrimary.opacity(0.2), radius: 32, x: 0, y: 12)
    }
}

struct TodaysDecksSection: View {
    let decks: [HomeDeckSummary]
    let onOpenDeck: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Today's decks")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(EchoColor.foregroundPrimary)
                Spacer()
                Text("See all")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(EchoColor.accentSecondary)
            }
            ForEach(decks) { deck in
                DeckRow(deck: deck, onTap: { onOpenDeck(deck.id) })
            }
        }
    }
}

struct DeckRow: View {
    let deck: HomeDeckSummary
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(EchoColor.accentPrimarySoft)
                        .frame(width: 56, height: 56)
                    Text(deck.coverInitial)
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundColor(EchoColor.accentPrimary)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(deck.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(EchoColor.foregroundPrimary)
                    Text("\(deck.dueCount) due · \(deck.cardCount) cards")
                        .font(.system(size: 13))
                        .foregroundColor(EchoColor.foregroundMuted)
                }
                Spacer()
                Text("\(deck.dueCount)")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(EchoColor.accentPrimary))
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 20).fill(EchoColor.surfaceCard))
            .shadow(color: Color.black.opacity(0.07), radius: 18, x: 0, y: 6)
        }
        .buttonStyle(.plain)
    }
}
