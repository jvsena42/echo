import SwiftUI
import Shared

struct MainView: View {
    @State private var selectedTab: LoopkyTab = .study

    /// `(deckId, authorPubky)`; the author is `nil` for a deck you own.
    var onDeckTap: (String, String?) -> Void = { _, _ in }
    var onImportTap: () -> Void = {}
    var onImportFileTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onOpenProfile: (String) -> Void = { _ in }
    var onOpenFollows: (String, FollowSource) -> Void = { _, _ in }
    var onBackUpNow: () -> Void = {}
    /// Browsing without an account.
    ///
    /// The guest shell is **Discover alone, with no tab bar** — Today, Decks and Profile are each
    /// a view onto a library, a review queue and an identity that do not exist yet, so a guest tab
    /// set of four would be one working destination and three apologies. What replaces them is the
    /// banner at the top of Discover, which is a way *in* rather than a wall.
    var isGuest: Bool = false
    /// Leave the guest shell for the sign-in flow.
    var onSignIn: () -> Void = {}

    /// Search is presented over the tabs rather than pushed: it is a way to reach a screen, not a
    /// place in the tab hierarchy, and dismissing it must return to whatever tab asked for it.
    @State private var isSearching = false

    var body: some View {
        Group {
            if isGuest { guestShell } else { tabs }
        }
        .sheet(isPresented: $isSearching) {
            SearchScreen(
                onOpenProfile: { pubky in isSearching = false; onOpenProfile(pubky) },
                onOpenDeck: { deckId, author in
                    isSearching = false
                    onDeckTap(deckId, author)
                },
                isGuest: isGuest,
                onSignIn: { isSearching = false; onSignIn() }
            )
        }
    }

    /// Discover alone — see [isGuest].
    private var guestShell: some View {
        DiscoverScreen(
            onOpenProfile: onOpenProfile,
            onOpenDeck: { deckId, author in onDeckTap(deckId, author) },
            onSearch: { isSearching = true },
            isGuest: true,
            onSignIn: onSignIn
        )
        .navigationBarHidden(true)
    }

    private var tabs: some View {
        // Native `TabView` → system `UITabBar` (Liquid Glass on the iOS 26 SDK). We only tint it
        // with Loopky's accent, rather than rebuilding the chrome from primitives.
        TabView(selection: $selectedTab) {
            HomeScreen(
                onOpenDeck: { onDeckTap($0, nil) },
                onCreateDeck: onCreateDeckTap,
                onBrowseExamples: onImportTap,
                onStartStudy: onStartStudy,
                onSignedOut: onSignedOut
            )
            .tabItem { Label(LoopkyTab.study.title, systemImage: LoopkyTab.study.iconName) }
            .tag(LoopkyTab.study)

            DecksScreen(
                onDeckTap: { onDeckTap($0, nil) },
                onImportTap: onImportTap,
                onImportFileTap: onImportFileTap,
                onCreateDeckTap: onCreateDeckTap
            )
            .tabItem { Label(LoopkyTab.decks.title, systemImage: LoopkyTab.decks.iconName) }
            .tag(LoopkyTab.decks)

            DiscoverScreen(
                onOpenProfile: onOpenProfile,
                onOpenDeck: { deckId, author in onDeckTap(deckId, author) },
                onSearch: { isSearching = true }
            )
            .tabItem { Label(LoopkyTab.discover.title, systemImage: LoopkyTab.discover.iconName) }
            .tag(LoopkyTab.discover)

            ProfileScreen(
                onSignedOut: onSignedOut,
                onOpenFollows: onOpenFollows,
                onOpenSettings: onOpenSettings,
                onBackUpNow: onBackUpNow
            )
                .tabItem { Label(LoopkyTab.profile.title, systemImage: LoopkyTab.profile.iconName) }
                .tag(LoopkyTab.profile)
        }
        .tint(LoopkyColor.accentPrimary)
        .toolbarBackground(LoopkyColor.navBarBackground, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        // Tab screens render their own in-content titles, so hide the NavigationStack's empty
        // navigation bar — otherwise it reserves space above each page title.
        .navigationBarHidden(true)
    }
}
