import SwiftUI

/// Hides its content while the screen is being recorded or mirrored.
///
/// **This is not `FLAG_SECURE`, and must not be presented as if it were.** Android can tell the
/// window server to refuse screenshots outright; iOS has no such API. The nearest honest thing is
/// `UIScreen.isCaptured`, which reports *recording and mirroring* — it says nothing about a plain
/// screenshot, which stays possible and undetectable until after the fact.
///
/// So this covers the phrase and says why, rather than implying a guarantee the platform does not
/// give. The escape hatch is deliberate too: someone screen-sharing on purpose, or using a
/// recording tool they trust, should not be locked out of their own recovery phrase.
struct SecureContent<Content: View>: View {
    @ViewBuilder var content: () -> Content

    @State private var isCaptured = UIScreen.main.isCaptured
    @State private var revealedAnyway = false

    private var isHidden: Bool { isCaptured && !revealedAnyway }

    var body: some View {
        ZStack {
            content()
                // Blurred rather than removed, so the layout does not jump when a recording starts.
                .blur(radius: isHidden ? 18 : 0)
                .accessibilityHidden(isHidden)
            if isHidden { warning }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            isCaptured = UIScreen.main.isCaptured
            // Re-arm when the recording stops, so a later one is covered again.
            if !isCaptured { revealedAnyway = false }
        }
    }

    private var warning: some View {
        VStack(spacing: 10) {
            Image(systemName: "eye.slash.fill")
                .font(.system(size: 22))
                .foregroundStyle(LoopkyColor.danger)
            Text("secure_screen_recording_title")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("secure_screen_recording_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button("secure_screen_reveal") { revealedAnyway = true }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentPrimary)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
        .shadow(color: LoopkyColor.shadowElevationMedium, radius: 12, y: 4)
        .padding(.horizontal, 24)
    }
}
