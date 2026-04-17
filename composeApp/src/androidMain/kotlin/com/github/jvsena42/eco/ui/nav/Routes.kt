package com.github.jvsena42.eco.ui.nav

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val DECK_DETAIL = "deck/{deckId}"
    const val DECK_EDITOR = "deck/editor/{deckId}"
    const val DECK_EDITOR_NEW = "deck/editor/new"
    const val EDIT_CARD = "deck/{deckId}/card/{cardId}/edit"

    const val IMPORT_PASTE = "import/paste"
    const val IMPORT_PUBLISH = "import/publish"

    fun deckDetail(deckId: String) = "deck/$deckId"
    fun deckEditor(deckId: String) = "deck/editor/$deckId"
    fun editCard(deckId: String, cardId: String) = "deck/$deckId/card/$cardId/edit"
}
