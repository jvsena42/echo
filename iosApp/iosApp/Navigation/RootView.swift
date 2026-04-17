import SwiftUI

/// Temporary root. Holds the navigation stack and owns the "am I signed in?" state.
///
/// Once the shared `OnboardingViewModel` is accessible through SKIE, this view should
/// observe `vm.state` and drive navigation from emitted effects rather than the local
/// `@State` used here.
struct RootView: View {
    @Environment(\.openURL) private var openURL
    @State private var isSignedIn: Bool = false
    @State private var pubky: String? = nil

    var body: some View {
        NavigationStack {
            if isSignedIn {
                MainView(
                    greetingName: pubky.map { "pk:\($0.prefix(6))" } ?? "there"
                )
            } else {
                OnboardingView(
                    onSignInTapped: handleSignIn,
                    onInstallTapped: handleInstall,
                )
            }
        }
        .onOpenURL { url in
            // TODO: parse echo://login-callback query params and forward to the shared VM
            // once wired. For now just log so the deeplink registration can be verified.
            print("[Echo] received deeplink: \(url.absoluteString)")
        }
    }

    private func handleSignIn() {
        // TODO: call shared `OnboardingViewModel.onSignInClick()` via SKIE bridge and open
        // the returned auth URL via `UIApplication.shared.open(_:)`.
    }

    private func handleInstall() {
        if let url = URL(string: "https://pubky.org/ring") {
            openURL(url)
        }
    }
}
