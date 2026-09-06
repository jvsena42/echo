package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AppThemeTest {

    @Test
    fun `a stored name round-trips`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, AppTheme.fromNameOrSystem(theme.name))
        }
    }

    @Test
    fun `a name a newer release wrote falls back to System`() {
        assertEquals(AppTheme.System, AppTheme.fromNameOrSystem("HighContrast"))
    }

    @Test
    fun `a blank preference falls back to System`() {
        assertEquals(AppTheme.System, AppTheme.fromNameOrSystem(""))
    }
}
