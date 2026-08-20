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
    override fun isInstalled(): Boolean {
        val url = NSURL(string = PUBKY_RING_PROBE_URL)
        return UIApplication.sharedApplication.canOpenURL(url)
    }

    private companion object {
        /** Product landing page — forwards to the App Store. */
        const val DEFAULT_INSTALL_URL = "https://pubkyring.app"
    }
}
