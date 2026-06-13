package com.github.jvsena42.echo.data.pubky

internal object PubkyPaths {
    const val APP_NAMESPACE = "pub/echo"
    private const val PUBKY_APP_NAMESPACE = "pub/pubky.app"

    fun profile(pubky: String): String =
        "pubky://$pubky/$PUBKY_APP_NAMESPACE/profile.json"

    fun deckRoot(authorPubky: String, deckId: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/$deckId"

    fun manifest(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/manifest.json"

    fun card(authorPubky: String, deckId: String, cardId: String): String =
        "${deckRoot(authorPubky, deckId)}/cards/$cardId.json"

    fun media(authorPubky: String, deckId: String, sha256: String, ext: String): String =
        "${deckRoot(authorPubky, deckId)}/media/$sha256.$ext"

    /** Per-card SRS review state, deck-scoped to mirror [card] and avoid cross-deck id collisions. */
    fun srs(authorPubky: String, deckId: String, cardId: String): String =
        "${deckRoot(authorPubky, deckId)}/srs/$cardId.json"

    fun srsRoot(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/srs/"

    fun decksList(authorPubky: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/"

    /**
     * Social follows use the pubky.app native primitive: a record's *existence* under the owner's
     * `follows/` directory means "owner follows followee". Stored on the follower's homeserver so
     * listing your own follows is a single [PubkyClient.list] on [followsRoot].
     */
    fun followsRoot(ownerPubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/follows/"

    fun follow(ownerPubky: String, followeePubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/follows/$followeePubky"

    /**
     * pubky.app tag record. Like follows, tags use the ecosystem-native primitive so Nexus
     * indexes them; the id is content-derived per pubky-app-specs (`PubkyClient.createTagId`).
     */
    fun tag(ownerPubky: String, tagId: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/tags/$tagId"

    /** Relative `media/<sha>.<ext>` reference stored inside card/manifest records. */
    fun relativeMedia(sha256: String, ext: String): String = "media/$sha256.$ext"
}
