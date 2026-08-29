import SwiftUI

/// The way in when Pubky Ring is not on this device: the pending authorisation as a QR code for
/// Ring on the phone that holds the key.
///
/// The code carries the same one-shot `pubkyauth://` URL the deeplink would have, and the relay
/// poll behind it is the same one — Ring does not care whether it was opened by a tap here or a
/// camera over there. That is also why both escape hatches reuse the live authorisation rather
/// than starting a new one: a fresh sign-in would invalidate the code the user may already be
/// pointing a phone at.
///
/// Presented as a `.sheet` with detents rather than a bespoke overlay, so it gets the system's
/// drag-to-dismiss and Liquid Glass chrome on the iOS 26 SDK for free.
struct RingScanSheet: View {
    let authUrl: String
    /// Drives the body copy only. When Ring *is* here the deeplink has already been fired, so the
    /// sheet is a fallback rather than the main event.
    let ringInstalledHere: Bool
    var onOpenRingHere: () -> Void
    var onCancel: () -> Void

    @State private var didCopy = false

    var body: some View {
        VStack(spacing: 20) {
            Text("onboarding_qr_title")
                .font(.title2.bold())
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)

            Text(ringInstalledHere ? "onboarding_qr_body" : "onboarding_qr_sheet_body")
                .font(.subheadline)
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            QrCodeView(text: authUrl)

            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("onboarding_qr_waiting")
                    .font(.footnote)
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }

            VStack(spacing: 10) {
                if ringInstalledHere {
                    Button("onboarding_qr_open_here", action: onOpenRingHere)
                        .buttonStyle(.loopkySoft)
                }
                Button(didCopy ? "onboarding_qr_copied" : "onboarding_qr_copy") {
                    UIPasteboard.general.string = authUrl
                    didCopy = true
                }
                .buttonStyle(.loopkyOutline)

                Button("onboarding_qr_cancel", action: onCancel)
                    .font(.subheadline)
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(LoopkyColor.surfacePrimary)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        // Dragging the sheet away is the same intent as tapping Cancel: back out without leaving
        // an error behind. Without this the authorisation would keep polling behind a gone sheet.
        .interactiveDismissDisabled(false)
    }
}
