package com.github.jvsena42.loopky.data.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSUserDefaults

/** iOS [AppPreferences] over `NSUserDefaults`, namespaced by [PREFERENCES_NAME]. */
class IosAppPreferences : AppPreferences {
    private val defaults = NSUserDefaults(suiteName = PREFERENCES_NAME)

    // `boolForKey` cannot distinguish "absent" from "false", and the default here is *true* —
    // so absence is probed with objectForKey first, or a fresh install would start opted out.
    private val _shareOnPubky = MutableStateFlow(
        if (defaults.objectForKey(KEY_SHARE_ON_PUBKY) == null) {
            DEFAULT_SHARE_ON_PUBKY
        } else {
            defaults.boolForKey(KEY_SHARE_ON_PUBKY)
        },
    )
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    override suspend fun setShareOnPubky(enabled: Boolean) {
        defaults.setBool(enabled, KEY_SHARE_ON_PUBKY)
        _shareOnPubky.update { enabled }
    }
}
