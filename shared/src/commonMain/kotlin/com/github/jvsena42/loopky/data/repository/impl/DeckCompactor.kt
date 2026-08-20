package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.CompactionOutcome
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.util.Log

/**
 * Folds the holes card deletes leave in a deck's chunk table back together (#51).
 *
 * `deleteCard` shrinks a chunk's `count` and stops there — closing the hole in place would
 * resequence every card after it and, under chunking, rewrite every following record. So the
 * density is reclaimed here instead: off the critical path, a bounded number of merges at a time,
 * driven by the manifest's own counts.
 *
 * Split from [DeckRepositoryImpl] for the same reason [DeckMediaSweeper] is — this is a pass over
 * a deck rather than another deck-write operation, and that class already carries plenty.
 */
internal class DeckCompactor(
    private val cardRepo: CardRepository,
    private val decks: DeckWriteAccess,
) {

    suspend fun compact(deckId: String, maxMerges: Int): CompactionOutcome {
        val before = decks.localDeck(deckId)?.chunks?.size ?: 0
        var merges = 0
        var cardsMoved = 0
        var complete = false

        // The lock is taken per merge rather than around the whole pass: each merge is a
        // self-contained read-then-write, and holding it for twenty of them would block a card
        // edit on the deck for the length of a background job.
        while (merges < maxMerges) {
            val step = decks.inWriteLock(deckId) { mergeOnceLocked(deckId) }
            if (step !is MergeStep.Merged) {
                complete = step is MergeStep.Done
                break
            }
            merges++
            cardsMoved += step.cardsMoved
        }

        val after = decks.localDeck(deckId)?.chunks?.size ?: before
        Log.d(TAG, "compact: $deckId merges=$merges cards=$cardsMoved $before→$after chunks")
        return CompactionOutcome(
            merges = merges,
            cardsMoved = cardsMoved,
            chunksBefore = before,
            chunksAfter = after,
            complete = complete,
        )
    }

    /**
     * Fold one pair of neighbouring chunks together.
     *
     * **The caller must hold the deck's write lock.** The deck is re-read from the cache inside it
     * rather than carried in from the pass, so each merge sees the table the previous one left.
     */
    private suspend fun mergeOnceLocked(deckId: String): MergeStep {
        val deck = decks.localDeck(deckId) ?: return MergeStep.Stop
        val (into, from) = CardChunking.mergeTarget(deck.chunks) ?: return MergeStep.Done
        val merged = mergedContents(deck, into, from) ?: return MergeStep.Stop

        decks.mergeChunksLocked(deck, into = into, from = from, merged = merged.cards)
        return MergeStep.Merged(merged.moved)
    }

    /**
     * Chunk [into]'s cards with [from]'s folded in, or null when the pass has to stop and try
     * again later.
     *
     * The fold is renumbered inside [into]'s own slice of the ord line, so study order survives
     * and no chunk other than the pair moves.
     */
    private suspend fun mergedContents(deck: Deck, into: Int, from: Int): MergedChunk? {
        val landing = readChunk(deck, into) ?: return null
        val source = readChunk(deck, from) ?: return null

        // The manifest said these two fit; the records disagree. That is a manifest already out of
        // step with its chunks, which matters far more than density — stop rather than write a
        // record over CHUNK_SIZE on top of it.
        if (landing.size + source.size > CHUNK_SIZE) {
            Log.w(
                TAG,
                "compact: ${deck.id} chunks $into+$from hold ${landing.size + source.size} cards, " +
                    "over the ${CHUNK_SIZE}-card record limit the manifest implied they were under",
            )
            return null
        }
        // A manifest entry counting cards its record no longer has. Dropping the entry is the
        // repair, and it is still progress, so the pass carries on.
        if (source.isEmpty()) return MergedChunk(landing, moved = 0)

        return MergedChunk(
            cards = CardChunking.renumber((landing + source).inStudyOrder(), into),
            moved = source.size,
        )
    }

    /**
     * One chunk's cards, or null when it could not be read. Unlike the media sweep this cannot
     * skip past a bad chunk: a merge is defined over a *pair*, and guessing at the contents of
     * either half would drop cards.
     */
    private suspend fun readChunk(deck: Deck, n: Int) = cardRepo.readChunk(deck, n)
        .onFailure { Log.e(TAG, "compact: chunk $n of ${deck.id} unreadable — ${it.message}", it) }
        .getOrNull()
        ?.inStudyOrder()

    /** Chunk contents ready to be written, and how many cards moved to get there. */
    private data class MergedChunk(val cards: List<Card>, val moved: Int)

    /** What one merge attempt did — merged a pair, ran out of pairs, or has to try again later. */
    private sealed interface MergeStep {
        data class Merged(val cardsMoved: Int) : MergeStep

        /** No two neighbours fit in one record: the table is as dense as it gets. */
        data object Done : MergeStep

        /** An unreadable chunk, or a manifest out of step with its records. */
        data object Stop : MergeStep
    }

    private companion object {
        const val TAG = "Loopky/DeckCompact"
    }
}
