package com.github.jvsena42.echo.data.unsplash

import com.github.jvsena42.echo.data.nexus.HttpError
import com.github.jvsena42.echo.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnsplashClientTest {

    private val http = FakeHttpFetcher()
    private val client = UnsplashClient(http, accessKey = KEY, baseUrl = BASE)

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
            expected = "https://unsplash.com/@ana?utm_source=echo&utm_medium=referral",
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

        assertEquals("https://unsplash.com/@bo?utm_source=echo&utm_medium=referral", photos[0].authorProfileUrl)
        // An existing query string must be extended with `&`, not a second `?`.
        assertEquals("https://x.test/cy?ref=1&utm_source=echo&utm_medium=referral", photos[1].authorProfileUrl)
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

        val unconfigured = UnsplashClient(http, accessKey = "", baseUrl = BASE)
        assertTrue(unconfigured.trackDownload(photo(downloadLocation = "$BASE/x")).isSuccess)

        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun trackDownloadFailurePropagatesAsResultFailureRatherThanThrowing() = runTest {
        val url = "$BASE/photos/abc/download"
        http.fail(url, HttpError(statusCode = 403, message = "rate limited"))

        assertTrue(client.trackDownload(photo(downloadLocation = url)).isFailure)
    }

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
