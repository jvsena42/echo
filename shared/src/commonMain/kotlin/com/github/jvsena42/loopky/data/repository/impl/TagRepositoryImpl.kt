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
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.serialization.encodeToString

/**
 * Tags use the pubky-app-specs primitive: a record whose body is `{uri, label, created_at}` and
 * whose id is content-derived (`HashId`), written to the *tagger's* homeserver. Nexus indexes
 * these records network-wide, which is what makes [trending] answerable — a single homeserver has
 * no global view.
 *
 * **Which namespace the record goes in is decided by the subject**, because Nexus has two ingest
 * paths and picks between them on the record's own path (Architecture.md §7.7):
 *
 * - subject is a pubky.app `profile.json` → [PubkyPaths.tag], the pubky.app namespace, indexed
 *   into the user graph (reachable from `/v0/tags/hot`, `/v0/tags/taggers/{label}`);
 * - anything else, i.e. a deck manifest → [PubkyPaths.loopkyTag], indexed as a generic resource
 *   (reachable from `/v0/stream/resources?app=loopky`).
 *
 * Putting a deck manifest subject in the pubky.app namespace is not merely suboptimal: the
 * watcher rejects the record and the tag is never indexed at all, silently. That is what Loopky
 * did before issue #40.
 *
 * Labels are sanitized to the spec (trimmed, lowercase, 1–20 chars, no whitespace) before
 * hashing so the derived id always matches what the indexer validates. The resource path does no
 * sanitizing of its own, so this is the only thing keeping deck labels from fragmenting by case.
 */
class TagRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val nexus: NexusClient,
) : TagRepository {

    override suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        rejectReserved(tag).getOrThrow()
        write(subjectUri, tag).getOrThrow()
    }

    override suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        rejectReserved(tag).getOrThrow()
        erase(subjectUri, tag).getOrThrow()
    }

    override suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        requireReserved(tag).getOrThrow()
        write(subjectUri, tag).getOrThrow()
    }

    override suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        requireReserved(tag).getOrThrow()
        erase(subjectUri, tag).getOrThrow()
    }

    private suspend fun write(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        val label = sanitizeLabel(tag).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(subjectUri.value, label).getOrThrow()
        val body = loopkyJson.encodeToString(
            TagDto(uri = subjectUri.value, label = label, created_at = epochMillis()),
        )
        pubky.putWithSessionRetry(
            url = recordPath(owner, subjectUri, tagId),
            content = body,
            session = session,
            revalidator = revalidator,
        ).getOrThrow()
        Unit
    }

    private suspend fun erase(subjectUri: PubkyUri, tag: Tag): Result<Unit> = runCatching {
        val label = sanitizeLabel(tag).getOrThrow()
        val owner = session.requireSession().identity.pubky
        val tagId = pubky.createTagId(subjectUri.value, label).getOrThrow()
        val path = recordPath(owner, subjectUri, tagId)
        pubky.deleteWithSessionRetry(path, session, revalidator).getOrThrow()

        // Decks were tagged in the pubky.app namespace before #40. Those records were never
        // indexed, but they are still sitting on the homeserver — clear them out as we go.
        val legacyPath = PubkyPaths.tag(owner, tagId)
        if (path != legacyPath) {
            pubky.deleteWithSessionRetry(legacyPath, session, revalidator).onFailure {
                Log.d(TAG, "removeTag: no legacy record at $legacyPath — ${it.message}")
            }
        }
        Unit
    }

    private fun rejectReserved(tag: Tag): Result<Unit> =
        if (ReservedTags.isReserved(tag)) {
            Result.failure(
                IllegalArgumentException("'${tag.value}' is reserved — see ReservedTags"),
            )
        } else {
            Result.success(Unit)
        }

    private fun requireReserved(tag: Tag): Result<Unit> =
        if (tag in ReservedTags.ALL) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalArgumentException("'${tag.value}' is not one of Loopky's index labels"),
            )
        }

    /** See the class doc: Nexus routes on the record's own namespace, so the subject picks it. */
    private fun recordPath(owner: String, subjectUri: PubkyUri, tagId: String): String =
        if (PubkyPaths.isProfileUri(subjectUri.value)) {
            PubkyPaths.tag(owner, tagId)
        } else {
            PubkyPaths.loopkyTag(owner, tagId)
        }

    override suspend fun trending(): List<Tag> =
        nexus.hotTags()
            .getOrElse { emptyList() }
            .map { Tag(it.label) }

    override suspend fun taggedSubjects(tag: Tag, limit: Int): List<TaggedSubject> {
        val label = sanitizeLabel(tag).getOrElse { return emptyList() }
        return nexus.resourcesByTag(label, limit)
            .onFailure { Log.w(TAG, "taggedSubjects('$label'): FAILED — ${it.message}") }
            .getOrElse { emptyList() }
            .map { resource ->
                // Prefer the count for *this* label; the resource-level count spans every label
                // on the subject, and the per-label entry can be missing if the tag list was cut.
                val details = resource.tags.firstOrNull { it.label == label }
                TaggedSubject(
                    uri = PubkyUri(resource.details.uri),
                    taggers = details?.taggers.orEmpty(),
                    taggersCount = details?.taggers_count ?: resource.taggers_count,
                )
            }
    }

    override suspend fun taggersOf(tag: Tag, limit: Int): List<String> {
        val label = sanitizeLabel(tag).getOrElse { return emptyList() }
        return nexus.taggersOfLabel(label, limit)
            .onFailure { Log.w(TAG, "taggersOf('$label'): FAILED — ${it.message}") }
            .getOrElse { emptyList() }
    }

    override suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean {
        val label = sanitizeLabel(tag).getOrElse { return false }
        return pubky in nexus.userTaggers(pubky, label).getOrElse { emptyList() }
    }

    override suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int> =
        nexus.resourceByUri(subjectUri.value)
            // A 404 here just means nobody has tagged it yet, which is not worth an error log.
            .getOrElse { return emptyMap() }
            .tags
            .associate { Tag(it.label) to it.taggers_count }

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
        const val TAG = "Loopky/TagRepo"
        const val MAX_LABEL_LENGTH = 20
    }
}
