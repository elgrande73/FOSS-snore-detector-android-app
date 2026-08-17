package com.aistudio.snoredetector.afkwd

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import com.aistudio.snoredetector.afkwd.ui.theme.DarkColorScheme
import com.aistudio.snoredetector.afkwd.ui.theme.LightColorScheme
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLightThemeColorScheme() {
        var primaryColor: androidx.compose.ui.graphics.Color? = null
        var backgroundColor: androidx.compose.ui.graphics.Color? = null

        composeTestRule.setContent {
            MyApplicationTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                primaryColor = MaterialTheme.colorScheme.primary
                backgroundColor = MaterialTheme.colorScheme.background
                Text("Light Theme Test")
            }
        }

        assertNotNull(primaryColor)
        assertEquals(LightColorScheme.primary, primaryColor)
        assertEquals(LightColorScheme.background, backgroundColor)
    }

    @Test
    fun testDarkThemeColorScheme() {
        var primaryColor: androidx.compose.ui.graphics.Color? = null
        var backgroundColor: androidx.compose.ui.graphics.Color? = null

        composeTestRule.setContent {
            MyApplicationTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                primaryColor = MaterialTheme.colorScheme.primary
                backgroundColor = MaterialTheme.colorScheme.background
                Text("Dark Theme Test")
            }
        }

        assertNotNull(primaryColor)
        assertEquals(DarkColorScheme.primary, primaryColor)
        assertEquals(DarkColorScheme.background, backgroundColor)
    }
}
