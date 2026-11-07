package com.aistudio.snoredetector.afkwd

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.data.groupEventsIntoSessions
import com.aistudio.snoredetector.afkwd.ui.sessionItems
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistorySessionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val hour = 60L * 60L * 1000L
    private val firstNight = 1793919600000L
    private val secondNight = firstNight + 24 * hour

    private fun event(id: Int, timestamp: Long) = SnoreEvent(
        id = id,
        timestamp = timestamp,
        durationSeconds = 2.0,
        maxDb = 60f,
        maxRms = 0.04f,
        meanZcr = 0.08f,
        meanBandEnergy = 0.03f,
        meanLowFreqRatio = 0.7f
    )

    private fun showHistory() {
        val sessions = groupEventsIntoSessions(
            listOf(
                event(3, secondNight + hour),
                event(2, secondNight),
                event(1, firstNight)
            )
        )
        composeTestRule.setContent {
            MyApplicationTheme {
                LazyColumn {
                    sessionItems(sessions) { item ->
                        Text("event ${item.id}", modifier = Modifier.testTag("event_${item.id}"))
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) = composeTestRule.onNodeWithTag(tag).getBoundsInRoot()

    @Test
    fun eachSessionShowsItsHeaderAboveItsOwnEvents() {
        showHistory()

        val order = listOf(
            "session_header_$secondNight",
            "event_3",
            "event_2",
            "session_header_$firstNight",
            "event_1"
        )
        order.zipWithNext { above, below ->
            assertTrue("$above should be above $below", bounds(above).top < bounds(below).top)
        }
    }

    @Test
    fun headerShowsTheNumberOfIncidents() {
        showHistory()

        composeTestRule.onNodeWithText("2 incidents", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("1 incident", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun onlySessionsBelowTheFirstDrawADivider() {
        showHistory()

        val firstHeader = bounds("session_header_$secondNight").height
        val laterHeader = bounds("session_header_$firstNight").height
        assertTrue(laterHeader >= firstHeader + 22.dp)
    }
}
