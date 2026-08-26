package com.github.jvsena42.loopky.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Asks UIKit whether anything handles the `pubkyauth://` scheme.
 *
 * Requires `pubkyauth` in the app's `LSApplicationQueriesSchemes`: without it iOS answers false
 * for every scheme it was not told about, which would report Ring missing on every device.
 *
 * `sharedApplication` is main-thread-only, and the caller is a ViewModel on `Dispatchers.Main`.
 */
class IosPubkyRingPresence(
    override val installUrl: String = DEFAULT_INSTALL_URL,
) : PubkyRingPresence {
    override fun isInstalled(): Boolean = canOpen(PUBKY_RING_PROBE_URL)

    // `pubkyring` is already in Info.plist's LSApplicationQueriesSchemes alongside `pubkyauth`,
    // so this probe answers honestly without a plist change.
    override fun canImportKey(): Boolean = canOpen(PUBKY_RING_IMPORT_PROBE_URL)

    private fun canOpen(scheme: String): Boolean =
        UIApplication.sharedApplication.canOpenURL(NSURL(string = scheme))

    private companion object {
        /** Product landing page — forwards to the App Store. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}
