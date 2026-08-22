package com.github.jvsena42.loopky.domain.model

/**
 * Length caps on the free-text fields of a deck manifest.
 *
 * Shared rather than per-ViewModel because both editors (deck editor, publish) validate the same
 * record, and the UI shows the same counter next to each field.
 */
object DeckLimits {
    const val TITLE_MAX_LENGTH = 120
    const val DESCRIPTION_MAX_LENGTH = 500
}
