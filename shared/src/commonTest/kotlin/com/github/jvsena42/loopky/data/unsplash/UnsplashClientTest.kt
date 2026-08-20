package com.github.jvsena42.loopky.data.unsplash

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeUnsplashKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsplashClientTest {

    private val http = FakeHttpFetcher()
    private val client = clientWith(fallbackKey = KEY)

    private fun clientWith(userKey: String = "", fallbackKey: String = "") = UnsplashClient(
        http = http,
        keyStore = FakeUnsplashKeyStore(userKey),
        fallbackKey = fallbackKey,
        baseUrl = BASE,
    )

    @Test
    fun searchParsesAttributionAndDownloadLocation() = runTest {
        http.respond(
            "$BASE/search/photos?per_page=30&query=cats",
            """
            {"results":[{
              "id":"abc",
              "urls":{"thumb":"t.jpg","small":"s.jpg","regular":"r.jpg"},
              "user":{"name":"Ana Ruiz","username":"ana","links":{"html":"https://unsplash.com/@ana"}},
              "links":{"download_location":"$BASE/photos/abc/download?ixid=xyz"}
            }]}
            """.trimIndent(),
        )

        val photo = client.search("cats").getOrThrow().single()

        assertEquals("Ana Ruiz", photo.authorName)
        assertEquals(
            expected = "https://unsplash.com/@ana?utm_source=loopky&utm_medium=referral",
            actual = photo.authorProfileUrl,
        )
        assertEquals("$BASE/photos/abc/download?ixid=xyz", photo.downloadLocation)
    }

    @Test
    fun profileUrlFallsBackToUsernameAndKeepsExistingQuery() = runTest {
        http.respond(
            "$BASE/photos/random?count=30",
            """
            [
              {"id":"a","urls":{"small":"s.jpg"},"user":{"name":"Bo","username":"bo"}},
              {"id":"b","urls":{"small":"s.jpg"},"user":{"name":"Cy","links":{"html":"https://x.test/cy?ref=1"}}},
              {"id":"c","urls":{"small":"s.jpg"}}
            ]
            """.trimIndent(),
        )

        val photos = client.random().getOrThrow()

        assertEquals("https://unsplash.com/@bo?utm_source=loopky&utm_medium=referral", photos[0].authorProfileUrl)
        // An existing query string must be extended with `&`, not a second `?`.
        assertEquals("https://x.test/cy?ref=1&utm_source=loopky&utm_medium=referral", photos[1].authorProfileUrl)
        assertEquals("", photos[2].authorProfileUrl)
        assertEquals("", photos[2].downloadLocation)
    }

    @Test
    fun trackDownloadHitsTheApiSuppliedUrlWithAuth() = runTest {
        val url = "$BASE/photos/abc/download?ixid=xyz"
        http.respond(url, """{"url":"https://images.test/abc.jpg"}""")

        val result = client.trackDownload(photo(downloadLocation = url))

        assertTrue(result.isSuccess)
        assertEquals(listOf(url), http.requestedUrls)
        assertEquals("Client-ID $KEY", http.requestedHeaders.single()["Authorization"])
    }

    @Test
    fun trackDownloadIsANoOpWithoutALocationOrAKey() = runTest {
        assertTrue(client.trackDownload(photo(downloadLocation = "")).isSuccess)

        val unconfigured = clientWith()
        assertTrue(unconfigured.trackDownload(photo(downloadLocation = "$BASE/x")).isSuccess)

        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun trackDownloadFailurePropagatesAsResultFailureRatherThanThrowing() = runTest {
        val url = "$BASE/photos/abc/download"
        http.fail(url, HttpError(statusCode = 403, message = "rate limited"))

        assertTrue(client.trackDownload(photo(downloadLocation = url)).isFailure)
    }

    @Test
    fun aUserKeyOverridesTheBuildTimeFallback() = runTest {
        http.respond("$BASE/photos/random?count=30", "[]")

        clientWith(userKey = "mine", fallbackKey = "shipped").random().getOrThrow()

        assertEquals("Client-ID mine", http.requestedHeaders.single()["Authorization"])
    }

    @Test
    fun theFallbackIsUsedOnlyWhileTheUserHasStoredNothing() = runTest {
        http.respond("$BASE/photos/random?count=30", "[]")

        clientWith(fallbackKey = "shipped").random().getOrThrow()

        assertEquals("Client-ID shipped", http.requestedHeaders.single()["Authorization"])
    }

    @Test
    fun withNoKeyAtAllTheCallFailsAsMissingKeyRatherThanReturningNothing() = runTest {
        val client = clientWith()

        assertEquals(UnsplashError.MissingKey, client.random().unsplashError())
        assertEquals(UnsplashError.MissingKey, client.search("cats").unsplashError())
        // An empty success would have rendered as "No images found", which is not the problem.
        assertEquals(emptyList(), http.requestedUrls)
        assertFalse(client.hasFallbackKey)
    }

    @Test
    fun unauthorizedMeansTheKeyIsWrongAndForbiddenMeansTheLimitIsSpent() = runTest {
        val url = "$BASE/photos/random?count=30"

        http.fail(url, HttpError(statusCode = 401, message = "GET $url failed with HTTP 401"))
        assertEquals(UnsplashError.InvalidKey, client.random().unsplashError())

        http.fail(url, HttpError(statusCode = 403, message = "GET $url failed with HTTP 403"))
        assertEquals(UnsplashError.RateLimited, client.random().unsplashError())
    }

    @Test
    fun anythingElseIsUnavailableRatherThanAKeyProblem() = runTest {
        val url = "$BASE/photos/random?count=30"

        http.fail(url, IllegalStateException("offline"))
        assertEquals(UnsplashError.Unavailable, client.random().unsplashError())

        // Malformed JSON is a server problem too, not the user's key.
        http.respond(url, "{not json")
        assertEquals(UnsplashError.Unavailable, client.random().unsplashError())
    }

    @Test
    fun theFailureNeverCarriesTheKeyOrTheRequestUrl() = runTest {
        val url = "$BASE/search/photos?per_page=30&query=cats"
        http.fail(url, HttpError(statusCode = 401, message = "GET $url failed with HTTP 401"))

        // This message used to be rendered to the user verbatim, URL and all.
        val message = client.search("cats").exceptionOrNull()?.message.orEmpty()

        assertFalse(message.contains(KEY))
        assertFalse(message.contains(BASE))
        assertFalse(message.contains("query=cats"))
    }

    @Test
    fun verifyChecksACandidateWithoutStoringOrUsingTheKeyInEffect() = runTest {
        val stored = FakeUnsplashKeyStore("mine")
        val client = UnsplashClient(http, keyStore = stored, fallbackKey = KEY, baseUrl = BASE)
        http.respond("$BASE/photos/random?count=1", "[]")

        assertTrue(client.verify("candidate").isSuccess)

        assertEquals("Client-ID candidate", http.requestedHeaders.single()["Authorization"])
        // Verifying is not saving — that is the ViewModel's call, and only if this succeeds.
        assertEquals("mine", stored.storedKey)
    }

    @Test
    fun verifySurfacesRejectionSoASettingsFieldCanRefuseTheKey() = runTest {
        val url = "$BASE/photos/random?count=1"
        http.fail(url, HttpError(statusCode = 401, message = "nope"))

        assertEquals(UnsplashError.InvalidKey, clientWith(fallbackKey = KEY).verify("bad").unsplashError())
        assertEquals(UnsplashError.MissingKey, clientWith(fallbackKey = KEY).verify("  ").unsplashError())
    }

    @Test
    fun maskedKeySuffixNeverReturnsMoreThanFourCharacters() {
        assertEquals("cdef", maskedKeySuffix("abcdef"))
        assertEquals("ab", maskedKeySuffix("ab"))
        assertEquals("", maskedKeySuffix(""))
    }

    @Test
    fun isConfiguredFollowsEitherSourceOfAKey() = runTest {
        assertFalse(clientWith().isConfigured.first())
        assertTrue(clientWith(fallbackKey = "shipped").isConfigured.first())
        assertTrue(clientWith(userKey = "mine").isConfigured.first())
    }

    private fun <T> Result<T>.unsplashError(): UnsplashError? =
        (exceptionOrNull() as? UnsplashException)?.error

    private fun photo(downloadLocation: String) = UnsplashPhoto(
        id = "abc",
        thumbUrl = "t.jpg",
        fullUrl = "r.jpg",
        authorName = "Ana Ruiz",
        authorProfileUrl = "https://unsplash.com/@ana",
        downloadLocation = downloadLocation,
    )

    private companion object {
        const val BASE = "https://unsplash.test"
        const val KEY = "test-key"
    }
}
