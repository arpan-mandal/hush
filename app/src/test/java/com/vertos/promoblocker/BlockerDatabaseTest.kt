package com.vertos.promoblocker

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BlockerDatabaseTest {
    @Test fun blockedCallsAreOrderedCountedAndCleared() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BlockerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.record(BlockedCallEntity(number = "140123", reason = "test", timestamp = 10))
            dao.record(BlockedCallEntity(number = null, reason = "private", timestamp = 20))
            assertEquals(2, dao.observeCount().first())
            val calls = dao.observeHistory().first()
            assertEquals(20L, calls.first().timestamp)
            assertNull(calls.first().number)
            dao.clearHistory()
            assertEquals(0, dao.observeCount().first())
            assertTrue(dao.observeHistory().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun builtInSeedingPreservesTogglesAndCustomRulesCanBeDeleted() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BlockerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            val builtIn = RegexRuleEntity("builtin", "140.*", builtIn = true)
            dao.seed(listOf(builtIn))
            dao.toggleRule("builtin", false)
            dao.seed(listOf(builtIn))
            assertFalse(dao.observeRules().first().single().enabled)
            dao.deleteRule("builtin")
            assertEquals(1, dao.observeRules().first().size)
            dao.insertRule(RegexRuleEntity("custom", "160.*"))
            assertEquals(1, dao.customRuleCount())
            dao.deleteRule("custom")
            assertEquals(0, dao.customRuleCount())
        } finally { db.close() }
    }
}
