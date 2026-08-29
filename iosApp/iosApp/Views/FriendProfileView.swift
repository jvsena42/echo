import SwiftUI

/// Someone else's profile. Pure layout; `FriendProfileScreen` owns the ViewModel.
struct FriendProfileView: View {
    var state: FriendProfileViewState = FriendProfileViewState()
    var onBack: () -> Void = {}
    var onToggleFollow: () -> Void = {}
    var onCopyPubky: () -> Void = {}
    var onShare: () -> Void = {}
    var onOpenOnPubkyApp: () -> Void = {}
    var onRefresh: () -> Void = {}
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onDismissSignInPrompt: () -> Void = {}

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                toolbar
                if state.isLoading {
                    ProgressView().padding(.top, 60)
                } else {
                    hero
                    stats
                    if !state.isSelf { followButton }
                    if let errorMessage = state.errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.danger)
                    }
                    decksSection
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .refreshable { onRefresh() }
        // A guest can read the whole profile; only Follow needs an account.
        .alert(
            Text("sign_in_prompt_title"),
            isPresented: Binding(get: { state.requiresSignIn }, set: { if !$0 { onDismissSignInPrompt() } })
        ) {
            Button("profile_dismiss", role: .cancel, action: onDismissSignInPrompt)
        }
    }

    private var toolbar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left").foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("friend_profile_back_content_description"))
            Spacer()
            Button(action: onOpenOnPubkyApp) {
                Image(systemName: "globe").foregroundStyle(LoopkyColor.accentPrimary)
            }
            Button(action: onShare) {
                Image(systemName: "square.and.arrow.up").foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("friend_profile_share"))
        }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().fill(LoopkyColor.accentSecondarySoft)
                if let avatarUrl = state.avatarUrl, let url = URL(string: avatarUrl) {
                    AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { initialText }
                        .clipShape(Circle())
                } else {
                    initialText
                }
            }
            .frame(width: 88, height: 88)

            Text(state.label)
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button(action: onCopyPubky) {
                Text(state.shortPubky)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .accessibilityLabel(Text("friend_profile_copy_pubky"))
            if let bio = state.bio, !bio.isEmpty {
                Text(bio)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var initialText: some View {
        Text(state.initial)
            .font(.system(size: 34, weight: .heavy))
            .foregroundStyle(LoopkyColor.accentSecondary)
    }

    private var stats: some View {
        HStack(spacing: 0) {
            stat(state.deckCount, "profile_stat_decks")
            stat(state.cardCount, "profile_stat_cards")
            if let following = state.followingCount { stat(following, "profile_stat_following") }
            if let followers = state.followerCount { stat(followers, "profile_stat_followers") }
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft.opacity(0.5)))
    }

    private func stat(_ value: Int, _ label: LocalizedStringKey) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }

    private var followButton: some View {
        Button(action: onToggleFollow) {
            if state.isProcessingFollow {
                ProgressView().controlSize(.small)
            } else {
                Text(state.isFollowing ? "discover_following" : "discover_follow")
            }
        }
        .buttonStyle(state.isFollowing ? AnyButtonStyle(.loopkyOutline) : AnyButtonStyle(.loopkyFilled))
        .disabled(state.isProcessingFollow)
        .accessibilityIdentifier("friend_profile_follow")
    }

    @ViewBuilder
    private var decksSection: some View {
        if state.decks.isEmpty {
            Text("friend_profile_no_public_decks")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .padding(.top, 20)
        } else {
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(state.decks) { deck in
                    DeckTileView(
                        title: deck.title,
                        cardCount: deck.cardCount,
                        coverEmoji: deck.coverEmoji,
                        authorLabel: deck.authorLabel,
                        onTap: { onOpenDeck(deck.id, deck.authorPubky) }
                    )
                }
            }
        }
    }
}

/// Lets one property hold either of two concrete `ButtonStyle`s.
struct AnyButtonStyle: ButtonStyle {
    private let makeBody: (Configuration) -> AnyView

    init<S: ButtonStyle>(_ style: S) {
        makeBody = { configuration in AnyView(style.makeBody(configuration: configuration)) }
    }

    func makeBody(configuration: Configuration) -> some View { makeBody(configuration) }
}
