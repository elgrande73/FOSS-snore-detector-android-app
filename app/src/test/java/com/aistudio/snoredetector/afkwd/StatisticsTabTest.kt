package com.aistudio.snoredetector.afkwd

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aistudio.snoredetector.afkwd.data.MonitoringSession
import com.aistudio.snoredetector.afkwd.data.NightStats
import com.aistudio.snoredetector.afkwd.data.NightSummary
import com.aistudio.snoredetector.afkwd.ui.StatisticsContent
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsTabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val utc = TimeZone.getTimeZone("UTC")
    private val today = 20_000L

    private fun night(daysAgo: Long, monitoredMinutes: Double, snoreMinutes: Double = 60.0, backfilled: Boolean = false): MonitoringSession {
        val start = NightStats.epochDayToUtcMillis(today - daysAgo) + 22 * 3_600_000L
        return MonitoringSession(
            startTime = start,
            endTime = start + 60_000L,
            monitoredMinutes = monitoredMinutes,
            snoreEventCount = 120,
            totalSnoreMinutes = snoreMinutes,
            longestEpisodeSeconds = 150.0,
            avgPeakDb = 65f,
            maxPeakDb = 72f,
            backgroundDb = 50f,
            isBackfilled = backfilled
        )
    }

    private fun show(nights: List<NightSummary>, onExport: () -> Unit = {}) {
        composeTestRule.setContent {
            MyApplicationTheme {
                StatisticsContent(nights = nights, today = today, onExportCsv = onExport)
            }
        }
    }

    private fun chartsNights(count: Int) = SemanticsMatcher("charts $count nights") { node ->
        node.config[SemanticsProperties.ContentDescription].single().contains(": $count nights")
    }

    @Test
    fun showsLastNightScoreAndDisclaimer() {
        show(NightStats.summarize(listOf(night(1, 480.0)), utc))

        composeTestRule.onNodeWithTag("last_night_score").assertTextEquals("28")
        composeTestRule.onNodeWithText("7.5 min per hour").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("It is not a medical diagnosis and cannot detect sleep apnea", substring = true)
            .assertExists()
    }

    @Test
    fun formulaPanelOpensWithTheNightsValues() {
        show(NightStats.summarize(listOf(night(1, 480.0)), utc))

        composeTestRule.onNodeWithText("15.0 of 60 points").assertDoesNotExist()
        composeTestRule.onNodeWithText("How is this calculated?").performClick()
        composeTestRule.onNodeWithText("15.0 of 60 points").assertExists()
    }

    @Test
    fun shortNightIsMarkedAndOnlyChartedOnRequest() {
        val nights = NightStats.summarize(listOf(night(2, 480.0), night(1, 30.0)), utc)
        show(nights)

        composeTestRule.onNodeWithTag("score_trend_chart")
            .assert(chartsNights(1))
        // Once on the last-night card and once in the list of recent nights
        assertEquals(2, composeTestRule.onAllNodes(hasText("Too short for a reliable score")).fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag("include_short_nights").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("score_trend_chart")
            .assert(chartsNights(2))
    }

    @Test
    fun periodChipNarrowsTheChart() {
        show(NightStats.summarize(listOf(night(20, 480.0), night(1, 480.0)), utc))

        composeTestRule.onNodeWithTag("score_trend_chart")
            .assert(chartsNights(2))

        // The chips sit in their own horizontal scroller, so bring them on screen through the page
        composeTestRule.onNodeWithTag("score_trend_chart").performScrollTo()
        composeTestRule.onNodeWithText("Week").performClick()

        composeTestRule.onNodeWithTag("score_trend_chart")
            .assert(chartsNights(1))
    }

    @Test
    fun comparisonShowsAbsoluteAndPercentChange() {
        show(
            NightStats.summarize(
                listOf(night(40, 480.0, snoreMinutes = 120.0), night(1, 480.0, snoreMinutes = 60.0)),
                utc
            )
        )

        // Scores: 43 before (30 + 5 + 2.5 + 5), 28 after (15 + 5 + 2.5 + 5)
        composeTestRule.onNodeWithText("−15.0 points (−34.9%)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backfilledNightExplainsWhyThereIsNoScore() {
        show(NightStats.summarize(listOf(night(1, 0.0, backfilled = true)), utc))

        composeTestRule.onNodeWithTag("last_night_score").assertTextEquals("–")
        assertEquals(
            2,
            composeTestRule.onAllNodes(hasText("No score: monitored time was not recorded")).fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithText("How is this calculated?").assertDoesNotExist()
    }

    @Test
    fun emptyHistoryShowsAHintInsteadOfCharts() {
        show(emptyList())

        composeTestRule.onNodeWithText("No monitoring sessions saved yet", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("score_trend_chart").assertDoesNotExist()
    }

    @Test
    fun exportButtonCallsBack() {
        var exported = 0
        show(NightStats.summarize(listOf(night(1, 480.0)), utc)) { exported++ }

        composeTestRule.onNodeWithText("Export nightly summaries (CSV)").performScrollTo().performClick()

        assertEquals(1, exported)
    }
}
