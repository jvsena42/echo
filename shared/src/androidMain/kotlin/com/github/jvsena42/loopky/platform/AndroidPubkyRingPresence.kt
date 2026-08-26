package com.github.jvsena42.loopky.platform

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Resolves the `pubkyauth://` deeplink against the package manager.
 *
 * Requires the `<queries>` entry for the `pubkyauth` scheme in the app manifest: without it
 * Android 11+ package visibility makes `resolveActivity` answer null even when Ring is installed,
 * which would lock every user out of signup.
 */
class AndroidPubkyRingPresence(
    private val context: Context,
    override val installUrl: String,
) : PubkyRingPresence {
    override fun canImportKey(): Boolean = canOpen(PUBKY_RING_IMPORT_PROBE_URL)

    override fun isInstalled(): Boolean = canOpen(PUBKY_RING_PROBE_URL)

    private fun canOpen(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        return intent.resolveActivity(context.packageManager) != null
    }
}
