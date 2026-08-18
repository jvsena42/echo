package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
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
class FollowListViewModelTest {

    private val discovery = FakeDiscoveryRepository()
    private val identity = FakeIdentityRepository()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(source: FollowSource, targetPubky: String = TEST_PUBKY) =
        FollowListViewModel(
            targetPubky = targetPubky,
            source = source,
            discoveryRepository = discovery,
            identityRepository = identity,
        )

    private val grace = PubkyIdentity("gracepk", "Grace Hopper", null, null)
    private val ada = PubkyIdentity("adapk", "Ada Lovelace", null, null)

    @Test
    fun followingShowsThePeopleTheUserFollows() = runTest {
        discovery.followingByUser = mapOf(TEST_PUBKY to listOf(grace, ada))
        val vm = viewModel(FollowSource.FOLLOWING)

        advanceUntilIdle()

        assertEquals(listOf("gracepk", "adapk"), vm.state.value.people.map { it.pubky })
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.errorReason)
    }

    @Test
    fun followersReadsTheOtherDirection() = runTest {
        // Same screen, different source — the two must not be able to answer for each other.
        discovery.followingByUser = mapOf(TEST_PUBKY to listOf(grace))
        discovery.followersByUser = mapOf(TEST_PUBKY to listOf(ada))
        val vm = viewModel(FollowSource.FOLLOWERS)

        advanceUntilIdle()

        assertEquals(listOf("adapk"), vm.state.value.people.map { it.pubky })
    }

    @Test
    fun knowsWhoseGraphItIsShowing() = runTest {
        // The empty state addresses the user in the second person, which is wrong the moment this
        // screen is opened from someone else's profile.
        val own = viewModel(FollowSource.FOLLOWING)
        val other = viewModel(FollowSource.FOLLOWING, targetPubky = "gracepk")

        advanceUntilIdle()

        assertTrue(own.state.value.isSelf)
        assertFalse(other.state.value.isSelf)
    }

    @Test
    fun anEmptyGraphIsNotAnError() = runTest {
        val vm = viewModel(FollowSource.FOLLOWING)

        advanceUntilIdle()

        assertTrue(vm.state.value.people.isEmpty())
        assertNull(vm.state.value.errorReason)
    }

    @Test
    fun aFailedReadIsSurfacedAsAnErrorRatherThanAnEmptyList() = runTest {
        // An unreachable indexer rendering as "you follow nobody" is the failure mode worth
        // guarding: it is indistinguishable from the truth.
        discovery.followListError = IllegalStateException("something went wrong")
        val vm = viewModel(FollowSource.FOLLOWING)

        advanceUntilIdle()

        assertEquals(ErrorReason.Unknown, vm.state.value.errorReason)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun retryClearsTheErrorAndReloads() = runTest {
        discovery.followListError = IllegalStateException("something went wrong")
        val vm = viewModel(FollowSource.FOLLOWING)
        advanceUntilIdle()

        discovery.followListError = null
        discovery.followingByUser = mapOf(TEST_PUBKY to listOf(grace))
        vm.onRetry()
        advanceUntilIdle()

        assertNull(vm.state.value.errorReason)
        assertEquals(listOf("gracepk"), vm.state.value.people.map { it.pubky })
    }
}
