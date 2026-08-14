import Foundation
import Shared

/// Maps the shared `ErrorReason` to user-facing copy, mirroring the Android
/// `ErrorMessages.kt`.
///
/// ViewModels deliberately carry no message text: the Pubky FFI's diagnostic string
/// (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`) used to be
/// rendered to users verbatim.
enum ErrorCopy {

    static func title(for reason: ErrorReason) -> String {
        switch reason {
        case .offline:
            return NSLocalizedString("You're offline", comment: "Error title: no connectivity")
        case .sessionExpired:
            return NSLocalizedString("Session expired", comment: "Error title: needs re-auth")
        case .notFound:
            return NSLocalizedString("Not found", comment: "Error title: missing record")
        case .notSignedIn:
            return NSLocalizedString("Not signed in", comment: "Error title: no session")
        case .ringNotInstalled:
            return NSLocalizedString("Pubky Ring not found", comment: "Error title: Ring missing")
        case .authFailed:
            return NSLocalizedString("Sign-in didn't finish", comment: "Error title: auth failed")
        default:
            return NSLocalizedString("Something went wrong", comment: "Error title: generic")
        }
    }

    static func message(for reason: ErrorReason) -> String {
        switch reason {
        case .offline:
            // Pubky is the only source of truth and there is no local cache, so this is exactly
            // the moment a user would fear they had lost everything.
            return NSLocalizedString(
                "Echo can't reach your homeserver. Your decks are safe — they live on Pubky. "
                    + "Check your connection and try again.",
                comment: "Error message: no connectivity"
            )
        case .sessionExpired:
            return NSLocalizedString(
                "Sign in with Pubky Ring again to get back to your decks.",
                comment: "Error message: needs re-auth"
            )
        case .notFound:
            return NSLocalizedString(
                "This deck no longer exists. It may have been deleted.",
                comment: "Error message: missing record"
            )
        case .notSignedIn:
            return NSLocalizedString(
                "Sign in with Pubky Ring to continue.",
                comment: "Error message: no session"
            )
        case .ringNotInstalled:
            return NSLocalizedString(
                "Echo signs you in through Pubky Ring. Install it to continue.",
                comment: "Error message: Ring missing"
            )
        case .authFailed:
            return NSLocalizedString(
                "Echo couldn't confirm the authorisation with Pubky Ring. "
                    + "Check your connection and try again.",
                comment: "Error message: auth failed"
            )
        default:
            return NSLocalizedString(
                "Something went wrong. Please try again.",
                comment: "Error message: generic"
            )
        }
    }
}
