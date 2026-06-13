import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `OnboardingView`. Owns the shared
/// `OnboardingViewModel`, observes its `StateFlow`, and reacts to one-shot effects
/// (open the Pubky Ring deeplink, open the install page, navigate home).
///
/// This is the wiring exemplar for all iOS screens: `*Screen` owns the VM via
/// `IosDependencies`, `*View` stays a pure layout.
struct OnboardingScreen: View {
    @Environment(\.openURL) private var openURL

    private let viewModel: OnboardingViewModel
    @StateObject private var state: FlowObserver<OnboardingUiState>
    @State private var effectSink: FlowEffectSink?

    var onSignedIn: () -> Void

    init(onSignedIn: @escaping () -> Void) {
        let vm = IosDependencies.shared.onboardingViewModel()
        viewModel = vm
        _state = StateObject(wrappedValue: FlowObserver(vm.state))
        self.onSignedIn = onSignedIn
    }

    var body: some View {
        OnboardingView(
            isWorking: isWorking,
            errorMessage: errorMessage,
            onSignInTapped: { viewModel.onSignInClick() },
            onInstallTapped: { viewModel.onGetRingClick() }
        )
        .onAppear { attachEffects() }
        .onDisappear { viewModel.onDispose() }
    }

    private var isWorking: Bool {
        switch state.value {
        case is OnboardingUiStateStarting, is OnboardingUiStateAwaitingApproval,
             is OnboardingUiStateVerifying:
            return true
        default:
            return false
        }
    }

    private var errorMessage: String? {
        (state.value as? OnboardingUiStateError)?.message
    }

    private func attachEffects() {
        guard effectSink == nil else { return }
        let vm = viewModel
        let signedIn = onSignedIn
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as OnboardingEffectOpenDeeplink:
                guard let url = URL(string: open.url) else {
                    vm.onDeeplinkUnavailable()
                    return
                }
                openURL(url) { accepted in
                    if !accepted { vm.onDeeplinkUnavailable() }
                }
            case let install as OnboardingEffectOpenInstallPage:
                if let url = URL(string: install.url) { openURL(url) }
            case is OnboardingEffectNavigateHome:
                signedIn()
            default:
                break
            }
        }
    }
}
