package com.aistudio.snoredetector.afkwd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.snoredetector.afkwd.service.MonitorControl
import com.aistudio.snoredetector.afkwd.ui.AutomationSettingsCard
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationSettingsCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun intentDetailsAppearOnlyAfterOptingIn() {
        var enabled by mutableStateOf(false)
        composeTestRule.setContent {
            MyApplicationTheme {
                AutomationSettingsCard(enabled = enabled, onEnabledChange = { enabled = it })
            }
        }

        composeTestRule.onNodeWithTag("automation_control_details").assertDoesNotExist()

        composeTestRule.onNodeWithTag("automation_control_switch").performClick()

        composeTestRule.onNodeWithTag("automation_control_details").assertIsDisplayed()
        composeTestRule.onNodeWithText(MonitorControl.ACTION_START_MONITORING).assertIsDisplayed()
        composeTestRule.onNodeWithText(MonitorControl.ACTION_STOP_MONITORING).assertIsDisplayed()
    }
}
