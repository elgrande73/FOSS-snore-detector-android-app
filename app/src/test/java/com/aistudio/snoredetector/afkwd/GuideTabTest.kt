package com.aistudio.snoredetector.afkwd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.aistudio.snoredetector.afkwd.ui.GuideTab
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuideTabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testGuideTabRendersSuccessfully() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GuideTab()
            }
        }

        // Verify Guide Tab root and hero header are present
        composeTestRule.onNodeWithTag("guide_tab").assertIsDisplayed()
        composeTestRule.onNodeWithText("How to use Snore Detector").assertIsDisplayed()
        composeTestRule.onNodeWithText("Getting started").assertIsDisplayed()
    }
}
