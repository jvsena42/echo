package com.github.jvsena42.eco.data.repository

import com.github.jvsena42.eco.domain.model.Card
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.ImportDraft
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.PubkyUri
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.domain.model.SrsState
import com.github.jvsena42.eco.domain.model.Tag

interface IdentityRepository {
    suspend fun currentSession(): Session?
    suspend fun signIn(): Result<Session>
    suspend fun signOut(): Result<Unit>
}

interface DeckRepository {
    suspend fun getLocal(id: String): Deck?
    suspend fun fetchRemote(uri: PubkyUri): Result<Deck>
    suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck>
    suspend fun delete(id: String): Result<Unit>
    suspend fun listOwned(): List<Deck>
}

interface CardRepository {
    suspend fun listByDeck(deckId: String): List<Card>
    suspend fun upsert(card: Card): Result<Unit>
    suspend fun delete(cardId: String): Result<Unit>
}

interface ImportRepository {
    fun currentDraft(): ImportDraft?
    suspend fun parse(rawText: String): Result<ImportDraft>
    fun clear()
}

interface TagRepository {
    suspend fun putTag(deckUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(deckUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun trending(): List<Tag>
}

interface DiscoveryRepository {
    suspend fun decksByTag(tag: Tag): List<Deck>
    suspend fun decksFromFollowing(): List<Deck>
}

interface SrsRepository {
    suspend fun dueToday(): List<Card>
    suspend fun stateFor(cardId: String): SrsState?
    suspend fun upsert(state: SrsState): Result<Unit>
}

interface MediaRepository {
    suspend fun storeImage(bytes: ByteArray, ext: String): Result<String>
    suspend fun storeAudio(bytes: ByteArray, ext: String): Result<String>
    suspend fun delete(path: String): Result<Unit>
}
