import SwiftUI

/// Settings, as a native grouped `List` rather than a hand-built stack of cards — so it inherits
/// the platform's section styling, row insets and Liquid Glass chrome on the iOS 26 SDK.
struct SettingsView: View {
    var state: SettingsViewState = SettingsViewState()
    var onBack: () -> Void = {}
    var onCopyPubky: () -> Void = {}
    var onCopyHomeserver: () -> Void = {}
    var onShareOnPubkyChanged: (Bool) -> Void = { _ in }
    var onGoalChanged: (Int) -> Void = { _ in }
    var onIntervalChanged: (StudyGrade, Int) -> Void = { _, _ in }
    var onSaveUnsplashKey: (String) -> Void = { _ in }
    var onRemoveUnsplashKey: () -> Void = {}
    var onDismissUnsplashError: () -> Void = {}
    var onSignOut: () -> Void = {}
    var onConfirmSignOutWithoutBackup: () -> Void = {}
    var onDismissSignOutWarning: () -> Void = {}
    var onDeleteAccount: () -> Void = {}
    var onConfirmDeleteAccount: () -> Void = {}
    var onDismissDeleteAccount: () -> Void = {}
    var onOpenUrl: (String) -> Void = { _ in }

    @State private var unsplashKey = ""
    @State private var isConfirmingSignOut = false

    private static let privacyPolicyUrl = "https://loopky.app/privacy"

    var body: some View {
        List {
            identitySection
            studyingSection
            sharingSection
            imageSearchSection
            aboutSection
            dangerSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(LoopkyColor.surfacePrimary.ignoresSafeArea())
        .navigationTitle(Text("settings_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: onBack) { Image(systemName: "chevron.left") }
                    .accessibilityLabel(Text("settings_back_content_description"))
            }
        }
        .confirmationDialog(
            Text("settings_sign_out_dialog_title"),
            isPresented: $isConfirmingSignOut,
            titleVisibility: .visible
        ) {
            Button("settings_sign_out", role: .destructive, action: onSignOut)
            Button("settings_cancel", role: .cancel) {}
        } message: {
            Text("settings_sign_out_dialog_message")
        }
        // Refused because this device holds a key nobody has a copy of. A separate, sterner
        // prompt than the ordinary sign-out — losing it loses the account.
        .alert(
            Text("settings_signout_unbacked_title"),
            isPresented: Binding(
                get: { state.unbackedUpPubky != nil },
                set: { if !$0 { onDismissSignOutWarning() } }
            )
        ) {
            Button("settings_signout_unbacked_confirm", role: .destructive, action: onConfirmSignOutWithoutBackup)
            Button("settings_cancel", role: .cancel, action: onDismissSignOutWarning)
        } message: {
            Text("settings_signout_unbacked_body")
        }
        .alert(
            Text("settings_delete_account_dialog_title"),
            isPresented: Binding(get: { state.isDeleting }, set: { if !$0 { onDismissDeleteAccount() } })
        ) {
            Button("settings_delete_account_confirm", role: .destructive, action: onConfirmDeleteAccount)
            Button("settings_cancel", role: .cancel, action: onDismissDeleteAccount)
        } message: {
            Text("settings_delete_account_dialog_irreversible")
        }
    }

    private var identitySection: some View {
        Section {
            Button(action: onCopyPubky) {
                LabeledContent {
                    Text(state.pubky).font(.system(size: 13, design: .monospaced)).lineLimit(1).truncationMode(.middle)
                } label: {
                    Text("settings_pubky_label")
                }
            }
            .tint(LoopkyColor.foregroundPrimary)
            Button(action: onCopyHomeserver) {
                LabeledContent {
                    Text(state.homeserver.isEmpty
                         ? NSLocalizedString("settings_homeserver_unknown", comment: "")
                         : state.homeserver)
                    .font(.system(size: 13)).lineLimit(1).truncationMode(.middle)
                } label: {
                    Text("settings_homeserver_label")
                }
            }
            .tint(LoopkyColor.foregroundPrimary)
        } header: {
            Text("settings_section_identity")
        }
    }

    /// The daily goal is **announced, never enforced** — the queue serves every due card and every
    /// new one regardless. The description says so; wording it as a limit would describe a feature
    /// Loopky does not have.
    private var studyingSection: some View {
        Section {
            Stepper(value: Binding(get: { state.newCardsGoal }, set: onGoalChanged), in: 1...100) {
                LabeledContent("settings_new_cards_goal_label", value: "\(state.newCardsGoal)")
            }
            intervalRow("settings_interval_hard_label", grade: .hard, days: state.hardDays)
            intervalRow("settings_interval_good_label", grade: .good, days: state.goodDays)
            intervalRow("settings_interval_easy_label", grade: .easy, days: state.easyDays)
        } header: {
            Text("settings_section_studying")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("settings_new_cards_goal_description")
                Text("settings_interval_description")
                Text("settings_interval_mastery_note")
                if !state.canEditStudySettings {
                    // The repository refuses a write before the record has been read, so that a
                    // save cannot put defaults over what the user really had.
                    Text("settings_study_unavailable").foregroundStyle(LoopkyColor.danger)
                }
            }
        }
        .disabled(!state.canEditStudySettings)
    }

    private func intervalRow(_ label: LocalizedStringKey, grade: StudyGrade, days: Int) -> some View {
        Stepper(
            value: Binding(get: { days }, set: { onIntervalChanged(grade, $0) }),
            in: 1...365
        ) {
            LabeledContent(label, value: "\(days)d")
        }
    }

    private var sharingSection: some View {
        Section {
            Toggle(isOn: Binding(get: { state.shareOnPubky }, set: onShareOnPubkyChanged)) {
                Text("settings_share_on_pubky_label")
            }
            .tint(LoopkyColor.accentPrimary)
        } header: {
            Text("settings_section_sharing")
        } footer: {
            Text("settings_share_on_pubky_description")
        }
    }

    private var imageSearchSection: some View {
        Section {
            LabeledContent("settings_unsplash_key_label", value: unsplashStatusText)
            if state.unsplashKeyStatus == .userSet {
                Button("settings_unsplash_key_remove", role: .destructive, action: onRemoveUnsplashKey)
            } else {
                SecureField("settings_unsplash_key_placeholder", text: $unsplashKey)
                Button("settings_unsplash_key_save") {
                    onSaveUnsplashKey(unsplashKey)
                    unsplashKey = ""
                }
                .disabled(unsplashKey.isEmpty || state.isVerifyingUnsplashKey)
            }
            Button("settings_unsplash_key_get") { onOpenUrl("https://unsplash.com/developers") }
        } header: {
            Text("settings_section_image_search")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("settings_unsplash_key_hint")
                if let error = state.unsplashKeyError {
                    Text(error).foregroundStyle(LoopkyColor.danger)
                }
            }
        }
    }

    private var unsplashStatusText: String {
        switch state.unsplashKeyStatus {
        case .userSet: return NSLocalizedString("settings_unsplash_key_label", comment: "")
        case .builtIn: return NSLocalizedString("settings_unsplash_key_built_in", comment: "")
        case .notSet: return NSLocalizedString("settings_unsplash_key_not_set", comment: "")
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("settings_app_version_label", value: state.appVersion)
            Button("settings_privacy_policy") { onOpenUrl(Self.privacyPolicyUrl) }
        } header: {
            Text("settings_section_about")
        }
    }

    private var dangerSection: some View {
        Section {
            Button("settings_sign_out", role: .destructive) { isConfirmingSignOut = true }
            Button("settings_delete_account", role: .destructive, action: onDeleteAccount)
        }
    }
}
