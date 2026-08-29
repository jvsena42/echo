import SwiftUI

/// The two ways Loopky points at pubky.app.
///
/// A Loopky account *is* a Pubky account — the same `profile.json`, the same follow graph, the
/// same key — and nothing on iOS said so: a foreign profile offered a generic globe, and the self
/// profile a plain "Open on pubky.app" button with no explanation of what that even is.
///
/// Both are deliberately quiet. The network underneath is worth knowing about, but it is not what
/// someone opened a flashcards app to do. Neither builds its own URL: the address comes from the
/// ViewModel, which reads it off `PubkyEnvironment`, so a debug build points at the staging
/// instance where its account actually exists.

/// pubky.app's mark, tinted like every other icon on the screen.
///
/// Monochrome on purpose. pubky.app sets the mark in lime on black, but that disc was the
/// highest-contrast thing on a cream screen and pulled the eye before the primary action beside
/// it — and the lime without the disc is the faintest thing on the screen. The shape alone says
/// whose logo it is.
struct PubkyMark: View {
    var size: CGFloat = 22

    var body: some View {
        Image("PubkyMark")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(LoopkyColor.foregroundSecondary)
    }
}

/// The button that leaves for pubky.app, in the same circle Share wears beside it — so it carries
/// no more weight in the row than that does.
struct PubkyAppIconButton: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            PubkyMark(size: 22)
                .frame(width: 44, height: 44)
                .background(Circle().fill(LoopkyColor.surfaceCard))
                .overlay(Circle().stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
        // The mark has no text of its own, so the label goes on the button.
        .accessibilityLabel(Text("pubky_app_open_profile"))
        .accessibilityIdentifier("pubky_app_open_profile")
    }
}

/// The self-profile call to action: one soft row explaining what the button does, for the person
/// who has no reason to know that the key they signed in with is also a social account.
///
/// A card rather than a banner, and it never claims a Loopky deck appears there — it does not.
/// What travels is the profile and, when they choose to announce one, the post.
struct PubkyAppProfileCta: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                PubkyMark(size: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("pubky_app_cta_title")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                    Text("pubky_app_cta_body")
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 16))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceSecondary))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("profile_pubky_app_cta")
    }
}
