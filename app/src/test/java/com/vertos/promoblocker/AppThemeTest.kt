package com.vertos.promoblocker

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    @Test fun savedChoicesFromEarlierVersionsHaveAManualTheme() {
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStored("SYSTEM"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStored("DARK"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStored("LIGHT"))
    }
}
