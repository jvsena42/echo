package com.github.jvsena42.loopky.domain.model

/**
 * Which palette the app paints itself in.
 *
 * A **device** preference rather than a synced one: it describes this screen in this room, and a
 * phone read in bed has no business dimming the tablet on the desk. It lives in
 * `AppPreferences` for that reason and not in `/pub/loopky/settings.json`.
 *
 * PascalCase entries, like every enum that crosses to Swift here — Kotlin exports them lowercased
 * with separators dropped, so a SCREAMING_SNAKE entry arrives under a name nothing can predict.
 */
enum class AppTheme {
    /** Follow the device. The default, and the only value that stays right when the device flips at sunset. */
    System,
    Light,
    Dark,
    ;

    companion object {
        /**
         * [name] as an [AppTheme], falling back to [System] — which is also what a value written
         * by a newer release resolves to, rather than an unreadable preference crashing start-up.
         */
        fun fromNameOrSystem(name: String): AppTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: System
    }
}
