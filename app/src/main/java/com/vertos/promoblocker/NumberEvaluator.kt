package com.vertos.promoblocker

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

// RE2/J prevents catastrophic backtracking in user patterns. No compilation or storage I/O
// occurs in evaluate(). Reused matchers are isolated to each evaluation thread.
class CompiledRule(val id: String, val expression: String, val transactional: Boolean = false) {
    private val pattern = Pattern.compile(expression)
    private val matcher = ThreadLocal.withInitial { pattern.matcher("") }
    fun matches(number: String): Boolean {
        val local = checkNotNull(matcher.get())
        return try { local.reset(number).matches() } finally { local.reset("") }
    }
}
data class ScreeningSettings(
    val enabled: Boolean = true,
    val blockSpoofed: Boolean = true,
    val blockUnknown: Boolean = false,
    val blockTransactional: Boolean = false,
)
data class ScreeningSnapshot(
    val settings: ScreeningSettings = ScreeningSettings(),
    val rules: List<CompiledRule> = DefaultRules.rules,
)
object DefaultRules {
    internal const val SALES_WITH_COUNTRY_CODE = "TRAI promotional (international)"
    internal const val SALES_WITHOUT_COUNTRY_CODE = "TRAI promotional (local)"
    internal const val SERVICE_WITH_COUNTRY_CODE = "TRAI transactional (international)"
    internal const val SERVICE_WITHOUT_COUNTRY_CODE = "TRAI transactional (local)"

    val rules = listOf(
        CompiledRule(SALES_WITH_COUNTRY_CODE, "^\\+?91140\\d+$"),
        CompiledRule(SALES_WITHOUT_COUNTRY_CODE, "^0?140\\d+$"),
        CompiledRule(SERVICE_WITH_COUNTRY_CODE, "^\\+?91160\\d+$", true),
        CompiledRule(SERVICE_WITHOUT_COUNTRY_CODE, "^0?160\\d+$", true),
    )
}
object RuleValidator {
    const val MAX_RULES = 100
    const val MAX_LENGTH = 256
    fun validate(expression: String) {
        require(expression.isNotBlank()) { "Enter a number pattern." }
        require(expression.length <= MAX_LENGTH) { "Use $MAX_LENGTH characters or fewer." }
        try { Pattern.compile(expression) }
        catch (error: PatternSyntaxException) {
            throw IllegalArgumentException("Hush couldn’t read this pattern. Check it and try again.", error)
        }
    }
}
object NumberEvaluator {
    const val MAX_NUMBER_LENGTH = 128
    const val SPOOFED = "Carrier verification failed"
    const val UNKNOWN = "Unknown or private caller"
    // A null reason means allow. Matched rule identifiers are reused, avoiding result objects.
    fun evaluate(number: String?, verificationFailed: Boolean, snapshot: ScreeningSnapshot): String? {
        val settings = snapshot.settings
        if (!settings.enabled) return null
        if (verificationFailed && settings.blockSpoofed) return SPOOFED
        if (number.isNullOrBlank()) return if (settings.blockUnknown) UNKNOWN else null
        if (number.length > MAX_NUMBER_LENGTH) return null
        val rules = snapshot.rules
        var index = 0
        while (index < rules.size) {
            val rule = rules[index++]
            if ((!rule.transactional || settings.blockTransactional) && rule.matches(number)) return rule.id
        }
        return null
    }
}
