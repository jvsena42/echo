import SwiftUI

/// Your own profile. Pure layout; `ProfileScreen` owns the ViewModel.
///
/// The backup card Android shows above Sign out is deliberately absent: the backup screens have no
/// iOS counterpart yet (#149), so a card offering to back a key up would lead nowhere.
struct ProfileView: View {
    var state: ProfileViewState = ProfileViewState()
    @Binding var editName: String
    @Binding var editBio: String

    var onEditProfile: () -> Void = {}
    var onDismissEdit: () -> Void = {}
    var onSaveEdit: () -> Void = {}
    var onEditNameChanged: (String) -> Void = { _ in }
    var onEditBioChanged: (String) -> Void = { _ in }
    var onCopyPubky: () -> Void = {}
    var onShare: () -> Void = {}
    var onOpenOnPubkyApp: () -> Void = {}
    var onSignOut: () -> Void = {}
    var onOpenFollowing: () -> Void = {}
    var onOpenFollowers: () -> Void = {}

    @State private var isConfirmingSignOut = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                toolbar
                if state.isLoading {
                    ProgressView().padding(.top, 60)
                } else {
                    hero
                    stats
                    peopleRow
                    actions
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationBarHidden(true)
        .sheet(isPresented: Binding(get: { state.showEditSheet }, set: { if !$0 { onDismissEdit() } })) {
            editSheet
        }
        .confirmationDialog(
            Text("profile_sign_out_dialog_title"),
            isPresented: $isConfirmingSignOut,
            titleVisibility: .visible
        ) {
            Button("profile_sign_out_confirm", role: .destructive, action: onSignOut)
            Button("profile_sign_out_cancel", role: .cancel) {}
        } message: {
            Text("profile_sign_out_dialog_message")
        }
    }

    private var toolbar: some View {
        HStack {
            Text("profile_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button(action: onShare) {
                Image(systemName: "square.and.arrow.up")
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("profile_share_content_description"))
        }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            avatar
            Text(state.label)
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button(action: onCopyPubky) {
                HStack(spacing: 4) {
                    Text(state.shortPubky)
                        .font(.system(size: 13, design: .monospaced))
                    Image(systemName: "doc.on.doc").font(.system(size: 11))
                }
                .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .accessibilityLabel(Text("profile_copy_pubky"))
            if let bio = state.bio, !bio.isEmpty {
                Text(bio)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var avatar: some View {
        ZStack {
            Circle().fill(LoopkyColor.accentSecondarySoft)
            if let avatarUrl = state.avatarUrl, let url = URL(string: avatarUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    initialText
                }
                .clipShape(Circle())
            } else {
                initialText
            }
        }
        .frame(width: 88, height: 88)
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
            stat(state.dueCount, "profile_stat_due", tint: LoopkyColor.accentPrimary)
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft.opacity(0.5)))
    }

    private func stat(_ value: Int, _ label: LocalizedStringKey, tint: Color = LoopkyColor.foregroundPrimary) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }

    /// Both counts stay hidden until they resolve — they land later than the rest of the screen,
    /// and a "0 Followers" that turns into 12 reads as a wrong answer rather than a pending one.
    private var peopleRow: some View {
        HStack(spacing: 10) {
            if let following = state.followingCount {
                peopleButton(following, "profile_stat_following", action: onOpenFollowing)
            }
            if let followers = state.followerCount {
                peopleButton(followers, "profile_stat_followers", action: onOpenFollowers)
            }
        }
    }

    private func peopleButton(_ value: Int, _ label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text("\(value)").font(.system(size: 15, weight: .heavy))
                Text(label).font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(LoopkyColor.foregroundSecondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().fill(LoopkyColor.surfaceCard))
            .overlay(Capsule().stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            Button("profile_edit_profile", action: onEditProfile).buttonStyle(.loopkySoft)
            Button("profile_open_on_pubky_app", action: onOpenOnPubkyApp).buttonStyle(.loopkyOutline)
            Button("profile_sign_out") { isConfirmingSignOut = true }
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(LoopkyColor.danger)
                .padding(.top, 6)
        }
    }

    private var editSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("profile_edit_name_placeholder", text: Binding(
                        get: { editName },
                        set: { editName = $0; onEditNameChanged($0) }
                    ))
                } header: {
                    Text("profile_edit_name_label")
                }
                Section {
                    TextField("profile_edit_bio_placeholder", text: Binding(
                        get: { editBio },
                        set: { editBio = $0; onEditBioChanged($0) }
                    ), axis: .vertical)
                    .lineLimit(3...6)
                } header: {
                    Text("profile_edit_bio_label")
                }
            }
            .navigationTitle(Text("profile_edit_profile_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("profile_dismiss", action: onDismissEdit)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if state.isSaving {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("profile_save", action: onSaveEdit)
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
