package com.github.jvsena42.loopky.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Android [AppPreferences] over plain `SharedPreferences` — no encryption, none needed. */
class AndroidAppPreferences(context: Context) : AppPreferences {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Mirrored into a StateFlow rather than read through callbackFlow: the file is read once at
    // construction and only this class writes it, so the flow is the cheaper source of truth and
    // a collector gets the current value without touching disk.
    private val _shareOnPubky = MutableStateFlow(
        prefs.getBoolean(KEY_SHARE_ON_PUBKY, DEFAULT_SHARE_ON_PUBKY),
    )
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    override suspend fun setShareOnPubky(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_SHARE_ON_PUBKY, enabled).apply()
        }
        _shareOnPubky.update { enabled }
    }
}
