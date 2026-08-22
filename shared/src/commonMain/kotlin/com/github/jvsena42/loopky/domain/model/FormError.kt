package com.github.jvsena42.loopky.domain.model

/**
 * Field-level validation failures. Like [ErrorReason] these are carried instead of message
 * text so `commonMain` holds no user-facing strings and each platform localises its own.
 */
enum class FormError {
    TitleRequired,
    TitleTooLong,
    DescriptionTooLong,

    /**
     * A card side with neither text nor a picture. The repository refuses such a card outright,
     * so without this the `require` fired and its message — which names the card by its internal
     * id — was rendered to the user verbatim.
     */
    CardSideRequired,
    CardTextTooLong,

    /**
     * Listen or Speak is on but the deck has not said what language each side is in. Required
     * rather than defaulted: the OS engines fall back to the *reader's* locale, so a guess here
     * mispronounces the card on every device but the author's. See [Deck.speechReady].
     */
    LanguagesRequired,
}
