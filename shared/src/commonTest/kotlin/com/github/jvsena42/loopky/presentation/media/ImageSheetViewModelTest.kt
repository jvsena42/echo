package com.github.jvsena42.loopky.presentation.media

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashPhoto
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
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

    private fun viewModel() =
        ImageSheetViewModel(UnsplashClient(http, accessKey = KEY, baseUrl = BASE))

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
    }
}
