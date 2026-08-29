import SwiftUI
import Shared

struct GreetingHeader: View {
    let name: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("home_greeting_hello")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundMuted)
            Text(String(format: NSLocalizedString("home_greeting_name", comment: ""), name))
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct EmptyStateCard: View {
    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                RoundedRectangle(cornerRadius: 28)
                    .fill(LoopkyColor.accentPrimarySoft)
                    .frame(width: 140, height: 140)
                Text("📚").font(.system(size: 64))
            }
            Text("home_empty_title")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("home_empty_subtitle")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(4)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.vertical, 36)
        .background(
            RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.surfaceCard)
        )
        .shadow(color: LoopkyColor.shadowElevationHigh, radius: 24, x: 0, y: 10)
    }
}

struct HomeCtaButtons: View {
    let onCreateDeck: () -> Void
    let onBrowseExamples: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button("home_create_first_deck", action: onCreateDeck)
                .buttonStyle(.loopkyFilled)
                .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
            Button("home_browse_examples", action: onBrowseExamples)
                .buttonStyle(.loopkySoft)
        }
    }
}

struct DueTodayHeroCard: View {
    let dueToday: Int
    let doneToday: Int
    /// The day's tally against the goal. **Announced, never enforced** — the queue behind this
    /// serves every due card and every unseen one regardless, so this reports, it does not cap.
    var newCardsToday: Int = 0
    var newCardsGoal: Int = 0
    let onStartStudy: () -> Void

    private var progress: CGFloat {
        guard dueToday > 0 else { return 0 }
        return min(1, max(0, CGFloat(doneToday) / CGFloat(dueToday)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("home_due_today")
                .font(.system(size: 11, weight: .bold))
                .kerning(1)
                .foregroundColor(LoopkyColor.accentPrimarySoft)
            HStack(alignment: .bottom) {
                Text("\(dueToday)")
                    .font(.system(size: 72, weight: .heavy))
                    .foregroundColor(.white)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("home_cards")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                    Text("home_to_review")
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.accentPrimarySoft)
                }
                .padding(.bottom, 12)
            }
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(.white)
                Text(String(format: NSLocalizedString("home_progress_done", comment: ""), doneToday, dueToday))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(LoopkyColor.accentPrimarySoft)
                Text(verbatim: newCardsToday >= newCardsGoal
                     ? String(
                        format: NSLocalizedString("home_new_cards_goal_reached", comment: ""),
                        newCardsGoal
                     )
                     : String(
                        format: NSLocalizedString("home_new_cards_goal", comment: ""),
                        newCardsToday, newCardsGoal
                     ))
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(LoopkyColor.accentPrimarySoft)
            }
            Button(action: onStartStudy) {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill")
                    Text("home_start_studying")
                }
            }
            .buttonStyle(LoopkyFilledButtonStyle(fill: LoopkyColor.surfaceCard, foreground: LoopkyColor.accentPrimary, verticalPadding: 16))
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.accentPrimary))
        .shadow(color: LoopkyColor.shadowAccent, radius: 32, x: 0, y: 12)
    }
}

struct TodaysDecksSection: View {
    let decks: [HomeDeckSummary]
    let onOpenDeck: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("home_todays_decks")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Spacer()
                Text("home_see_all")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(LoopkyColor.accentSecondary)
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
                // Initial first, cover over it: the letter is the fallback, so a deck with a
                // picture must not show both. The image is sized to the tile rather than left to
                // grow, or the row with a cover stands taller than the row without.
                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(LoopkyColor.accentPrimarySoft)
                    Text(deck.coverInitial)
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundColor(LoopkyColor.accentPrimary)
                    if deck.coverImage != nil {
                        CardMediaImage(
                            ref: deck.coverImage,
                            authorPubky: deck.authorPubky,
                            deckId: deck.id,
                            contentMode: .fill
                        )
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(deck.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    Text(String(format: NSLocalizedString("home_deck_due_cards", comment: ""), deck.dueCount, deck.cardCount))
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                }
                Spacer()
                Text("\(deck.dueCount)")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(LoopkyColor.accentPrimary))
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
            .shadow(color: LoopkyColor.shadowElevationMedium, radius: 18, x: 0, y: 6)
        }
        .buttonStyle(.plain)
    }
}

private let sampleHomeDecks = [
    HomeDeckSummary(id: "1", title: "Spanish Basics", cardCount: 42, dueCount: 12, coverInitial: "S"),
    HomeDeckSummary(id: "2", title: "Bio 101: Cells", cardCount: 28, dueCount: 7, coverInitial: "B"),
    HomeDeckSummary(id: "3", title: "Guitar Chords", cardCount: 18, dueCount: 5, coverInitial: "G"),
]

#Preview("Content") {
    ScrollView {
        VStack(spacing: 16) {
            GreetingHeader(name: "Maria")
            DueTodayHeroCard(dueToday: 24, doneToday: 8, onStartStudy: {})
            TodaysDecksSection(decks: sampleHomeDecks, onOpenDeck: { _ in })
        }
        .padding()
    }
    .background(LoopkyColor.surfacePrimary)
}

#Preview("Empty") {
    ScrollView {
        VStack(spacing: 16) {
            GreetingHeader(name: "Maria")
            EmptyStateCard()
            HomeCtaButtons(onCreateDeck: {}, onBrowseExamples: {})
        }
        .padding()
    }
    .background(LoopkyColor.surfacePrimary)
}

/// Nothing due and nothing unseen.
///
/// Says *when* the next review lands, which is what makes an empty queue read as earned rather
/// than as a dead end. The interval comes from `RelativeDateTimeFormatter` rather than a ported
/// helper — the system already words this, in the reader's own language.
struct CaughtUpCard: View {
    let nextDueAtMillis: Int64?

    var body: some View {
        VStack(spacing: 10) {
            Text("🎉").font(.system(size: 40))
            Text("home_caught_up_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text(verbatim: subtitle)
                .font(.system(size: 14))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.accentPrimarySoft))
        .accessibilityIdentifier("home_caught_up")
    }

    private var subtitle: String {
        guard let millis = nextDueAtMillis else {
            return NSLocalizedString("home_caught_up_no_next_due", comment: "")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        let relative = formatter.localizedString(
            for: Date(timeIntervalSince1970: Double(millis) / 1000),
            relativeTo: Date()
        )
        return String(format: NSLocalizedString("home_caught_up_next_due", comment: ""), relative)
    }
}
