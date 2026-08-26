package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.platform.PubkyRingPresence

/** Ring is present by default — the common case, so tests only say so when it matters. */
class FakePubkyRingPresence(
    var installed: Boolean = true,
    /**
     * Defaults to tracking [installed]. Settable apart from it because the two probe different
     * schemes and a device really can answer yes to one and no to the other.
     */
    var canImport: Boolean? = null,
    override var installUrl: String = "https://pubkyring.app",
) : PubkyRingPresence {
    override fun isInstalled(): Boolean = installed
    override fun canImportKey(): Boolean = canImport ?: installed
}
