package com.aistudio.snoredetector.afkwd

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for #10: on a 360dp-wide screen the title took the whole header row,
 * squeezing the "Always Active" badge to one character per line and making the card
 * a full screen tall.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ConfigureMethodCardLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun alwaysActiveBadgeStaysOnOneLineOnNarrowScreen() {
        composeTestRule.setContent {
            // Large system font (1.3x) reproduces the device's wider text rendering from the report.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            MyApplicationTheme {
                ConfigureMethodCard(
                    title = "1. Sound Volume (Amplitude dB)",
                    thresholdType = "Minimum",
                    comparisonSymbol = "≥",
                    description = "Mandatory acoustic criterion (Value ≥ Threshold).",
                    isActive = true,
                    onActiveChange = {},
                    value = 55f,
                    onValueChange = {},
                    valueRange = 20f..100f,
                    labelFormatter = { "${it.toInt()} dB" },
                    defaultValueLabel = "55 dB",
                    isToggleable = false,
                    testTag = "card"
                )
            }
            }
        }

        val badge = composeTestRule.onNodeWithText("Always Active").getUnclippedBoundsInRoot()
        assertTrue(
            "Always Active badge wrapped: ${badge.width} x ${badge.height}",
            badge.width > badge.height * 2
        )

        val cardHeight = composeTestRule.onNodeWithTag("card").getUnclippedBoundsInRoot().height
        assertTrue("Card unexpectedly tall: height=$cardHeight", cardHeight < 400.dp)
    }
}
