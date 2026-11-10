package com.vertos.promoblocker

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
class CloudRulesTest {
    @Test fun migrationPreservesExistingRulesAndHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE blocked_calls (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, number TEXT, reason TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_blocked_calls_timestamp ON blocked_calls (timestamp)")
            old.execSQL("CREATE TABLE regex_rules (id TEXT PRIMARY KEY NOT NULL, expression TEXT NOT NULL, enabled INTEGER NOT NULL, builtIn INTEGER NOT NULL, transactional INTEGER NOT NULL)")
            old.execSQL("INSERT INTO regex_rules VALUES ('custom','^123$',0,0,0)")
            old.execSQL("INSERT INTO blocked_calls (number,reason,timestamp) VALUES ('123','old rule',10)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, BlockerDatabase::class.java, file.name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val rule = db.dao().observeRules().first().single()
            assertEquals("^123$", rule.expression)
            assertFalse(rule.enabled)
            assertNull(rule.description)
            assertEquals("123", db.dao().observeHistory().first().single().number)
        } finally {
            db.close()
            context.deleteDatabase(file.name)
        }
    }

    @Test fun syncActivatesApprovedRulesAndPreservesLocalDataWhenOffline() = runBlocking<Unit> {
        withTimeout(10000) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val sample = """{"version":1,"rules":[
                {"id":1,"regex":"^555[0-9]+$","description":"Example campaign"},
                {"id":2,"regex":"^222$","description":"Duplicate of local rule"}
            ]}"""
            var response = CloudRuleFeed.Feed(CloudRuleFeed.parse(sample), "v1")
            var offline = false
            val repository = BlockerRepository(context) {
                if (offline) throw IOException("Offline")
                response
            }
            try {
                repository.awaitReady()
                repository.setFlag("enabled", true)
                repository.settings.first { it.enabled }
                repository.addRule("^222$")
                repository.refreshCloudRules()
                val cloud = repository.rules.first { rules -> rules.any { it.isCloud } }.filter { it.isCloud }
                assertEquals("Example campaign", cloud.single().description)
                suspend fun awaitEvaluation(expected: String?) {
                    while (NumberEvaluator.evaluate("555123", false, repository.snapshot) != expected) delay(10)
                }
                awaitEvaluation("Example campaign")
                offline = true
                try {
                    repository.refreshCloudRules()
                    fail("Expected offline sync to fail")
                } catch (_: IOException) {
                    assertEquals("Example campaign", NumberEvaluator.evaluate("555123", false, repository.snapshot))
                }
                offline = false
                repository.toggleRule(cloud.single().id, false)
                repository.refreshCloudRules()
                awaitEvaluation(null)
                assertFalse(repository.rules.first { rules -> rules.any { it.isCloud && !it.enabled } }
                    .single { it.isCloud }.enabled)
                response = CloudRuleFeed.Feed(emptyList(), "v2")
                repository.refreshCloudRules()
                val remaining = repository.rules.first { rules -> rules.none { it.isCloud } }
                assertTrue(remaining.any { it.expression == "^222$" })
            } finally {
                withContext(NonCancellable) { repository.close() }
            }
        }
    }
}
