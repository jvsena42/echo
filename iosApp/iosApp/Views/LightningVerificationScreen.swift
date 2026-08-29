import SwiftUI
import Shared

/// Pay a small Lightning invoice to prove you are not a robot.
struct LightningVerificationScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @Environment(\.openURL) private var openURL

    @State private var viewModel: LightningVerificationViewModel?
    @State private var uiState: LightningVerificationUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var toast: String?

    var body: some View {
        SignupScaffold(
            title: uiState?.isCheckingEarlierPayment ?? false
                ? "signup_lightning_resumed_title"
                : "signup_lightning_title",
            subtitle: subtitle,
            errorTitle: SignupErrorCopy.title(for: uiState?.error),
            errorMessage: SignupErrorCopy.message(for: uiState?.error),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 0) {
                if let invoice = payableInvoice { invoiceBlock(invoice) }
                if uiState?.isLoading ?? false || uiState?.isAwaitingPayment ?? false { waiting }
                if uiState?.canRetry ?? false {
                    Spacer().frame(height: 16)
                    SignupPrimaryButton(title: "signup_lightning_new_invoice") {
                        viewModel?.createInvoice()
                    }
                }
            }
        }
        .overlay(alignment: .bottom) { toastView }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    /// An invoice with no bolt11 is a placeholder while the resumed one is checked, not something
    /// to render a QR for.
    private var payableInvoice: LnInvoice? {
        uiState?.invoice.flatMap { $0.bolt11.isEmpty ? nil : $0 }
    }

    private var subtitle: String {
        guard let invoice = uiState?.invoice else { return "" }
        let resumed = uiState?.isResumed ?? false
        let sats = invoice.amountSat
        if let fiat = uiState?.fiatPrice {
            let key = resumed ? "signup_lightning_resumed_subtitle_fiat" : "signup_lightning_amount_fiat"
            return String(format: NSLocalizedString(key, comment: ""), sats, fiat)
        }
        let key = resumed ? "signup_lightning_resumed_subtitle" : "signup_lightning_amount"
        return String(format: NSLocalizedString(key, comment: ""), sats)
    }

    private func invoiceBlock(_ invoice: LnInvoice) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // The QR carries the bare BOLT11, uppercased — uppercase lets the encoder use
            // alphanumeric mode, and a `lightning:` prefix inside the code breaks some scanners.
            // The white plate is unconditional: a QR inverted for dark mode is unreadable.
            QrCodeView(text: invoice.bolt11.uppercased())
                .frame(maxWidth: .infinity)
                .accessibilityLabel(Text("signup_lightning_qr_content_description"))

            Spacer().frame(height: 20)
            Text(verbatim: invoice.bolt11)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .lineLimit(3)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))

            Spacer().frame(height: 20)
            SignupPrimaryButton(title: "signup_lightning_open_wallet") {
                viewModel?.onOpenWalletClick()
            }
            .accessibilityIdentifier("signup_lightning_open_wallet")

            Spacer().frame(height: 8)
            Button("signup_lightning_copy") { viewModel?.onCopyInvoiceClick() }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.accentSecondary)
        }
    }

    private var waiting: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small).tint(LoopkyColor.accentPrimary)
            Text(uiState?.isResumed ?? false
                 ? "signup_lightning_resumed_waiting"
                 : "signup_lightning_waiting")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .padding(.top, 16)
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            Text(verbatim: toast)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundOnAccent)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(LoopkyColor.foregroundPrimary.opacity(0.9)))
                .padding(.bottom, 40)
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.lightningVerificationViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? LightningVerificationUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let copy as LightningVerificationEffectCopyToClipboard:
                UIPasteboard.general.string = copy.text
                flash(NSLocalizedString("signup_lightning_copied", comment: ""))
            case let wallet as LightningVerificationEffectOpenWallet:
                openWallet(wallet.uri)
            case is LightningVerificationEffectNavigateToHandoff:
                onDone()
            default:
                break
            }
        }
    }

    /// If nothing on the device handles `lightning:`, copy the bare invoice and say so — a dead
    /// tap with no explanation is the worst outcome on a screen the user is trying to pay from.
    private func openWallet(_ uri: String) {
        guard let url = URL(string: uri), UIApplication.shared.canOpenURL(url) else {
            UIPasteboard.general.string = uri.replacingOccurrences(of: "lightning:", with: "")
            flash(NSLocalizedString("signup_lightning_no_wallet", comment: ""))
            return
        }
        openURL(url)
    }

    private func flash(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { toast = nil }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
