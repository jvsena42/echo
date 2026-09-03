package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.domain.model.ErrorReason

/**
 * What `loopky` exits with, and what an agent is supposed to do about it.
 *
 * The table is part of the contract, not an implementation detail: an agent branches on these
 * before it looks at anything else, so a code's meaning may be added to but never repurposed.
 *
 * [SessionExpired] has a code of its own, and that is the whole reason this enum is not three
 * values long. Loopky's homeserver session dies after roughly an hour and nothing renews it —
 * writes start failing while reads keep working (#165), which from the outside is
 * indistinguishable from a network wobble. An agent that cannot tell the two apart either retries
 * a dead session forever or gives up on a live network. Told which it is, it can stop, say so, and
 * resume after a human runs `loopky login` — which is what makes `--resume` worth having.
 *
 * [name] is what `--json` reports, so a caller can branch on a word rather than a number.
 */
enum class ExitCode(val code: Int, val json: String) {
    /** The command did what it was asked. */
    Ok(0, "ok"),

    /** A failure this table has no better word for. Worth reporting as a bug. */
    Internal(1, "internal"),

    /** The command line was wrong: unknown command, missing argument, bad flag. */
    Usage(2, "usage"),

    /** No session at all. Run `loopky login`, or set `LOOPKY_SESSION`. */
    NotSignedIn(3, "not_signed_in"),

    /** There was a session and the homeserver has stopped honouring it. See the class note. */
    SessionExpired(4, "session_expired"),

    /** The homeserver, the relay or the indexer could not be reached. Retryable as-is. */
    Network(5, "network"),

    /** The deck, card or record asked for does not exist. */
    NotFound(6, "not_found"),

    /**
     * The homeserver refused the write because the account is out of quota (507).
     *
     * Terminal, never retried: re-hosting and compaction both *consume* quota, so nothing the
     * client can do digs it out, and a backoff chain against a full disk never converges.
     */
    StorageFull(7, "storage_full"),

    /**
     * The session and the requested environment disagree.
     *
     * Its own code because the alternative is silent and worse: Nexus answers a query aimed at the
     * wrong network **successfully, with an empty result**, so an agent that writes a tag, reads it
     * back and sees `[]` concludes the write failed and retries. Caught at startup instead.
     */
    EnvironmentMismatch(8, "environment_mismatch"),

    /** An input file could not be read, or held nothing importable. */
    BadInput(9, "bad_input"),

    /**
     * This machine is not one `libpubkycore` is built for. See [SupportedHost].
     *
     * Its own code because the alternative is not a vague code but a **wrong** one. An unshipped
     * host misses at `Native.load`, which throws `UnsatisfiedLinkError("Unable to load library
     * 'pubkycore': … not found in resource path …")` — and "not found" is what [of] matches on, so
     * the machine that can never run this binary reports [NotFound]: *the deck does not exist*.
     * `SupportedHostTest` pins that, because it is the reason this row exists.
     */
    UnsupportedHost(10, "unsupported_host"),

    /**
     * `loopky update` found a newer release and may not install it here (#209).
     *
     * Its own code rather than 0 or 1. Zero would tell an agent that asked for an update that it
     * has one, which is the single most expensive thing this command could get wrong — the whole
     * reason the check exists is that a stale client writes an old shape while believing it is
     * current. [Internal] would be wrong in the other direction: a Homebrew install, a
     * `dpkg`-owned file, a container layer and a read-only directory are all correct states of the
     * world, and the command's answer in each is a different, correct instruction rather than a
     * bug. Nothing was downloaded and nothing was replaced.
     */
    UpdateUnsupported(11, "update_unsupported"),
    ;

    companion object {
        /**
         * The exit code for a failure that came back from the shared layer.
         *
         * Goes through [toErrorReason] rather than matching on messages here, so the CLI and the
         * two apps classify the same failure the same way — the classifier already knows that "no
         * homeserver record" and "the DHT did not answer" arrive through one call and are not the
         * same thing.
         */
        fun of(error: Throwable): ExitCode = when (error.toErrorReason()) {
            ErrorReason.NotSignedIn -> NotSignedIn
            ErrorReason.SessionExpired, ErrorReason.SessionUnreachable -> SessionExpired
            ErrorReason.Offline,
            ErrorReason.AuthRelayUnreachable,
            ErrorReason.ServerBusy,
            ErrorReason.HomeserverLookupFailed,
            -> Network

            ErrorReason.NotFound, ErrorReason.NoHomeserverAccount -> NotFound
            ErrorReason.StorageFull -> StorageFull
            ErrorReason.RingNotInstalled, ErrorReason.AuthFailed -> Internal
            ErrorReason.Unknown -> Internal
        }
    }
}

/** A failure that already knows what the process should exit with. */
class CliError(val exitCode: ExitCode, message: String) : RuntimeException(message)
