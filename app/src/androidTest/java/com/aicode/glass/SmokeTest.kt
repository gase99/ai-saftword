package com.aicode.glass

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchAndOpenSettings() {
        compose.waitUntil(10000) { try { compose.onNodeWithText("Settings").fetchSemanticsNode(); true } catch(_: Throwable) { false } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("AI connection").assertIsDisplayed()
    }
}
