package com.github.jvsena42.loopky.data.pubky

internal object PubkyPaths {
    const val APP_NAMESPACE = "pub/loopky"
    private const val PUBKY_APP_NAMESPACE = "pub/pubky.app"

    fun profile(pubky: String): String =
        "pubky://$pubky/$PUBKY_APP_NAMESPACE/profile.json"

    fun deckRoot(authorPubky: String, deckId: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/$deckId"

    fun manifest(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/manifest.json"

    /**
     * One chunk of up to `CHUNK_SIZE` card records. Cards used to get a record each, which made a
     * 20k-card deck 20k requests to open and forced an unbounded card index into the manifest.
     */
    fun cardChunk(authorPubky: String, deckId: String, chunk: Int): String =
        "${deckRoot(authorPubky, deckId)}/cards/$chunk.json"

    fun cardsRoot(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/cards/"

    fun media(authorPubky: String, deckId: String, sha256: String, ext: String): String =
        "${deckRoot(authorPubky, deckId)}/media/$sha256.$ext"

    /**
     * A chunk of SRS review state, on **your** homeserver ([ownerPubky]) but keyed by the deck's
     * [authorPubky].
     *
     * Two things changed here (#43 §2). It is no longer nested under `/decks/{deckId}/`, because
     * your review state for someone else's deck was never the owner's data — that only looked
     * right while every deck was your own. And it is keyed by the deck's author, so two authors
     * whose decks happen to share a `deckId` no longer collide in your `srs/` tree. Following
     * decks from many authors is exactly what raises those odds.
     *
     * Chunked for the same reason cards are: one record per card made a studied 20k-card deck
     * ~40,000 records, and `dueForDeck` 20,000 GETs.
     */
    fun srsChunk(ownerPubky: String, authorPubky: String, deckId: String, chunk: Int): String =
        "${srsRoot(ownerPubky, authorPubky, deckId)}$chunk.json"

    fun srsRoot(ownerPubky: String, authorPubky: String, deckId: String): String =
        "pubky://$ownerPubky/$APP_NAMESPACE/srs/$authorPubky/$deckId/"

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
