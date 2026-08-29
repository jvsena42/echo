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

    /// Resolved once, in `attach()`, and held in `@State`.
    ///
    /// Koin binds the ViewModels as factories, so `onboardingViewModel()` returns a *new* instance
    /// every call. Resolving it in `init` therefore minted a fresh VM on each SwiftUI re-render
    /// while the flow subscriptions stayed bound to the first one: the button drove one instance
    /// and the screen observed another, so sign-in ran to the relay and the UI never moved.
    @State private var viewModel: OnboardingViewModel?

    /// Held erased, and matched against the *concrete* state classes below.
    ///
    /// `OnboardingUiState` is a sealed interface, so it crosses the bridge as an Objective-C
    /// **protocol**. Casting an erased value to a generic parameter bound to a protocol — which is
    /// what `FlowObserver<OnboardingUiState>` did — yields `nil` every time, with no crash and no
    /// diagnostic: the screen simply never left its initial state and the sign-in appeared to do
    /// nothing. The concrete `OnboardingUiState*` types are classes and cast correctly.
    @State private var uiState: Any?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var onSignedIn: () -> Void
    /// The three doors Android's onboarding offers beside Ring sign-in. iOS had none of them.
    var onCreatePubky: () -> Void = {}
    var onRestore: () -> Void = {}
    /// A Ring sign-in whose key the homeserver has no account for.
    var onUnregistered: (String) -> Void = { _ in }
    /// This launch found no session: hand off to browsing rather than holding the visitor on a
    /// sign-in wall. Only fires when [autoExplore] is set, which is the launch that was routed
    /// here automatically rather than by an explicit "sign me out".
    var onExplore: () -> Void = {}
    var autoExplore: Bool = false

    var body: some View {
        Group {
            if isRestoring {
                SplashView()
            } else {
                OnboardingView(
                    isWorking: isWorking,
                    errorMessage: errorMessage,
                    onSignInTapped: { viewModel?.onSignInClick(handoff: RingHandoff.thisdevice) },
                    onRestoreTapped: onRestore,
                    onCreatePubkyTapped: onCreatePubky
                )
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
        // Driven off the state, not an effect, for the same reason Android does it this way:
        // `effects` has zero replay, so anything emitted from the ViewModel's init can be dropped
        // if the collector has not attached yet — and "no persisted session" is decided in exactly
        // that window. Idle is the ViewModel's word for it, and a StateFlow cannot lose it.
        .onChange(of: hasNoSession) { _, noSession in
            if autoExplore && noSession { onExplore() }
        }
        .sheet(isPresented: scanSheetBinding) {
            if let awaiting {
                RingScanSheet(
                    authUrl: awaiting.authUrl,
                    ringInstalledHere: awaiting.ringInstalledHere,
                    onOpenRingHere: { viewModel?.onOpenRingOnThisDevice() },
                    onGetRing: { viewModel?.onGetRingClick() },
                    onCancel: { viewModel?.onCancelSignIn() }
                )
            }
        }
    }

    private var awaiting: OnboardingUiStateAwaitingApproval? {
        uiState as? OnboardingUiStateAwaitingApproval
    }

    /// Raise the QR only where the deeplink cannot go.
    ///
    /// The shared VM fires `OpenDeeplink` only when the handoff is this device *and* Ring is
    /// actually installed here, so an authorisation that is waiting with `ringInstalledHere ==
    /// false` has nothing driving it — the code is the user's only way to approve it. This is the
    /// same rule Android applies (3224e94), and it is what makes a simulator signable at all:
    /// Ring cannot be installed on one, so the QR is always the path there.
    private var scanSheetBinding: Binding<Bool> {
        Binding(
            get: { awaiting.map { !$0.ringInstalledHere } ?? false },
            // Dismissing by drag is the same intent as Cancel: back out without an error.
            set: { if !$0 { viewModel?.onCancelSignIn() } }
        )
    }

    /// Cold start, still reading the persisted session back. Showing the splash here keeps a
    /// returning user from seeing the sign-in CTA flash by on the way home.
    private var isRestoring: Bool {
        uiState is OnboardingUiStateRestoring
    }

    /// The ViewModel has finished looking and found nothing to restore.
    private var hasNoSession: Bool { uiState is OnboardingUiStateIdle }

    private var isWorking: Bool {
        switch uiState {
        case is OnboardingUiStateStarting, is OnboardingUiStateAwaitingApproval,
             is OnboardingUiStateVerifying:
            return true
        default:
            return false
        }
    }

    private var errorMessage: String? {
        guard let error = uiState as? OnboardingUiStateError else { return nil }
        return ErrorCopy.message(for: error.reason)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.onboardingViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 }
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
            case let unregistered as OnboardingEffectNavigateUnregistered:
                onUnregistered(unregistered.pubky)
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
