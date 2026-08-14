package com.github.jvsena42.echo.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityTest {

    private fun identity(pubky: String = "rk3xq9abc", displayName: String? = null) =
        PubkyIdentity(pubky = pubky, displayName = displayName, avatarUrl = null, bio = null)

    @Test
    fun displayNameWinsOverPubky() {
        assertEquals('C', identity(displayName = "Cosmic-Crystal-Panda").avatarInitial)
    }

    @Test
    fun fallsBackToPubkyWithoutADisplayName() {
        assertEquals('R', identity().avatarInitial)
    }

    @Test
    fun blankDisplayNameFallsBackToPubky() {
        assertEquals('R', identity(displayName = "   ").avatarInitial)
    }

    @Test
    fun leadingSpaceInDisplayNameIsIgnored() {
        assertEquals('A', identity(displayName = "  Ada").avatarInitial)
    }

    @Test
    fun emptyIdentityShowsAPlaceholder() {
        assertEquals('?', identity(pubky = "").avatarInitial)
    }
}
