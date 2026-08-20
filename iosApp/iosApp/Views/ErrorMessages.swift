import Foundation
import Shared

/// Maps the shared `ErrorReason` to user-facing copy, mirroring the Android
/// `ErrorMessages.kt`.
///
/// ViewModels deliberately carry no message text: the Pubky FFI's diagnostic string
/// (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`) used to be
/// rendered to users verbatim.
///
/// **Both switches end in `default:`, so adding an `ErrorReason` case compiles here without a
/// warning and silently renders the generic copy.** Android's `when` is exhaustive and will fail
/// the build; this file will not. Add the case here in the same change.
enum ErrorCopy {

    static func title(for reason: ErrorReason) -> String {
        switch reason {
        case .offline:
            return NSLocalizedString("You're offline", comment: "Error title: no connectivity")
        case .sessionExpired:
            return NSLocalizedString("Session expired", comment: "Error title: needs re-auth")
        case .notFound:
            return NSLocalizedString("Not found", comment: "Error title: missing record")
        case .noHomeserverAccount:
            return NSLocalizedString(
                "Your Pubky isn't set up yet",
                comment: "Error title: pubky has no homeserver account"
            )
        case .notSignedIn:
            return NSLocalizedString("Not signed in", comment: "Error title: no session")
        case .ringNotInstalled:
            return NSLocalizedString("Pubky Ring not found", comment: "Error title: Ring missing")
        case .authFailed:
            return NSLocalizedString("Sign-in didn't finish", comment: "Error title: auth failed")
        case .authRelayUnreachable:
            return NSLocalizedString(
                "Sign-in couldn't reach Pubky",
                comment: "Error title: auth relay unreachable"
            )
        case .storageFull:
            return NSLocalizedString(
                "Your Pubky storage is full",
                comment: "Error title: homeserver storage quota exceeded"
            )
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
                "Loopky can't reach your homeserver. Your decks are safe — they live on Pubky. "
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
        case .noHomeserverAccount:
            // Deliberately not "sign in again": there is nothing to sign in to. Saying so is the
            // whole point of this case — it used to fall through to the deck-deleted copy.
            return NSLocalizedString(
                "This Pubky doesn't have a homeserver account yet, so there's nowhere to keep "
                    + "your decks. Finish setting it up in Pubky Ring, then sign in again.",
                comment: "Error message: pubky has no homeserver account"
            )
        case .notSignedIn:
            return NSLocalizedString(
                "Sign in with Pubky Ring to continue.",
                comment: "Error message: no session"
            )
        case .ringNotInstalled:
            return NSLocalizedString(
                "Loopky signs you in through Pubky Ring. Install it to continue.",
                comment: "Error message: Ring missing"
            )
        case .authFailed:
            return NSLocalizedString(
                "Loopky couldn't confirm the authorisation with Pubky Ring. "
                    + "Check your connection and try again.",
                comment: "Error message: auth failed"
            )
        case .authRelayUnreachable:
            // Not "you're offline": the relay is its own host, and the homeserver is usually
            // reachable while it is not.
            return NSLocalizedString(
                "Loopky signs you in through Pubky's authorisation relay, and it isn't "
                    + "responding. Try again in a moment.",
                comment: "Error message: auth relay unreachable"
            )
        case .storageFull:
            // Not "please try again" — a retry against a full quota is the one thing that cannot
            // work, so the copy has to name the two things that can: delete something, or buy room.
            return NSLocalizedString(
                "There's no room left on your homeserver, so this couldn't be saved. "
                    + "Delete a deck you no longer study to free up space, or upgrade your "
                    + "Pubky plan for more.",
                comment: "Error message: homeserver storage quota exceeded"
            )
        default:
            return NSLocalizedString(
                "Something went wrong. Please try again.",
                comment: "Error message: generic"
            )
        }
    }
}
