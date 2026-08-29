import SwiftUI
import Shared

// TODO(iOS Koin + SKIE): wire the shared `HomeViewModel` once the Kotlin framework is
// bootstrapped on iOS. Until then this view renders the empty-state fallback so the
// design can be iterated on without blocking on the DI wiring.

/// SwiftUI Home screen: the daily study state, and the empty state before any deck exists.
struct HomeView: View {
    var greetingName: String = "there"
    var state: HomeViewState = .empty
    var onCreateDeck: () -> Void = {}
    var onBrowseExamples: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onOpenDeck: (String) -> Void = { _ in }

    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    GreetingHeader(name: greetingName)
                    switch state {
                    case .loading:
                        ProgressView().frame(maxWidth: .infinity)
                    case .empty:
                        EmptyStateCard()
                        HomeCtaButtons(
                            onCreateDeck: onCreateDeck,
                            onBrowseExamples: onBrowseExamples
                        )
                    case .content(let content):
                        // Nothing due *and* nothing unseen is a different screen, not a hero
                        // reading zero: a primary CTA whose only outcome is "All done!" is a dead
                        // end dressed as an action.
                        if content.dueToday == 0 && content.newToday == 0 {
                            CaughtUpCard(nextDueAtMillis: content.nextDueAtMillis)
                        } else {
                            DueTodayHeroCard(
                                dueToday: content.dueToday,
                                doneToday: content.doneToday,
                                newCardsToday: content.newCardsToday,
                                newCardsGoal: content.newCardsGoal,
                                onStartStudy: onStartStudy
                            )
                        }
                        TodaysDecksSection(decks: content.decks, onOpenDeck: onOpenDeck)
                    case .error(let message):
                        Text("home_error_title")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(LoopkyColor.foregroundPrimary)
                        Text(message)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 100)
            }
        }
    }
}

enum HomeViewState: Equatable {
    case loading
    case empty
    case content(HomeContentData)
    case error(String)
}

/// Everything the loaded Today screen renders.
///
/// A struct rather than four associated values on the case: the hero grew a goal tally and the
/// screen grew a caught-up state, and a five-tuple stops being readable at the call site.
struct HomeContentData: Equatable {
    var dueToday: Int = 0
    var doneToday: Int = 0
    /// Cards never studied. Separate from due, because nothing about an unseen card is late.
    var newToday: Int = 0
    var newCardsToday: Int = 0
    var newCardsGoal: Int = 0
    var nextDueAtMillis: Int64?
    var decks: [HomeDeckSummary] = []
}

struct HomeDeckSummary: Equatable, Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let dueCount: Int
    let coverInitial: String
    var coverImage: MediaRef.Image?
    var authorPubky: String = ""

    // `MediaRef.Image` is a Kotlin class, so identity comparison is what `Equatable` can offer
    // here — enough for SwiftUI to notice a deck's cover arriving.
    static func == (lhs: HomeDeckSummary, rhs: HomeDeckSummary) -> Bool {
        lhs.id == rhs.id && lhs.title == rhs.title && lhs.cardCount == rhs.cardCount
            && lhs.dueCount == rhs.dueCount && lhs.coverInitial == rhs.coverInitial
            && lhs.coverImage === rhs.coverImage && lhs.authorPubky == rhs.authorPubky
    }
}

#if DEBUG
struct HomeView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            HomeView(greetingName: "Maria", state: .empty)
                .previewDisplayName("Empty")
            HomeView(
                greetingName: "Maria",
                state: .content(HomeContentData(
                    dueToday: 24,
                    doneToday: 8,
                    decks: [
                        HomeDeckSummary(id: "1", title: "Spanish Basics", cardCount: 42, dueCount: 12, coverInitial: "S"),
                        HomeDeckSummary(id: "2", title: "Bio 101: Cells", cardCount: 28, dueCount: 7, coverInitial: "B"),
                        HomeDeckSummary(id: "3", title: "Guitar Chords", cardCount: 18, dueCount: 5, coverInitial: "G"),
                    ]
                ))
            )
            .previewDisplayName("Content")
        }
    }
}
#endif
