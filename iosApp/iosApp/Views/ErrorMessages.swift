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
        case .serverBusy:
            return NSLocalizedString(
                "Your homeserver is busy",
                comment: "Error title: homeserver rate-limiting"
            )
        case .storageFull:
            return NSLocalizedString(
                "Your Pubky storage is full",
                comment: "Error title: homeserver storage quota exceeded"
            )
        case .homeserverLookupFailed:
            return NSLocalizedString(
                "We couldn't check that",
                comment: "Error title: pkarr/DHT lookup did not answer"
            )
        default:
            return NSLocalizedString("Something went wrong", comment: "Error title: generic")
        }
    }

    static func message(for reason: ErrorReason) -> String {
        switch reason {
        case .offline:
            // The reassurance stays, short: Pubky is the only source of truth and there is no
            // local cache, so this is exactly the moment a user would fear they had lost
            // everything.
            return NSLocalizedString(
                "Check your connection and try again. Your decks are safe on Pubky.",
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
        case .serverBusy:
            // Not "you're offline": the homeserver answered, so the connection is fine and
            // sending the user to check it points them at something that is not broken.
            return NSLocalizedString(
                "Your homeserver is rate-limiting Loopky, so this couldn't be finished. "
                    + "Nothing is wrong with your connection — wait a moment and try again.",
                comment: "Error message: homeserver rate-limiting"
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
        case .homeserverLookupFailed:
            // Never a verdict. This is the lookup that decides whether a recovery phrase belongs
            // to an account, and it failing means we do not know — saying anything about the
            // phrase here would be a guess presented as an answer.
            return NSLocalizedString(
                "We couldn't reach the Pubky network to look this up, so we don't know yet. "
                    + "This often means a network that blocks peer-to-peer traffic. Try again, "
                    + "or switch to a different Wi-Fi or mobile data.",
                comment: "Error message: pkarr/DHT lookup did not answer"
            )
        default:
            return NSLocalizedString(
                "Something went wrong. Please try again.",
                comment: "Error message: generic"
            )
        }
    }
}
