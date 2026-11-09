package com.vertos.promoblocker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BlockerRepositoryTest {
    @Test fun settingsRulesAndAuditSurviveRepositoryRecreation() = runBlocking<Unit> {
        withTimeout(15000) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            var repository = BlockerRepository(context)
            try {
                repository.awaitReady()
                assertEquals(4, repository.snapshot.rules.size)
                repository.setFlag("enabled", false)
                repository.settings.first { !it.enabled }
                assertFalse(repository.snapshot.settings.enabled)
                val snapshotBeforeThemeChange = repository.snapshot
                repository.setThemeMode(AppThemeMode.DARK_OLED)
                repository.themeMode.first { it == AppThemeMode.DARK_OLED }
                assertSame(snapshotBeforeThemeChange, repository.snapshot)
                assertFalse(repository.snapshot.settings.enabled)
                repository.addRule("^999[0-9]+$")
                repository.rules.first { it.size == 5 }
                repository.record("9991234567", "integration test")
                assertEquals("9991234567", repository.history.first { it.isNotEmpty() }.single().number)
                repository.close()
                repository = BlockerRepository(context)
                repository.awaitReady()
                assertFalse(repository.snapshot.settings.enabled)
                assertEquals(AppThemeMode.DARK_OLED, repository.themeMode.first { it == AppThemeMode.DARK_OLED })
                repository.setThemeMode(AppThemeMode.LIGHT)
                repository.themeMode.first { it == AppThemeMode.LIGHT }
                repository.setThemeMode(AppThemeMode.DARK)
                repository.themeMode.first { it == AppThemeMode.DARK }
                assertEquals(5, repository.snapshot.rules.size)
                assertEquals(1, repository.count.first { it == 1 })
                val custom = repository.rules.first { it.size == 5 }.single { !it.builtIn }
                repository.toggleRule(custom.id, false)
                repository.rules.first { list -> list.any { it.id == custom.id && !it.enabled } }
                repository.deleteRule(custom.id)
                repository.rules.first { it.size == 4 }
                repository.clearHistory()
                repository.history.first { it.isEmpty() }
                assertThrows(IllegalArgumentException::class.java) { RuleValidator.validate("[") }
            } finally { repository.close() }
        }
    }
}
