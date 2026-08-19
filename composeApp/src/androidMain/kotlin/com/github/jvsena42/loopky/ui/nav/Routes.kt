package com.github.jvsena42.loopky.ui.nav

import android.net.Uri
import com.github.jvsena42.loopky.presentation.profile.FollowSource

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val DECK_DETAIL = "deck/{deckId}?author={author}"
    const val DECK_EDITOR = "deck/editor/{deckId}"
    const val EDIT_CARD = "deck/{deckId}/card/{cardId}/edit"

    /**
     * The card editor on a card that does not exist yet. A separate route rather than a flag on
     * [EDIT_CARD]: one fewer path segment, so the two patterns cannot match the same URL.
     */
    const val NEW_CARD = "deck/{deckId}/card/new"

    const val IMPORT_PASTE = "import/paste"

    /** Bulk file import: summary + one confirm, not the swipe queue (spec §5.4). */
    const val IMPORT_BULK = "import/bulk"
    const val IMPORT_TRIAGE = "import/triage"
    const val IMPORT_TRIAGE_EDIT = "import/triage/edit/{rowIndex}"
    const val IMPORT_PUBLISH = "import/publish"

    /** Study session. `deckId` omitted = study all due cards across owned decks. */
    const val STUDY = "study?deckId={deckId}"

    /** Another user's public profile (their decks + follow button). */
    const val FRIEND_PROFILE = "profile/{pubky}"

    /** Every deck on the network carrying one tag — where a tag chip leads (brief §9.3). */
    const val TAG_BROWSE = "tag/{tag}"

    /** One box over people and decks, by name or by pubky. Reached from Discover's header. */
    const val SEARCH = "search"

    /** One side of someone's follow graph. `source` is a [FollowSource] name, lowercased. */
    const val FOLLOW_LIST = "follows/{pubky}/{source}"

    fun deckDetail(deckId: String, author: String? = null) =
        if (author != null) "deck/$deckId?author=$author" else "deck/$deckId"

    /**
     * Pubkys are z-base-32 and need no escaping, but the add-friend sheet takes free-typed text —
     * a stray character produced a route no destination matched, i.e. a tap that did nothing.
     */
    fun friendProfile(pubky: String) = "profile/" + Uri.encode(pubky)

    /** Encoded for the same reason [friendProfile] is — the pubky can come from typed text. */
    fun followList(pubky: String, source: FollowSource) =
        "follows/" + Uri.encode(pubky) + "/" + source.name.lowercase()

    /** Labels are sanitized to lowercase with no whitespace, but encode anyway — they are free text. */
    fun tagBrowse(tag: String) = "tag/" + Uri.encode(tag)
    fun deckEditor(deckId: String) = "deck/editor/$deckId"
    fun editCard(deckId: String, cardId: String) = "deck/$deckId/card/$cardId/edit"
    fun newCard(deckId: String) = "deck/$deckId/card/new"
    fun study(deckId: String?) = if (deckId != null) "study?deckId=$deckId" else "study"
    fun triageEditCard(rowIndex: Int) = "import/triage/edit/$rowIndex"
}
