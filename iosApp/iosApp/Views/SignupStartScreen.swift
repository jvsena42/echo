import SwiftUI
import Shared

/// Prove you are not a robot: SMS, Lightning, or an invite code.
struct SignupStartScreen: View {
    var onBack: () -> Void
    var onSms: () -> Void
    var onLightning: () -> Void
    var onInviteCode: () -> Void

    @State private var viewModel: SignupStartViewModel?
    @State private var uiState: SignupStartUiState?
    @State private var stateSink: FlowEffectSink?

    var body: some View {
        SignupScaffold(
            title: "signup_start_title",
            subtitle: NSLocalizedString("signup_start_subtitle", comment: ""),
            onBack: onBack
        ) {
            VStack(spacing: 16) {
                MethodCard(
                    title: "signup_sms_card_title",
                    detail: "signup_sms_card_note",
                    trailing: NSLocalizedString("signup_sms_card_price", comment: ""),
                    isEnabled: uiState?.isSmsEnabled ?? true,
                    action: onSms
                )
                .accessibilityIdentifier("signup_method_sms")

                MethodCard(
                    title: "signup_lightning_card_title",
                    detail: "signup_lightning_card_note",
                    trailing: lightningPrice,
                    isEnabled: uiState?.isLightningEnabled ?? true,
                    action: onLightning
                )
                .accessibilityIdentifier("signup_method_lightning")

                Button("signup_invite_link", action: onInviteCode)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentSecondary)
                    .accessibilityIdentifier("signup_method_invite")
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    /// Sats, with the fiat estimate when the price source answered. A price nobody can read is
    /// worse than none, so an unknown price says so rather than showing a blank.
    private var lightningPrice: String {
        guard let sats = uiState?.lightningPriceSat?.int64Value else {
            return NSLocalizedString("signup_lightning_card_price_unknown", comment: "")
        }
        if let fiat = uiState?.fiatPrice {
            return String(
                format: NSLocalizedString("signup_lightning_card_price_fiat", comment: ""),
                sats, fiat
            )
        }
        return String(format: NSLocalizedString("signup_lightning_card_price", comment: ""), sats)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.signupStartViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? SignupStartUiState }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}
