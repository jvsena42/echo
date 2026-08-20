package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val identityRepo = FakeIdentityRepository()
    private val preferences = FakeAppPreferences()
    private val http = FakeHttpFetcher()
    private val keyStore = FakeUnsplashKeyStore()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(fallbackKey: String = "") = SettingsViewModel(
        identityRepository = identityRepo,
        pubkyClient = FakePubkyClient(),
        appPreferences = preferences,
        unsplashKeyStore = keyStore,
        unsplashClient = UnsplashClient(
            http = http,
            keyStore = keyStore,
            fallbackKey = fallbackKey,
            baseUrl = UNSPLASH_BASE,
        ),
    )

    @Test
    fun `share on Pubky starts on`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.shareOnPubky)
    }

    @Test
    fun `toggling the switch persists the choice`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onShareOnPubkyChange(false)
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
        assertFalse(preferences.shareOnPubkyValue)
    }

    @Test
    fun `a stored opt-out survives into a new session`() = runTest(mainDispatcher) {
        preferences.setShareOnPubky(false)

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
    }

    @Test
    fun `the switch follows a Don't ask again taken elsewhere`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        // What a prompt's "Don't ask again" does — the two controls are one setting.
        preferences.setShareOnPubky(false)
        advanceUntilIdle()

        assertFalse(vm.state.value.shareOnPubky)
    }

    // --- Unsplash access key ---

    @Test
    fun `with neither key the row says web image search is off`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(UnsplashKeyStatus.NotSet, vm.state.value.unsplashKeyStatus)
    }

    @Test
    fun `a build-time key is admitted to exist but never rendered`() = runTest(mainDispatcher) {
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()

        assertEquals(UnsplashKeyStatus.UsingBuiltIn, vm.state.value.unsplashKeyStatus)
        // The shared key is the one the user cannot rotate; showing it would leak it to every
        // screenshot of this screen.
        assertFalse(vm.state.value.toString().contains(SHIPPED_KEY))
    }

    @Test
    fun `a verified key is stored and shown as four characters`() = runTest(mainDispatcher) {
        http.respond(VERIFY_URL, "[]")
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()

        vm.onSaveUnsplashKey("  user-key-abcd1234  ")
        advanceUntilIdle()

        assertEquals("user-key-abcd1234", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.UserSet("1234"), vm.state.value.unsplashKeyStatus)
        assertFalse(vm.state.value.toString().contains("user-key-abcd"))
        assertNull(vm.state.value.unsplashKeyError)
    }

    @Test
    fun `a rejected key is reported and never stored`() = runTest(mainDispatcher) {
        http.fail(VERIFY_URL, HttpError(statusCode = 401, message = "nope"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSaveUnsplashKey("typo")
        advanceUntilIdle()

        assertEquals(UnsplashError.InvalidKey, vm.state.value.unsplashKeyError)
        assertEquals("", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.NotSet, vm.state.value.unsplashKeyStatus)
    }

    @Test
    fun `an unreachable Unsplash still saves because that is not the key's fault`() =
        runTest(mainDispatcher) {
            http.fail(VERIFY_URL, IllegalStateException("offline"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.onSaveUnsplashKey("probably-fine")
            advanceUntilIdle()

            // Refusing here would make the field unusable on a train.
            assertEquals("probably-fine", keyStore.storedKey)
            assertNull(vm.state.value.unsplashKeyError)
        }

    @Test
    fun `a blank key is not even sent`() = runTest(mainDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSaveUnsplashKey("   ")
        advanceUntilIdle()

        assertEquals("", keyStore.storedKey)
        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun `removing the user key falls back to the built-in one`() = runTest(mainDispatcher) {
        http.respond(VERIFY_URL, "[]")
        val vm = viewModel(fallbackKey = SHIPPED_KEY)
        advanceUntilIdle()
        vm.onSaveUnsplashKey("mine-wxyz")
        advanceUntilIdle()

        vm.onRemoveUnsplashKey()
        advanceUntilIdle()

        assertEquals("", keyStore.storedKey)
        assertEquals(UnsplashKeyStatus.UsingBuiltIn, vm.state.value.unsplashKeyStatus)
    }

    private companion object {
        const val UNSPLASH_BASE = "https://unsplash.test"
        const val VERIFY_URL = "$UNSPLASH_BASE/photos/random?count=1"
        const val SHIPPED_KEY = "shipped-secret-key"
    }
}
