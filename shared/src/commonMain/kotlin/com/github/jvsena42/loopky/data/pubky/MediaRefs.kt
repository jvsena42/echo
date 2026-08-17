package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.MediaRef

/**
 * Rewrite a deck-relative media ref as an absolute `pubky://` one pointing at [authorPubky]'s copy
 * of the blob, or return it unchanged when it needs no origin.
 *
 * This is what makes cloning a media-heavy deck instant (#33). A card's `path` is relative to *its
 * own* deck (`media/{sha}.{ext}`), so copying it verbatim into a clone would point at a blob that
 * was never uploaded there. Pinning the origin instead means a clone of an Anki-sized deck costs
 * card records and nothing else — against hundreds of MB of re-upload.
 *
 * The clone is not permanently tethered: because refs are content-addressed,
 * [com.github.jvsena42.loopky.data.repository.MediaRepository.rehost] can later copy the blob under
 * the clone's own path and the digest is unchanged, so every card referencing it stays valid.
 *
 * Left alone are refs that need no origin: one that already carries a [MediaRef.uri] (a clone of a
 * clone — keep the first origin rather than chaining), a remote web image (`url`, e.g. Unsplash,
 * where re-hosting the bytes would breach their licence anyway), and an empty [MediaRef.sha256],
 * which addresses no blob at all.
 */
internal fun MediaRef.Image.absolutizedTo(authorPubky: String, deckId: String): MediaRef.Image {
    if (isRemote) return this
    return copy(uri = originUri(authorPubky, deckId) ?: return this)
}

/** [MediaRef.Image.absolutizedTo] for audio; audio has no remote-URL form. */
internal fun MediaRef.Audio.absolutizedTo(authorPubky: String, deckId: String): MediaRef.Audio =
    copy(uri = originUri(authorPubky, deckId) ?: return this)

/** Where [authorPubky]'s copy of this blob lives, or null when the ref already resolves. */
private fun MediaRef.originUri(authorPubky: String, deckId: String): String? {
    if (uri != null || sha256.isEmpty()) return null
    return PubkyPaths.media(authorPubky, deckId, sha256, path.substringAfterLast('.', ""))
}
