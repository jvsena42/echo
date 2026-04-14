package com.github.jvsena42.eco.data.pubky

internal object PubkyPaths {
    const val APP_NAMESPACE = "pub/echo"

    fun deckRoot(authorPubky: String, deckId: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/$deckId"

    fun manifest(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/manifest.json"

    fun card(authorPubky: String, deckId: String, cardId: String): String =
        "${deckRoot(authorPubky, deckId)}/cards/$cardId.json"

    fun media(authorPubky: String, deckId: String, sha256: String, ext: String): String =
        "${deckRoot(authorPubky, deckId)}/media/$sha256.$ext"

    fun decksList(authorPubky: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/"

    /** Relative `media/<sha>.<ext>` reference stored inside card/manifest records. */
    fun relativeMedia(sha256: String, ext: String): String = "media/$sha256.$ext"
}
