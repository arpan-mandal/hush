package com.vertos.promoblocker

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "blocked_calls", indices = [Index("timestamp")])
data class BlockedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String?,
    val reason: String,
    val timestamp: Long,
)
@Entity(tableName = "regex_rules")
data class RegexRuleEntity(
    @PrimaryKey val id: String,
    val expression: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val transactional: Boolean = false,
    val description: String? = null,
) {
    val isCloud: Boolean get() = id.startsWith("cloud:")
}

@Entity(tableName = "call_feedback")
data class CallFeedbackEntity(@PrimaryKey val number: String, val reportPending: Boolean)

@Dao
interface BlockerDao {
    @Query("SELECT EXISTS(SELECT 1 FROM call_feedback WHERE number = :number)")
    suspend fun hasFeedback(number: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedback(feedback: CallFeedbackEntity): Long
    @Query("SELECT number FROM call_feedback WHERE reportPending = 1 ORDER BY number LIMIT 50")
    suspend fun pendingFeedback(): List<String>
    @Query("UPDATE call_feedback SET reportPending = 0 WHERE number = :number")
    suspend fun markFeedbackSent(number: String)
    @Query("SELECT id FROM regex_rules WHERE builtIn = 0 AND id NOT LIKE 'cloud:%' AND expression = :expression LIMIT 1")
    suspend fun localRuleId(expression: String): String?
    @Transaction
    suspend fun saveFeedback(number: String, rule: RegexRuleEntity?): Boolean {
        if (insertFeedback(CallFeedbackEntity(number, rule != null)) == -1L) return false
        if (rule != null) {
            val existing = localRuleId(rule.expression)
            if (existing == null) insertRule(rule) else toggleRule(existing, true)
        }
        return true
    }
    @Query("SELECT * FROM blocked_calls ORDER BY timestamp DESC, id DESC LIMIT 1000")
    fun observeHistory(): Flow<List<BlockedCallEntity>>
    @Query("SELECT COUNT(*) FROM blocked_calls") fun observeCount(): Flow<Int>
    @Insert suspend fun insertBlocked(call: BlockedCallEntity)
    @Query("DELETE FROM blocked_calls") suspend fun clearHistory()
    @Query("DELETE FROM blocked_calls WHERE id NOT IN (SELECT id FROM blocked_calls ORDER BY timestamp DESC, id DESC LIMIT 10000)")
    suspend fun trimHistory()
    @Transaction suspend fun record(call: BlockedCallEntity) { insertBlocked(call); trimHistory() }
    @Query("SELECT * FROM regex_rules ORDER BY builtIn DESC, id ASC")
    fun observeRules(): Flow<List<RegexRuleEntity>>
    @Query("SELECT COUNT(*) FROM regex_rules WHERE builtIn = 0 AND id NOT LIKE 'cloud:%' AND id NOT LIKE 'feedback:%'") suspend fun customRuleCount(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seed(rules: List<RegexRuleEntity>)
    @Insert suspend fun insertRule(rule: RegexRuleEntity)
    @Query("UPDATE regex_rules SET enabled = :enabled WHERE id = :id") suspend fun toggleRule(id: String, enabled: Boolean)
    @Query("DELETE FROM regex_rules WHERE id = :id AND builtIn = 0 AND id NOT LIKE 'cloud:%'") suspend fun deleteRule(id: String)
    @Query("SELECT expression FROM regex_rules WHERE id NOT LIKE 'cloud:%'")
    suspend fun localExpressions(): List<String>
    @Query("SELECT * FROM regex_rules WHERE id LIKE 'cloud:%'")
    suspend fun cloudRules(): List<RegexRuleEntity>
    @Query("DELETE FROM regex_rules WHERE id LIKE 'cloud:%' AND id NOT IN (:ids)")
    suspend fun pruneCloudRules(ids: List<String>)
    @Upsert suspend fun upsertCloudRules(rules: List<RegexRuleEntity>)

    @Transaction
    suspend fun replaceCloudRules(incoming: List<RegexRuleEntity>) {
        val local = localExpressions().toMutableSet()
        DefaultRules.rules.mapTo(local) { it.expression }
        val existing = cloudRules().associateBy { it.id }
        val current = incoming.filterNot { it.expression in local }.map { rule ->
            rule.copy(enabled = existing[rule.id]?.enabled ?: true)
        }
        val changed = current.filter { existing[it.id] != it }
        if (changed.isNotEmpty()) upsertCloudRules(changed)
        val ids = current.map { it.id }
        if (existing.keys.any { it !in ids }) pruneCloudRules(ids)
    }
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE regex_rules ADD COLUMN description TEXT")
    }
}
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS call_feedback (number TEXT NOT NULL PRIMARY KEY, reportPending INTEGER NOT NULL)")
    }
}
@Database(entities = [BlockedCallEntity::class, RegexRuleEntity::class, CallFeedbackEntity::class], version = 3, exportSchema = true)
abstract class BlockerDatabase : RoomDatabase() { abstract fun dao(): BlockerDao }
