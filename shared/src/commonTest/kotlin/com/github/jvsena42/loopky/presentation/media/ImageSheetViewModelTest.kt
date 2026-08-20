package com.github.jvsena42.loopky.presentation.media

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.data.unsplash.UnsplashPhoto
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeUnsplashKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ImageSheetViewModelTest {

    private val http = FakeHttpFetcher()

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // The VM loads a random grid in init; keep it out of the way of the ping assertions.
        http.respond("$BASE/photos/random?count=30", "[]")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val keyStore = FakeUnsplashKeyStore(KEY)

    private fun viewModel(client: UnsplashClient = client()) = ImageSheetViewModel(client)

    private fun client(
        keyStore: FakeUnsplashKeyStore = this.keyStore,
        fallbackKey: String = "",
    ) = UnsplashClient(http, keyStore = keyStore, fallbackKey = fallbackKey, baseUrl = BASE)

    @Test
    fun selectingAPhotoKeepsTheWholePhotoNotJustItsUrl() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPhotoSelected(photo())

        assertEquals(photo(), vm.state.value.selectedPhoto)
    }

    @Test
    fun usingAPhotoPingsUnsplashExactlyOncePerPhoto() = runTest {
        http.respond(DOWNLOAD_URL, """{"url":"https://images.test/abc.jpg"}""")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPhotoSelected(photo())

        vm.onPhotoUsed()
        vm.onPhotoUsed()
        vm.onPhotoSelected(photo())
        vm.onPhotoUsed()
        advanceUntilIdle()

        assertEquals(expected = 1, actual = http.requestedUrls.count { it == DOWNLOAD_URL })
    }

    @Test
    fun usingAPhotoWithNothingSelectedPingsNothing() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onPhotoUsed()
        advanceUntilIdle()

        assertEquals(expected = 0, actual = http.requestedUrls.count { it == DOWNLOAD_URL })
    }

    @Test
    fun aFailedPingIsSwallowedRatherThanShownToTheUser() = runTest {
        http.fail(DOWNLOAD_URL, HttpError(statusCode = 403, message = "rate limited"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onPhotoSelected(photo())

        vm.onPhotoUsed()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(photo(), vm.state.value.selectedPhoto)
    }

    @Test
    fun `with no key the sheet reports a missing key rather than an empty grid`() = runTest {
        val vm = viewModel(client(keyStore = FakeUnsplashKeyStore()))
        advanceUntilIdle()

        // "No images found" would blame the search; the user has no key, which is fixable.
        assertEquals(UnsplashError.MissingKey, vm.state.value.error)
        assertEquals(emptyList(), vm.state.value.photos)
    }

    @Test
    fun `saving a key in Settings reloads a sheet that is still open`() = runTest {
        val store = FakeUnsplashKeyStore()
        val vm = viewModel(client(keyStore = store))
        advanceUntilIdle()
        assertEquals(UnsplashError.MissingKey, vm.state.value.error)

        store.save(KEY)
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(expected = 1, actual = http.requestedUrls.count { it == RANDOM_URL })
    }

    @Test
    fun `a failed search clears the grid instead of leaving stale results under the error`() = runTest {
        http.respond(RANDOM_URL, PHOTO_JSON)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 1, actual = vm.state.value.photos.size)

        http.fail("$BASE/search/photos?per_page=30&query=cats", HttpError(statusCode = 401, message = "no"))
        vm.onQueryChange("cats")
        advanceUntilIdle()

        assertEquals(UnsplashError.InvalidKey, vm.state.value.error)
        assertEquals(emptyList(), vm.state.value.photos)
        assertNull(vm.state.value.selectedPhoto)
    }

    private fun photo() = UnsplashPhoto(
        id = "abc",
        thumbUrl = "t.jpg",
        fullUrl = "r.jpg",
        authorName = "Ana Ruiz",
        authorProfileUrl = "https://unsplash.com/@ana?utm_source=loopky&utm_medium=referral",
        downloadLocation = DOWNLOAD_URL,
    )

    private companion object {
        const val BASE = "https://unsplash.test"
        const val KEY = "test-key"
        const val DOWNLOAD_URL = "$BASE/photos/abc/download?ixid=xyz"
        const val RANDOM_URL = "$BASE/photos/random?count=30"
        const val PHOTO_JSON = """[{"id":"abc","urls":{"small":"s.jpg"},"user":{"name":"Ana"}}]"""
    }
}
