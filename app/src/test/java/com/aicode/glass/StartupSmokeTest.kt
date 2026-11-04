package com.aicode.glass

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.glass.core.data.AppLocale
import com.aicode.glass.core.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lets the startup tests persist a language before the activity is created. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleSeedEntryPoint {
    fun settingsRepository(): SettingsRepository
}

/** Writes both the synchronous mirror and DataStore so the first frame is deterministic. */
private fun seedLanguage(language: String) = TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val settings = EntryPointAccessors.fromApplication(app, LocaleSeedEntryPoint::class.java)
                .settingsRepository()
            runBlocking { settings.setLanguage(language) }
            base.evaluate()
        }
    }
}

/**
 * Launch regression: a freshly installed build must reach the chat shell instead of force-closing
 * during `Application.onCreate`, Hilt graph creation, Room/DataStore opening or first composition.
 *
 * These tests run the real Hilt graph on the JVM (Robolectric), so startup I/O failures, missing
 * classes and Compose exceptions surface as a test failure with a stack trace.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [33], qualifiers = "en")
class StartupSmokeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val seed = seedLanguage(AppLocale.ENGLISH)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToChatShellWithoutCrashing() {
        hilt.inject()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }
}

/**
 * Regression for the on-device crash
 * `IllegalStateException: Expected an activity context for creating a HiltViewModelFactory but
 * instead found: android.app.ContextImpl` (HiltNavBackStackEntry). The activity used to replace
 * `LocalContext` with a `createConfigurationContext` result, which broke every `hiltViewModel()`
 * inside the NavHost. The stored language is applied to the activity itself instead, so the Persian
 * resources must still be visible while the Hilt view models resolve.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [33], qualifiers = "en")
class StartupLocaleSmokeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val seed = seedLanguage(AppLocale.PERSIAN)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithStoredPersianLocaleAndHiltViewModels() {
        hilt.inject()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("گفتگو").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("گفتگو").assertIsDisplayed()
    }
}
