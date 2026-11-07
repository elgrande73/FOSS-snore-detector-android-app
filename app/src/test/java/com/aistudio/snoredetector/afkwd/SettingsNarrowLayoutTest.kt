package com.aistudio.snoredetector.afkwd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for #10: on a 360dp-wide screen with a large font, a badge sharing a row with
 * a title got squeezed into a narrow column, one word or letter per line, and the card grew tall.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SettingsNarrowLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setLargeFontContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MyApplicationTheme { content() }
            }
        }
    }

    private fun assertNotSqueezed(text: String) {
        val bounds = composeTestRule.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("\"$text\" squeezed: ${bounds.width} x ${bounds.height}", bounds.width > bounds.height)
    }

    @Test
    fun sectionHeaderBadgeIsNotSqueezed() {
        setLargeFontContent {
            SettingsSectionHeader(
                title = "General & Additional Settings",
                subtitle = "Audio input hardware, recordings, notifications, theming, and reset",
                icon = Icons.Default.Settings,
                expanded = false,
                onToggle = {},
                badgeText = "Hardware & Preferences",
                testTag = "header"
            )
        }

        assertNotSqueezed("Hardware & Preferences")
        val height = composeTestRule.onNodeWithTag("header").getUnclippedBoundsInRoot().height
        assertTrue("Header unexpectedly tall: $height", height < 200.dp)
    }

    @Test
    fun settingTitleWithDefaultLabelIsNotSqueezed() {
        setLargeFontContent {
            // About the width the title gets on a 360dp screen, next to the switch.
            Box(Modifier.width(260.dp)) {
                SettingTitleWithDefault(title = "Pause Triggers During Media Playback", defaultLabel = "(Default: Enabled)")
            }
        }

        assertNotSqueezed("(Default: Enabled)")
    }

    @Test
    fun formulaCardBadgeIsNotSqueezed() {
        setLargeFontContent {
            DspFormulaCard(
                rmsDbThreshold = 55f,
                useZcr = true,
                zcrThreshold = 0.15f,
                useBandEnergy = true,
                bandEnergyThreshold = 0.015f,
                useLowFreqRatio = true,
                lowFreqRatioThreshold = 0.6f,
                minDurationSeconds = 1f
            )
        }

        assertNotSqueezed("Volume (Mandatory) + 3 of 3 Optional Filters")
    }

    @Test
    fun methodCardThresholdBadgeIsNotSqueezed() {
        setLargeFontContent {
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
                defaultValueLabel = "55 dB (Always Active)",
                isToggleable = false,
                testTag = "card"
            )
        }

        assertNotSqueezed("Mandatory (Minimum: Value ≥ Threshold)")
    }
}
