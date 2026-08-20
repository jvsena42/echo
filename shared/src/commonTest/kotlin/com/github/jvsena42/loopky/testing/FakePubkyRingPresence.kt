package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.platform.PubkyRingPresence

/** Ring is present by default — the common case, so tests only say so when it matters. */
class FakePubkyRingPresence(
    var installed: Boolean = true,
    override var installUrl: String = "https://pubkyring.app",
) : PubkyRingPresence {
    override fun isInstalled(): Boolean = installed
}
