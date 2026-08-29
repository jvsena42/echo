import SwiftUI
import Shared

/// Why a restore did not sign you in.
///
/// Six outcomes, and they exist as six **because collapsing them is the bug**: "we could not check"
/// is not "that phrase is wrong", and telling someone their perfectly good phrase is invalid
/// because a DHT lookup timed out sends them hunting for a mistake they did not make.
struct RestoreOutcomeBlock: View {
    var outcome: RestoreOutcome

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LoopkyColor.danger)
            Text(verbatim: message)
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)

            // Only for NoAccount: the pubky is the one thing that makes "no account" actionable —
            // it is what the user checks against Ring, or hands to support.
            if let noAccount = outcome as? RestoreOutcomeNoAccount {
                Spacer().frame(height: 8)
                Text("restore_error_no_account_pubky")
                    .font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(verbatim: noAccount.pubky)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var title: String {
        switch outcome {
        case is RestoreOutcomeInvalidPhrase:
            return NSLocalizedString("restore_error_invalid_title", comment: "")
        case is RestoreOutcomeNoAccount:
            return NSLocalizedString("restore_error_no_account_title", comment: "")
        case is RestoreOutcomeWrongPassphrase:
            return NSLocalizedString("restore_error_passphrase_title", comment: "")
        case is RestoreOutcomeFileUnreadable:
            return NSLocalizedString("restore_error_unreadable_title", comment: "")
        case let couldNot as RestoreOutcomeCouldNotCheck:
            return ErrorCopy.title(for: couldNot.reason)
        case let failed as RestoreOutcomeSignInFailed:
            return ErrorCopy.title(for: failed.reason)
        default:
            return NSLocalizedString("restore_error_invalid_title", comment: "")
        }
    }

    private var message: String {
        switch outcome {
        case is RestoreOutcomeInvalidPhrase:
            return NSLocalizedString("restore_error_invalid_message", comment: "")
        case is RestoreOutcomeNoAccount:
            return NSLocalizedString("restore_error_no_account_message", comment: "")
        case is RestoreOutcomeWrongPassphrase:
            return NSLocalizedString("restore_error_passphrase_message", comment: "")
        case is RestoreOutcomeFileUnreadable:
            return NSLocalizedString("restore_error_unreadable_message", comment: "")
        case let couldNot as RestoreOutcomeCouldNotCheck:
            return ErrorCopy.message(for: couldNot.reason)
        case let failed as RestoreOutcomeSignInFailed:
            return ErrorCopy.message(for: failed.reason)
        default:
            return NSLocalizedString("restore_error_invalid_message", comment: "")
        }
    }
}
