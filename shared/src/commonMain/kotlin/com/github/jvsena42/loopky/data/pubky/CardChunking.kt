package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.ordForIndex

/**
 * Pure chunk-layout maths shared by [com.github.jvsena42.loopky.data.repository.impl.DeckRepositoryImpl]
 * and [com.github.jvsena42.loopky.data.repository.impl.CardRepositoryImpl].
 *
 * The homeserver stores cards as `cards/{n}.json`, each holding up to [CHUNK_SIZE] cards, with the
 * manifest carrying only `{n, count, updated_at}` per chunk. Membership is the union of the
 * chunks — there is no separate card index to keep in sync, which is what used to let a card
 * edit and the manifest drift apart.
 */
internal object CardChunking {

    /**
     * Split cards into chunk-sized batches in study order, assigning a fresh sparse `ord` per
     * position. Used by publish, where the whole card set is being (re)written anyway.
     */
    fun chunk(cards: List<Card>): List<List<Card>> =
        cards.mapIndexed { index, card -> card.copy(ord = ordForIndex(index)) }
            .chunked(CHUNK_SIZE)

    /**
     * Chunk metadata for [chunks], stamped with [updatedAt].
     *
     * Every chunk gets the same timestamp on a full publish; single-chunk writes bump only their
     * own entry, which is what lets a follower re-fetch one chunk instead of the whole deck.
     */
    fun metaFor(chunks: List<List<Card>>, updatedAt: Long): List<ChunkMeta> =
        chunks.mapIndexed { n, batch -> ChunkMeta(n = n, count = batch.size, updatedAt = updatedAt) }

    /**
     * The chunk a new card should be appended to: the last one with room, or a new trailing chunk.
     *
     * Sequential-append rather than hash-partitioning on card id. Hashing would be stable under
     * every mutation, but it produces [CHUNK_SIZE]-many near-empty records for a 50-card deck;
     * sequential is self-describing and compacts later if it ever needs to.
     */
    fun appendTarget(chunks: List<ChunkMeta>): Int {
        val last = chunks.maxByOrNull { it.n } ?: return 0
        return if (last.count < CHUNK_SIZE) last.n else last.n + 1
    }

    /**
     * Apply a single chunk's new contents to the manifest's chunk list.
     *
     * Deleting a card leaves the chunk short rather than resequencing every later card — holes are
     * tolerated deliberately, since closing one would mean rewriting every following chunk. A chunk
     * emptied completely is dropped from the list so it stops being fetched; the record itself is
     * deleted by the caller.
     */
    fun withChunk(chunks: List<ChunkMeta>, n: Int, count: Int, updatedAt: Long): List<ChunkMeta> {
        val without = chunks.filterNot { it.n == n }
        return if (count == 0) {
            without.sortedBy { it.n }
        } else {
            (without + ChunkMeta(n = n, count = count, updatedAt = updatedAt)).sortedBy { it.n }
        }
    }

    /**
     * Card total derived from the chunk counts. Never taken from a list length: with holes, the
     * only place the true count survives is the per-chunk counts.
     */
    fun cardCount(chunks: List<ChunkMeta>): Int = chunks.sumOf { it.count }
}
