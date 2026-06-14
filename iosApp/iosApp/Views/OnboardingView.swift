import SwiftUI

/// SwiftUI onboarding screen mirroring the Pencil design (node `l6a3j`).
///
/// Pure layout — state comes from the shared `OnboardingViewModel` via `OnboardingScreen`.
struct OnboardingView: View {
    var isWorking: Bool = false
    var errorMessage: String?
    var onSignInTapped: () -> Void = {}
    var onInstallTapped: () -> Void = {}

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
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color(red: 1.0, green: 0.36, blue: 0.0))
                    .frame(width: 44, height: 44)
                Text("🦊").font(.system(size: 24))
            }
            Text("onboarding_brand_name")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(Color(red: 0.11, green: 0.11, blue: 0.12))
        }
    }

    private var heroBlock: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(Color(red: 1.0, green: 0.91, blue: 0.84))
                    .frame(width: 160, height: 160)
                Text("🦊").font(.system(size: 96))
            }
            Text("onboarding_hero_title")
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
            .buttonStyle(.echoFilled)
            .shadow(color: EchoColor.shadowAccent, radius: 24, x: 0, y: 8)
            .disabled(isWorking)

            Text("onboarding_no_email_notice")
                .font(.system(size: 13))
                .foregroundColor(Color(red: 0.55, green: 0.55, blue: 0.6))
                .multilineTextAlignment(.center)

            if let error = errorMessage {
                Text(error)
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 0.85, green: 0.17, blue: 0.17))
            }

            Button(action: onInstallTapped) {
                Text("onboarding_get_ring")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Color(red: 0.48, green: 0.3, blue: 1.0))
            }
        }
    }
}

#if DEBUG
struct OnboardingView_Previews: PreviewProvider {
    static var previews: some View { OnboardingView() }
}
#endif
