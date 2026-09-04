package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CLI_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

/**
 * `--check-images`: ask each distinct picture URL whether it is a picture, once.
 *
 * The static checks in `ImageUrls.kt` answer what a *string* can be known to be wrong about, and
 * they cover most of it. Three things they cannot see produce exactly the same silent blank card
 * (#229, item 4): a Wikipedia lead image that resolves to `.stl` or `.webm` behind a URL that
 * looks fine, a file that has been renamed or deleted, and a host that refuses an unfamiliar
 * client. One `HEAD` catches all three, and the caller ended up writing that check by hand anyway.
 *
 * Three properties keep it consistent with "nothing is uploaded, nothing is fetched":
 *
 * - **Opt-in.** Off by default, so an ordinary `card add` stays one write and no round trips.
 * - **Never fatal.** A host that is having a bad minute, or that refuses `HEAD` in a way the
 *   fallback does not cover, must not be able to fail somebody's import — the picture may be
 *   perfectly good. Every finding is a warning on stderr and a row in the result.
 * - **One request per distinct URL**, not per card. A picture on forty cards is asked about once.
 */
@Serializable
data class ImageCheck(
    val url: String,
    /** The HTTP status, or null when the host could not be reached at all. */
    val status: Int? = null,
    @SerialName("content_type") val contentType: String? = null,
    /** True when the host answered 2xx with an `image/…` content type. Everything else is worth reading. */
    val ok: Boolean = false,
    /** Why this is being reported, for a person. Null when [ok]. */
    val reason: String? = null,
)

/** Whether this invocation asked for the probe. */
internal fun Args.checksImages(): Boolean = has(CHECK_IMAGES_FLAG)

internal const val CHECK_IMAGES_FLAG = "check-images"

/**
 * Probe [urls], returning **only what is worth reporting** — an address that answered 2xx with an
 * image content type produces no row and no note.
 *
 * Concurrency is capped rather than unbounded: 900 simultaneous connections to one host is a way
 * to be rate-limited into a false negative, which for a check whose whole job is telling truth
 * from silence would be worse than not running it.
 */
internal suspend fun checkImageUrls(
    urls: Collection<String>,
    onNote: (String) -> Unit,
    probe: suspend (String) -> ImageCheck = ::probeImage,
): List<ImageCheck> {
    val distinct = urls.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return emptyList()
    onNote("loopky: checking ${distinct.size} distinct picture URL(s)…")

    val problems = distinct.chunked(PROBE_CONCURRENCY)
        .flatMap { batch -> coroutineScope { batch.map { async { probe(it) } }.awaitAll() } }
        .filterNot { it.ok }

    problems.forEach { onNote("loopky: ${it.describe()}") }
    if (problems.isEmpty()) {
        onNote("loopky: every picture URL answered with an image.")
    } else {
        // Named as a warning rather than left to be inferred from the rows: the write goes ahead.
        onNote("loopky: ${problems.size} of ${distinct.size} picture URL(s) look wrong — writing anyway.")
    }
    return problems
}

private fun ImageCheck.describe(): String = buildString {
    append(url)
    append(" — ")
    append(reason)
    status?.let { append(" (HTTP $it") }
    contentType?.let { append(", $it") }
    if (status != null) append(")")
}

/**
 * One request for one URL.
 *
 * `HEAD` first, because the point is to spend nothing: a 4 MB picture answers this in a few
 * hundred bytes. A host that refuses the method — 403 or 405 to a `HEAD` it serves happily to a
 * `GET` is a real and common configuration — is asked again with a one-byte ranged `GET`, so a
 * working picture is not reported as broken by a quirk of the method.
 *
 * The user agent is not decoration. Wikimedia answers `403 Please set a user-agent` to a generic
 * one, which is the very failure mode this exists to catch, and a probe that produced it on every
 * Wikimedia URL would be worse than no probe.
 */
private suspend fun probeImage(url: String): ImageCheck = withContext(Dispatchers.IO) {
    val head = request(url, "HEAD")
    val answer = if (head.status in METHOD_REFUSED) request(url, "GET", ranged = true) else head
    answer.classified(url)
}

private class Answer(val status: Int?, val contentType: String?, val failure: String?)

private fun Answer.classified(url: String): ImageCheck {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        failure != null -> ImageCheck(url, reason = "could not be reached: $failure")
        status !in SUCCESS -> ImageCheck(url, status, type, reason = "the host refused it")
        type == null -> ImageCheck(url, status, null, reason = "the host named no content type")
        !type.startsWith(IMAGE_PREFIX) ->
            ImageCheck(url, status, type, reason = "this is not an image — both apps will render a blank card")

        else -> ImageCheck(url, status, type, ok = true)
    }
}

private fun request(url: String, method: String, ranged: Boolean = false): Answer {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
        .getOrElse { return Answer(null, null, it.message ?: "bad URL") }
    return try {
        connection.requestMethod = method
        connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
        connection.readTimeout = PROBE_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        // One byte, so the fallback for a host that refuses HEAD still transfers nothing.
        if (ranged) connection.setRequestProperty("Range", "bytes=0-0")
        val status = connection.responseCode
        Answer(status, connection.contentType, null)
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Every failure here is "the host did not answer", which is the finding. Nothing is
        // rethrown: the probe is advisory, and an unreachable host must not fail the write.
        Answer(null, null, error.message ?: error::class.simpleName)
    } finally {
        runCatching { connection.disconnect() }
    }
}

/**
 * What Wikimedia asks for, and what every other host is entitled to see. `403 Please set a
 * user-agent` to a generic client is a real answer from a real host in this path.
 */
private val USER_AGENT = "loopky/$CLI_VERSION (+https://github.com/jvsena42/loopky)"

/** Statuses that mean "not this method", worth one ranged GET before believing them. */
private val METHOD_REFUSED = setOf(403, 405, 501)

private val SUCCESS = 200..299

private const val IMAGE_PREFIX = "image/"

/** Enough to be quick on 900 URLs, few enough not to be rate-limited into a false negative. */
private const val PROBE_CONCURRENCY = 8

private const val PROBE_CONNECT_TIMEOUT_MS = 5_000
private const val PROBE_READ_TIMEOUT_MS = 10_000
