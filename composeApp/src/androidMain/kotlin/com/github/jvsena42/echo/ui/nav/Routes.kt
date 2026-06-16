package com.github.jvsena42.echo.ui.nav

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val DECK_DETAIL = "deck/{deckId}?author={author}"
    const val DECK_EDITOR = "deck/editor/{deckId}"
    const val DECK_EDITOR_NEW = "deck/editor/new"
    const val EDIT_CARD = "deck/{deckId}/card/{cardId}/edit"

    const val IMPORT_PASTE = "import/paste"
    const val IMPORT_TRIAGE = "import/triage"
    const val IMPORT_TRIAGE_EDIT = "import/triage/edit/{rowIndex}"
    const val IMPORT_PUBLISH = "import/publish"

    /** Study session. `deckId` omitted = study all due cards across owned decks. */
    const val STUDY = "study?deckId={deckId}"

    /** Another user's public profile (their decks + follow button). */
    const val FRIEND_PROFILE = "profile/{pubky}"

    fun deckDetail(deckId: String, author: String? = null) =
        if (author != null) "deck/$deckId?author=$author" else "deck/$deckId"
    fun friendProfile(pubky: String) = "profile/$pubky"
    fun deckEditor(deckId: String) = "deck/editor/$deckId"
    fun editCard(deckId: String, cardId: String) = "deck/$deckId/card/$cardId/edit"
    fun study(deckId: String?) = if (deckId != null) "study?deckId=$deckId" else "study"
    fun triageEditCard(rowIndex: Int) = "import/triage/edit/$rowIndex"
}
