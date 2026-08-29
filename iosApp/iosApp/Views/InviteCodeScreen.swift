import SwiftUI
import Shared

/// Redeem an invite code instead of paying or verifying a phone.
struct InviteCodeScreen: View {
    var onBack: () -> Void
    var onDone: () -> Void

    @State private var viewModel: InviteCodeViewModel?
    @State private var uiState: InviteCodeUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var code = ""

    var body: some View {
        SignupScaffold(
            title: "signup_invite_title",
            subtitle: NSLocalizedString("signup_invite_subtitle", comment: ""),
            errorTitle: SignupErrorCopy.title(for: uiState?.error),
            errorMessage: SignupErrorCopy.message(for: uiState?.error),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 0) {
                FieldLabel(text: "signup_invite_label")
                Spacer().frame(height: 8)
                SignupTextField(
                    text: $code,
                    placeholder: "signup_invite_placeholder",
                    isEnabled: !(uiState?.isSubmitting ?? false),
                    isError: uiState?.error != nil,
                    // Codes are uppercase; typing them in lower case and being told they are wrong
                    // is a bad first impression of an invite.
                    capitalization: .characters
                )
                .onChange(of: code) { _, value in viewModel?.onCodeChange(code: value) }
                .accessibilityIdentifier("signup_invite_input")

                Spacer().frame(height: 24)
                SignupPrimaryButton(
                    title: "signup_invite_submit",
                    isLoading: uiState?.isSubmitting ?? false,
                    isEnabled: uiState?.canSubmit ?? false,
                    action: { viewModel?.onSubmit() }
                )
                .accessibilityIdentifier("signup_invite_submit")
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.inviteCodeViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? InviteCodeUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if effect is InviteCodeEffectNavigateToHandoff { onDone() }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
