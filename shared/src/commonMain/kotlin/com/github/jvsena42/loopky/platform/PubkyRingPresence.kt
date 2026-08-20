package com.github.jvsena42.loopky.platform

/**
 * Whether Pubky Ring is installed, and where to get it if it is not.
 *
 * Platform-provided via Koin (like [Speaker]) rather than `expect`/`actual`, because answering it
 * needs platform context — Android's `PackageManager`, iOS's `UIApplication.canOpenURL` — and
 * because the *install* address differs per platform too (a Play Store listing vs. a web page),
 * which is exactly the knowledge the same object already has.
 *
 * Signup consults this **before the first Homegate call**: a signup token is single-use, costs an
 * SMS attempt or real sats, and only Ring can spend it. Minting one on a device that cannot
 * redeem it charges the user for something they cannot use yet.
 */
interface PubkyRingPresence {
    /** True when a `pubkyauth://` deeplink has somewhere to go. */
    fun isInstalled(): Boolean

    /** Where to send a user who does not have it. */
    val installUrl: String
}

/**
 * The URL the presence check probes with. Shared by both platforms so the scheme cannot drift
 * from the one the auth deeplinks actually use (`PubkyAuthUrls`) — a probe on a scheme nobody
 * handles reports Ring missing on every device.
 */
internal const val PUBKY_RING_PROBE_URL = "pubkyauth://signin"
