package com.github.jvsena42.loopky.ui.decks

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.decks.DeckDetailUiState

/**
 * What Deck Detail's bottom-bar Study button says.
 *
 * Reviews take precedence when there are any. With none, the count that matters is the unseen one:
 * a freshly imported deck has nothing overdue and hundreds of cards you have not met, and reading
 * "Start studying · 0 due" there described it as finished (#101 §7).
 *
 * Lives beside the screen rather than in it only because `DeckDetailScreen.kt` is already at
 * detekt's per-file function ceiling.
 */
@Composable
internal fun studyCtaLabel(state: DeckDetailUiState.Content): String = when {
    !state.isOwned -> stringResource(R.string.deck_detail_study_this_deck)
    state.dueLabel != "0" -> stringResource(R.string.deck_detail_start_studying, state.dueLabel)
    state.newCards > 0 -> stringResource(R.string.deck_detail_start_studying_new, state.newCards)
    else -> stringResource(R.string.deck_detail_start_studying, state.dueLabel)
}
