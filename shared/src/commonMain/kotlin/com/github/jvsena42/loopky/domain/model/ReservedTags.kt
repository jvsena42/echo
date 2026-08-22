package com.github.jvsena42.loopky.domain.model

/**
 * Labels Loopky writes on its own behalf. With no backend, tag records are the only thing Loopky
 * publishes that a network-wide index can answer questions about, so these four labels *are* the
 * app's global index: they make users and decks findable by strangers with no follow relationship
 * (issue #40).
 *
 * They are a reserved namespace, not user content: [isReserved] labels are rejected by the tag
 * input and by [com.github.jvsena42.loopky.data.repository.TagRepository.putTag], and only the
 * repositories that own each concept write them via `putReservedTag`.
 *
 * Anything read back under these labels is untrusted — anyone can write any label on any URI — so
 * a reader verifies before displaying: the subject has to resolve to something real, and the
 * tagger has to be the party the label claims. See `DiscoveryRepositoryImpl`.
 */
object ReservedTags {
    const val PREFIX = "loopky-"

    /** Self-tag on the user's own `profile.json` — the directory of Loopky users. */
    val USER = Tag("${PREFIX}user")

    /** On a published deck's manifest — the global list of Loopky decks. */
    val DECK = Tag("${PREFIX}deck")

    /**
     * On a followed deck's manifest, so "N people follow this" falls out of the indexer's tagger
     * count for free.
     *
     * TODO(#33): written by the follow flow once Follow deck exists; nothing writes it today.
     */
    val FOLLOWED = Tag("${PREFIX}followed")

    /**
     * On the **source** deck's manifest, so credit for a clone accrues to the original author
     * (matching the `cloned_from` provenance field).
     *
     * TODO(#33): written by the clone flow once Clone deck exists; nothing writes it today.
     */
    val CLONED = Tag("${PREFIX}cloned")

    val ALL: Set<Tag> = setOf(USER, DECK, FOLLOWED, CLONED)

    private const val LANGUAGE_PREFIX = "${PREFIX}lang-"

    /**
     * On a published deck's manifest, one per language the deck declares — the global list of
     * decks in that language, so a Spanish learner can find Spanish decks by strangers.
     *
     * Open-ended rather than a fixed label, which is why [ALL] cannot hold these and
     * `requireReserved` has to admit them separately. Takes a base subtag (see
     * [Deck.languageCodes]), not a full BCP-47 tag: indexing `es-ES` apart from `es-MX` would
     * split a search for Spanish decks in two.
     */
    fun language(code: String): Tag = Tag("$LANGUAGE_PREFIX${code.trim().lowercase()}")

    fun isLanguage(tag: Tag): Boolean = tag.value.trim().lowercase().startsWith(LANGUAGE_PREFIX)

    /**
     * True for anything in the reserved namespace, not just [ALL] — a label a future version
     * writes must not become authorable today by virtue of not existing yet.
     */
    fun isReserved(label: String): Boolean = label.trim().lowercase().startsWith(PREFIX)

    fun isReserved(tag: Tag): Boolean = isReserved(tag.value)
}
