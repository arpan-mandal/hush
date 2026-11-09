package com.vertos.promoblocker

import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

class NumberEvaluatorTest {
    private val defaults = ScreeningSnapshot()
    @Test fun promotionalFormatsAreBlocked() {
        listOf("+911408823412", "911408823412", "01408823412", "1408823412").forEach {
            assertNotNull(it, NumberEvaluator.evaluate(it, false, defaults))
        }
    }
    @Test fun residentialNumbersPass() {
        listOf("+919876543210", "09876543210", "140", "91140", "x1401234", "140123x").forEach {
            assertNull(it, NumberEvaluator.evaluate(it, false, defaults))
        }
    }
    @Test fun transactionalSeriesIsOptIn() {
        listOf("+911601234567", "911601234567", "01601234567", "1601234567").forEach {
            assertNull(NumberEvaluator.evaluate(it, false, defaults))
            assertNotNull(NumberEvaluator.evaluate(it, false, defaults.copy(settings = ScreeningSettings(blockTransactional = true))))
        }
    }
    @Test fun killSwitchOverridesEveryCondition() {
        val off = defaults.copy(settings = ScreeningSettings(enabled = false, blockUnknown = true))
        assertNull(NumberEvaluator.evaluate("14012345", true, off))
        assertNull(NumberEvaluator.evaluate(null, true, off))
    }
    @Test fun spoofingCanBeDisabled() {
        assertEquals(NumberEvaluator.SPOOFED, NumberEvaluator.evaluate("+919876543210", true, defaults))
        assertNull(NumberEvaluator.evaluate("+919876543210", true, defaults.copy(settings = ScreeningSettings(blockSpoofed = false))))
    }
    @Test fun unknownAndEmptyAreConfigurable() {
        listOf(null, "", " ").forEach {
            assertNull(NumberEvaluator.evaluate(it, false, defaults))
            assertEquals(NumberEvaluator.UNKNOWN, NumberEvaluator.evaluate(it, false, defaults.copy(settings = ScreeningSettings(blockUnknown = true))))
        }
    }
    @Test fun dynamicRulesAndDisabledRules() {
        val custom = CompiledRule("custom", "^\\+91999[0-9]+$")
        assertEquals("custom", NumberEvaluator.evaluate("+919991234567", false, defaults.copy(rules = listOf(custom))))
        assertNull(NumberEvaluator.evaluate("+919991234567", false, defaults.copy(rules = emptyList())))
    }
    @Test fun syntaxValidationRejectsInvalidAndUnsupportedPatterns() {
        listOf("", "[", "a".repeat(257), "(?=140)140", "(1)\\1").forEach {
            assertThrows(IllegalArgumentException::class.java) { RuleValidator.validate(it) }
        }
        RuleValidator.validate("^0?140\\d+$")
    }
    @Test fun oversizedNumberFailsOpen() {
        assertNull(NumberEvaluator.evaluate("140" + "1".repeat(129), false, defaults))
    }
    @Test fun thousandEvaluationsAverageUnderOneMillisecond() {
        val numbers = arrayOf("+911408823412", "911408823412", "01408823412", "1408823412", "+919876543210", "09876543210")
        repeat(2000) { NumberEvaluator.evaluate(numbers[it % numbers.size], false, defaults) }
        val elapsed = measureNanoTime { repeat(1000) { NumberEvaluator.evaluate(numbers[it % numbers.size], false, defaults) } }
        println("Default regex average: ${elapsed / 1000.0 / 1_000_000} ms (1000 evaluations)")
        assertTrue("Average was ${elapsed / 1000} ns", elapsed / 1000 < 1_000_000)
    }
    @Test fun maximumRuleSetStaysWithinFiveMillisecondEvaluationBudget() {
        val full = defaults.copy(rules = List(100) { CompiledRule("custom-$it", "^\\+91${2000000000L + it}$") })
        repeat(1000) { NumberEvaluator.evaluate("+919876543210", false, full) }
        val elapsed = measureNanoTime { repeat(1000) { NumberEvaluator.evaluate("+919876543210", false, full) } }
        println("100-rule regex average: ${elapsed / 1000.0 / 1_000_000} ms")
        assertTrue(elapsed / 1000 < 5_000_000)
    }
    @Test fun nestedQuantifiersDoNotBacktrackCatastrophically() {
        val nested = defaults.copy(rules = listOf(CompiledRule("nested", "^(1+)+$")))
        val elapsed = measureNanoTime { repeat(1000) { assertNull(NumberEvaluator.evaluate("1".repeat(120) + "x", false, nested)) } }
        assertTrue(elapsed / 1000 < 5_000_000)
    }
}
