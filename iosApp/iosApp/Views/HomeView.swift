import SwiftUI

// TODO(iOS Koin + SKIE): wire the shared `HomeViewModel` once the Kotlin framework is
// bootstrapped on iOS. Until then this view renders the empty-state fallback so the
// design can be iterated on without blocking on the DI wiring.

/// SwiftUI Home screen mirroring Pencil nodes `xaQR5` (daily study) and `nwHYV` (empty state).
struct HomeView: View {
    var greetingName: String = "there"
    var state: HomeViewState = .empty
    var onCreateDeck: () -> Void = {}
    var onBrowseExamples: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onOpenDeck: (String) -> Void = { _ in }

    var body: some View {
        ZStack {
            EchoColor.surfacePrimary.ignoresSafeArea()
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
                    case .content(let due, let done, let decks):
                        DueTodayHeroCard(
                            dueToday: due,
                            doneToday: done,
                            onStartStudy: onStartStudy
                        )
                        TodaysDecksSection(decks: decks, onOpenDeck: onOpenDeck)
                    case .error(let message):
                        Text("Something went wrong")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(EchoColor.foregroundPrimary)
                        Text(message)
                            .font(.system(size: 14))
                            .foregroundColor(EchoColor.foregroundMuted)
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
    case content(dueToday: Int, doneToday: Int, decks: [HomeDeckSummary])
    case error(String)
}

struct HomeDeckSummary: Equatable, Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let dueCount: Int
    let coverInitial: String
}

#if DEBUG
struct HomeView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            HomeView(greetingName: "Maria", state: .empty)
                .previewDisplayName("Empty")
            HomeView(
                greetingName: "Maria",
                state: .content(
                    dueToday: 24,
                    doneToday: 8,
                    decks: [
                        HomeDeckSummary(id: "1", title: "Spanish Basics", cardCount: 42, dueCount: 12, coverInitial: "S"),
                        HomeDeckSummary(id: "2", title: "Bio 101: Cells", cardCount: 28, dueCount: 7, coverInitial: "B"),
                        HomeDeckSummary(id: "3", title: "Guitar Chords", cardCount: 18, dueCount: 5, coverInitial: "G"),
                    ]
                )
            )
            .previewDisplayName("Content")
        }
    }
}
#endif
