package com.github.jvsena42.eco.data.pubky

import com.github.jvsena42.eco.domain.model.Capability
import com.github.jvsena42.eco.domain.model.Deck
import com.github.jvsena42.eco.domain.model.PubkyIdentity
import com.github.jvsena42.eco.domain.model.PubkyUri
import com.github.jvsena42.eco.domain.model.Session
import com.github.jvsena42.eco.domain.model.Tag

/**
 * Single entry point to pubky-core-ffi-fork. Implementation strategy is still open
 * (UniFFI-generated bindings vs. handwritten expect/actual) — see docs/Architecture.md §7.
 */
interface PubkyClient {
    suspend fun startSignIn(capabilities: List<Capability>): DeeplinkRequest
    suspend fun completeSignIn(callback: SignInCallback): Result<Session>
    suspend fun signOut(): Result<Unit>

    suspend fun publishDeck(deck: Deck): Result<PubkyUri>
    suspend fun fetchDeck(uri: PubkyUri): Result<Deck>
    suspend fun deleteDeck(uri: PubkyUri): Result<Unit>

    suspend fun putTag(target: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(target: PubkyUri, tag: Tag): Result<Unit>

    suspend fun follow(target: PubkyIdentity): Result<Unit>
    suspend fun unfollow(target: PubkyIdentity): Result<Unit>
}

data class DeeplinkRequest(val url: String)

data class SignInCallback(
    val pubky: String,
    val sessionSecret: String,
    val capabilities: List<String>,
)
