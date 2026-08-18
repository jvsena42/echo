package com.github.jvsena42.loopky.presentation.settings

import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val identityRepo = FakeIdentityRepository()
    private val preferences = FakeAppPreferences()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(
        identityRepository = identityRepo,
        pubkyClient = FakePubkyClient(),
        appPreferences = preferences,
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
}
