package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.deleteWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.requireSession
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.serialization.encodeToString

/**
 * Tags use the pubky.app native primitive: a record at `/pub/pubky.app/tags/{tagId}` whose body
 * is `{uri, label, created_at}` and whose id is content-derived (pubky-app-specs `HashId`),
 * written to the *tagger's* homeserver. Nexus indexes these records network-wide, which is what
 * makes [trending] answerable — a single homeserver has no global view.
 *
 * Labels are sanitized to the spec (trimmed, lowercase, 1–20 chars, no whitespace) before
 * hashing so the derived id always matches what the indexer validates.
 */
class TagRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val nexus: NexusClient,
) : TagRepository {

    override suspend fun putTag(deckUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        val label = sanitizeLabel(tag).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(deckUri.value, label).getOrThrow()
        val body = loopkyJson.encodeToString(
            TagDto(uri = deckUri.value, label = label, created_at = epochMillis()),
        )
        pubky.putWithSessionRetry(
            url = PubkyPaths.tag(owner, tagId),
            content = body,
            session = session,
            revalidator = revalidator,
        ).getOrThrow()
        Unit
    }

    override suspend fun removeTag(deckUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        val label = sanitizeLabel(tag).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(deckUri.value, label).getOrThrow()
        pubky.deleteWithSessionRetry(PubkyPaths.tag(owner, tagId), session, revalidator)
            .getOrThrow()
        Unit
    }

    override suspend fun trending(): List<Tag> =
        nexus.hotTags()
            .getOrElse { emptyList() }
            .map { Tag(it.label) }

    /** pubky-app-specs tag label rules: trimmed, lowercase, 1–20 chars, no whitespace. */
    private fun sanitizeLabel(tag: Tag): Result<String> {
        val label = tag.value.trim().lowercase()
        return when {
            label.isEmpty() ->
                Result.failure(IllegalArgumentException("Tag label must not be empty"))

            label.length > MAX_LABEL_LENGTH ->
                Result.failure(
                    IllegalArgumentException("Tag label must be at most $MAX_LABEL_LENGTH chars"),
                )

            label.any { it.isWhitespace() } ->
                Result.failure(
                    IllegalArgumentException("Tag label must not contain whitespace"),
                )

            else -> Result.success(label)
        }
    }

    private companion object {
        const val MAX_LABEL_LENGTH = 20
    }
}
