import SwiftUI

/// SwiftUI onboarding screen.
///
/// Pure layout — state comes from the shared `OnboardingViewModel` via `OnboardingScreen`.
struct OnboardingView: View {
    var isWorking: Bool = false
    var errorMessage: String?
    var onSignInTapped: () -> Void = {}
    var onRestoreTapped: () -> Void = {}
    var onCreatePubkyTapped: () -> Void = {}

    var body: some View {
        ZStack {
            Color(red: 1.0, green: 0.98, blue: 0.96).ignoresSafeArea()
            VStack(spacing: 24) {
                brandRow
                Spacer(minLength: 0)
                heroBlock
                Spacer(minLength: 0)
                ctaBlock
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 32)
        }
    }

    private var brandRow: some View {
        HStack(spacing: 10) {
            Text("onboarding_brand_name")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(Color(red: 0.11, green: 0.11, blue: 0.12))
        }
    }

    private var heroBlock: some View {
        VStack(spacing: 20) {
            FoxPlate(size: 160, glyphSize: 96, containerColor: LoopkyColor.accentPrimarySoft)
            Text("brand_tagline")
                .font(.system(size: 30, weight: .heavy))
                .foregroundColor(Color(red: 0.11, green: 0.11, blue: 0.12))
                .multilineTextAlignment(.center)
                .lineSpacing(2)
            Text("onboarding_hero_subtitle")
                .font(.system(size: 15))
                .foregroundColor(Color(red: 0.35, green: 0.35, blue: 0.4))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
    }

    private var ctaBlock: some View {
        VStack(spacing: 12) {
            Button(action: onSignInTapped) {
                HStack(spacing: 10) {
                    Image(systemName: "key.fill")
                    Text(isWorking ? "onboarding_signin_waiting" : "onboarding_signin_default")
                }
            }
            .buttonStyle(.loopkyFilled)
            .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
            .disabled(isWorking)

            // The second door, presented as a matched pair with Ring rather than a footnote:
            // someone arriving with a recovery phrase has as much right to the front of the
            // screen as someone arriving with the app.
            Button(action: onRestoreTapped) {
                HStack(spacing: 10) {
                    Image(systemName: "arrow.down.doc")
                    Text("onboarding_restore")
                }
            }
            .buttonStyle(.loopkySoft)
            .disabled(isWorking)
            .accessibilityIdentifier("onboarding_restore")

            Text("onboarding_no_email_notice")
                .font(.system(size: 13))
                .foregroundColor(Color(red: 0.55, green: 0.55, blue: 0.6))
                .multilineTextAlignment(.center)

            if let error = errorMessage {
                Text(error)
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 0.85, green: 0.17, blue: 0.17))
            }

            // Deliberately a text link, not a third button — Android does the same. Creating a
            // pubky is the least common way in, and it costs money or a code.
            Button(action: onCreatePubkyTapped) {
                Text("onboarding_create_pubky")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Color(red: 0.48, green: 0.3, blue: 1.0))
            }
            .accessibilityIdentifier("onboarding_create_pubky")
        }
    }
}

#if DEBUG
struct OnboardingView_Previews: PreviewProvider {
    static var previews: some View { OnboardingView() }
}
#endif
