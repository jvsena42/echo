package com.github.jvsena42.loopky.presentation.share

import com.github.jvsena42.loopky.domain.model.DeckAnnouncement

/**
 * The "Share this on Pubky?" confirm shown after a deck is created, followed or cloned (#39).
 *
 * One shape for all three because the decision is the same everywhere: show the post that would be
 * written, let the user post it, decline it, or turn the whole offer off. Only the surrounding
 * screen differs — Publish folds it into its success step, Deck detail stacks it as a dialog.
 *
 * Holding the whole [announcement] rather than a rendered string is what keeps [preview] honest:
 * the text on screen is the text that gets written, not a second rendering of it.
 */
data class DeckSharePrompt(
    val announcement: DeckAnnouncement,
    /** True while the post is in flight, so the confirm button can't be tapped twice. */
    val isPosting: Boolean = false,
) {
    val kind: DeckAnnouncement.Kind get() = announcement.kind
    val preview: String get() = announcement.content
    val coverUrl: String? get() = announcement.coverUrl
}
