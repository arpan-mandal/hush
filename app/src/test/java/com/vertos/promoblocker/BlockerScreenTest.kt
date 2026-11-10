package com.vertos.promoblocker

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlockerScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var hostView: View

    private fun showScreen(
        roleHeld: Boolean = true,
        initialTheme: AppThemeMode = AppThemeMode.LIGHT,
        extraRules: List<RegexRuleEntity> = emptyList(),
        feedbackInitiallyEnabled: Boolean = true,
        onRoleRequest: () -> Unit = {},
    ) {
        compose.setContent {
            hostView = LocalView.current
            var theme by remember { mutableStateOf(initialTheme) }
            var feedbackEnabled by remember { mutableStateOf(feedbackInitiallyEnabled) }
            val settings = ScreeningSettings()
            val fixtureRules = DefaultRules.rules.map {
                RegexRuleEntity(it.id, it.expression, builtIn = true, transactional = it.transactional)
            }
            val state = BlockerUiState(
                settings = settings, rules = fixtureRules + extraRules, history = emptyList(), count = 0,
                storageError = null, initialized = true, roleHeld = roleHeld, roleAvailable = true, theme = theme,
            )
            PromoTheme(theme) {
                BlockerScreen(state, BlockerActions(
                    setFlag = { _, _ -> }, setTheme = { theme = it }, requestRole = onRoleRequest,
                    addRule = { RuleValidator.validate(it) }, toggleRule = { _, _ -> },
                    deleteRule = {}, clearHistory = {},
                    testNumber = { NumberEvaluator.evaluate(it, false, ScreeningSnapshot(settings)) },
                    enableFeedback = if (feedbackEnabled) null else ({ feedbackEnabled = true }),
                ), remember { SnackbarHostState() })
            }
        }
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.runOnIdle {
            val directory = File(checkNotNull(System.getProperty("promo.ui.output")))
            directory.mkdirs()
            val view = if (dialog) checkNotNull(ShadowDialog.getLatestDialog().window).decorView else hostView.rootView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun themeDropdownAndScreeningPreferencesLiveInTheirOwnTabs() {
        showScreen()
        compose.onNodeWithText("Call blocking is on").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("What to block").assertDoesNotExist()
        compose.onNodeWithText("Dark").assertDoesNotExist()
        compose.onNodeWithText("Pure black").assertDoesNotExist()
        val appearance = compose.onNodeWithText("Light")
        val selectorBounds = appearance.getUnclippedBoundsInRoot()
        val selectorWidth = selectorBounds.right - selectorBounds.left
        appearance.performClick()
        compose.onNodeWithText("Dark").assertWidthIsEqualTo(selectorWidth).performClick()
        compose.onNodeWithText("Pure black").assertDoesNotExist()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Pure black").performClick()
        compose.onNodeWithText("Pure black").assertIsDisplayed()
        capture("settings-compact")
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        capture("home-compact-oled")
        compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
        compose.onNodeWithText("What to block").assertIsDisplayed()
        compose.onNodeWithText("History", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No blocked calls yet").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Pure black").performClick()
        compose.onNode(hasText("Pure black") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("Light").performClick().assertIsDisplayed()
    }
    @Test fun feedbackMovesFromHomeToSettingsAfterPermissionIsGranted() {
        showScreen(feedbackInitiallyEnabled = false)
        val page = compose.onNode(hasScrollAction())
        val feedback = compose.onNodeWithText("After-call feedback")

        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        page.performScrollToNode(hasText("Donate"))
        feedback.assertDoesNotExist()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        page.performScrollToNode(hasText("Allow call feedback"))
        compose.onNodeWithText("Allow call feedback").performClick()
        feedback.assertDoesNotExist()

        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        page.performScrollToIndex(0)
        page.performScrollToNode(hasText("After-call feedback"))
        feedback.assertIsDisplayed()
        compose.onNodeWithText("Allow call feedback").assertDoesNotExist()
        page.performScrollToNode(hasText("Donate"))
        val donate = compose.onNodeWithText("Donate").assertIsDisplayed()
        assertTrue(donate.getUnclippedBoundsInRoot().top > feedback.getUnclippedBoundsInRoot().top)
    }
    @Test fun displayRulesOpensASeparateTableWithoutBuiltInRules() {
        showScreen(extraRules = listOf(
            RegexRuleEntity("local-test", "^1234567$"),
            RegexRuleEntity("cloud:42", "^2345678$", enabled = false, description = "Community test"),
        ))
        compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
        compose.onNodeWithText("^1234567$").assertDoesNotExist()
        compose.onNodeWithText("Display rules").performClick()
        compose.onNodeWithText("Regex").assertIsDisplayed()
        compose.onNodeWithText("^1234567$").assertIsDisplayed()
        compose.onNodeWithText("Community test").assertIsDisplayed()
        compose.onNodeWithText("ON").assertIsDisplayed()
        compose.onNodeWithText("OFF").assertIsDisplayed()
        compose.onNodeWithText("Built-in rules").assertDoesNotExist()
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("What to block").assertIsDisplayed()
    }
    @Test fun testerShowsActualRuleOutcomeAndClearsStaleResults() {
        showScreen(initialTheme = AppThemeMode.DARK_OLED)
        compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Phone number").assertDoesNotExist()
        compose.onNodeWithText("Try a number").performClick()
        compose.onNodeWithText("Phone number").performTextInput("1401234567")
        compose.onNodeWithText("Test number").performClick()
        compose.onNodeWithText("Hush would block this call").assertIsDisplayed()
        compose.onNodeWithText("140 sales calls (without country code)").assertIsDisplayed()
        capture("number-tester-declined", dialog = true)
        compose.onNodeWithText("Phone number").performTextReplacement("+919876543210")
        compose.onNodeWithText("Hush would block this call").assertDoesNotExist()
        compose.onNodeWithText("Test number").performClick()
        compose.onNodeWithText("Hush would let this call through").assertIsDisplayed()
        capture("number-tester-allowed", dialog = true)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Phone number").assertDoesNotExist()
    }
    @Test fun invalidRegexStaysInEditorWithInlineFeedback() {
        showScreen()
        compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add rule", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Number pattern").performTextInput("[")
        compose.onNodeWithText("Save rule").performClick()
        compose.onNodeWithText("Hush couldn’t read this pattern", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add a rule").assertIsDisplayed()
        capture("rule-editor-light", dialog = true)
    }
    @Test fun roleRequestExplainsReplacementBeforeOpeningAndroid() {
        var requests = 0
        showScreen(roleHeld = false, onRoleRequest = { requests++ })
        compose.onNodeWithText("Turn on call blocking").performClick()
        compose.onNodeWithText("Android lets you choose one caller ID", substring = true).assertIsDisplayed()
        assertEquals(0, requests)
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, requests)
    }
}
