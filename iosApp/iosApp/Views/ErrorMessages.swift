import Foundation
import Shared

/// Native mirror of the shared `ErrorReason`.
///
/// The bridged `ErrorReason` is a Kotlin enum, which crosses to Swift as a *class* whose entries
/// are class properties (`ErrorReason.sessionexpired` — lowercased, no separators). Switching on
/// it can therefore never be exhaustive, which is exactly the hazard this file used to carry: a
/// new reason compiled without a warning and silently rendered the generic copy, while Android's
/// `when` failed the build.
///
/// Mirroring it as a real Swift enum moves that guarantee back. `title(for:)` and `message(for:)`
/// switch over `LoopkyErrorReason` with **no `default`**, so adding a case here without giving it
/// copy is a compile error. `init(_:)` below is the single bridge point, and the one place a new
/// shared reason has to be mapped — the debug assertion catches forgetting it.
enum LoopkyErrorReason: CaseIterable {
    case offline
    case sessionExpired
    case notFound
    case noHomeserverAccount
    case notSignedIn
    case ringNotInstalled
    case authFailed
    case authRelayUnreachable
    case serverBusy
    case storageFull
    case homeserverLookupFailed
    case unknown

    /// The only place the bridged singletons are matched. Kotlin enum entries are singletons, so
    /// identity comparison is well defined.
    init(_ reason: ErrorReason) {
        switch reason {
        case ErrorReason.offline: self = .offline
        case ErrorReason.sessionexpired: self = .sessionExpired
        case ErrorReason.notfound: self = .notFound
        case ErrorReason.nohomeserveraccount: self = .noHomeserverAccount
        case ErrorReason.notsignedin: self = .notSignedIn
        case ErrorReason.ringnotinstalled: self = .ringNotInstalled
        case ErrorReason.authfailed: self = .authFailed
        case ErrorReason.authrelayunreachable: self = .authRelayUnreachable
        case ErrorReason.serverbusy: self = .serverBusy
        case ErrorReason.storagefull: self = .storageFull
        case ErrorReason.homeserverlookupfailed: self = .homeserverLookupFailed
        default:
            // A reason added on the Kotlin side and not mapped above lands here. It renders the
            // generic copy rather than crashing a user, but trips in debug so it is caught.
            assertionFailure("Unmapped ErrorReason: \(reason.name). Add it to LoopkyErrorReason.")
            self = .unknown
        }
    }
}

/// Maps the shared `ErrorReason` to user-facing copy, mirroring the Android `ErrorMessages.kt`.
///
/// ViewModels deliberately carry no message text: the Pubky FFI's diagnostic string
/// (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`) used to be
/// rendered to users verbatim.
enum ErrorCopy {

    static func title(for reason: ErrorReason) -> String {
        title(for: LoopkyErrorReason(reason))
    }

    static func message(for reason: ErrorReason) -> String {
        message(for: LoopkyErrorReason(reason))
    }

    static func title(for reason: LoopkyErrorReason) -> String {
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
        case .unknown:
            return NSLocalizedString("Something went wrong", comment: "Error title: generic")
        }
    }

    static func message(for reason: LoopkyErrorReason) -> String {
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
        case .unknown:
            return NSLocalizedString(
                "Something went wrong. Please try again.",
                comment: "Error message: generic"
            )
        }
    }
}

/// Copy for the shared `FormError`s, in one place so the publish flow, the deck editor and the
/// card editor cannot drift into wording the same validation differently.
///
/// Like `ErrorReason`, `FormError` is a Kotlin enum whose entries export lowercased with no
/// separators, so this is the only file that has to know that.
enum FormErrorCopy {
    static func message(for error: FormError?) -> String? {
        switch error {
        case FormError.titlerequired:
            return NSLocalizedString("form_error_title_required", comment: "")
        case FormError.titletoolong:
            return NSLocalizedString("form_error_title_too_long", comment: "")
        case FormError.descriptiontoolong:
            return NSLocalizedString("form_error_description_too_long", comment: "")
        case FormError.cardsiderequired:
            return NSLocalizedString("form_error_card_side_required", comment: "")
        case FormError.cardtexttoolong:
            return NSLocalizedString("form_error_card_text_too_long", comment: "")
        case FormError.languagesrequired:
            return NSLocalizedString("deck_languages_required", comment: "")
        default:
            return nil
        }
    }
}

/// Native mirror of the shared `SignupError`, for the same reason `LoopkyErrorReason` mirrors
/// `ErrorReason`: a Kotlin enum crosses as a class whose entries are lowercased class properties,
/// so switching on it can never be exhaustive. Mirroring it moves that guarantee back — the two
/// switches below have no `default`, so adding a case without copy is a compile error.
enum LoopkySignupError: CaseIterable {
    case geoblocked
    case phoneBlocked
    case rateLimited
    case rateLimitedWeekly
    case rateLimitedYearly
    case codeIncorrect
    case invoiceExpired
    case verificationLost
    case unavailable
    case tokenRejected

    /// The only place the bridged singletons are matched.
    init?(_ error: SignupError?) {
        switch error {
        case SignupError.geoblocked: self = .geoblocked
        case SignupError.phoneblocked: self = .phoneBlocked
        case SignupError.ratelimited: self = .rateLimited
        case SignupError.ratelimitedweekly: self = .rateLimitedWeekly
        case SignupError.ratelimitedyearly: self = .rateLimitedYearly
        case SignupError.codeincorrect: self = .codeIncorrect
        case SignupError.invoiceexpired: self = .invoiceExpired
        case SignupError.verificationlost: self = .verificationLost
        case SignupError.unavailable: self = .unavailable
        case SignupError.tokenrejected: self = .tokenRejected
        case nil: return nil
        default:
            assertionFailure("Unmapped SignupError. Add it to LoopkySignupError.")
            self = .unavailable
        }
    }

    /// `TokenRejected` is terminal by design — it is the one signup error that must never offer
    /// "try again", which is why `LocalSignupUiState.canRetry` excludes it.
    var isTerminal: Bool { self == .tokenRejected }
}

enum SignupErrorCopy {

    static func title(for error: SignupError?) -> String? {
        LoopkySignupError(error).map(title(for:))
    }

    static func message(for error: SignupError?) -> String? {
        LoopkySignupError(error).map(message(for:))
    }

    static func title(for error: LoopkySignupError) -> String {
        NSLocalizedString("signup_error_\(error.key)_title", comment: "Signup error title")
    }

    static func message(for error: LoopkySignupError) -> String {
        NSLocalizedString("signup_error_\(error.key)_message", comment: "Signup error message")
    }
}

private extension LoopkySignupError {
    /// The snake_case fragment the string keys are built from.
    var key: String {
        switch self {
        case .geoblocked: return "geoblocked"
        case .phoneBlocked: return "phone_blocked"
        case .rateLimited: return "rate_limited"
        case .rateLimitedWeekly: return "rate_limited_weekly"
        case .rateLimitedYearly: return "rate_limited_yearly"
        case .codeIncorrect: return "code_incorrect"
        case .invoiceExpired: return "invoice_expired"
        case .verificationLost: return "verification_lost"
        case .unavailable: return "unavailable"
        case .tokenRejected: return "token_rejected"
        }
    }
}
