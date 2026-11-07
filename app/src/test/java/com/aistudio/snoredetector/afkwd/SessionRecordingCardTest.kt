package com.aistudio.snoredetector.afkwd

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aistudio.snoredetector.afkwd.data.SessionRecording
import com.aistudio.snoredetector.afkwd.data.SessionRecordingStore
import com.aistudio.snoredetector.afkwd.ui.SessionRecordingCardContent
import com.aistudio.snoredetector.afkwd.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecordingCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val recording = SessionRecording(
        file = File("session_1771372800000.wav"),
        startTimeMillis = 1771372800000L,
        sizeBytes = 8 * SessionRecordingStore.BYTES_PER_HOUR
    )

    private var toggled: Boolean? = null
    private var saved: SessionRecording? = null
    private var deleted: SessionRecording? = null

    private fun setCard(
        enabled: Boolean = false,
        isMonitoring: Boolean = false,
        exportProgress: String? = null
    ) {
        composeTestRule.setContent {
            MyApplicationTheme {
                SessionRecordingCardContent(
                    enabled = enabled,
                    isMonitoring = isMonitoring,
                    recordings = listOf(recording),
                    freeBytes = 12_300_000_000L,
                    exportProgress = exportProgress,
                    onEnabledChange = { toggled = it },
                    onSave = { saved = it },
                    onDelete = { deleted = it }
                )
            }
        }
    }

    @Test
    fun showsExpectedSizeAndFreeSpaceBeforeOptingIn() {
        setCard()

        composeTestRule.onNodeWithTag("record_full_session_switch").assertIsOff()
        composeTestRule.onNodeWithTag("session_storage_summary").assertTextContains("115 MB per hour", substring = true)
        composeTestRule.onNodeWithTag("session_storage_summary").assertTextContains("921 MB for 8 hours", substring = true)
        composeTestRule.onNodeWithTag("session_storage_summary").assertTextContains("Free now: 12.3 GB", substring = true)

        composeTestRule.onNodeWithTag("record_full_session_switch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun listsRecordingWithDurationAndSizeAndSaves() {
        setCard()

        composeTestRule.onNodeWithText("7 h 59 min • 921 MB").assertExists()
        composeTestRule.onNodeWithTag("save_session_recording_1771372800000").performClick()
        assertEquals(recording, saved)
    }

    @Test
    fun deleteAsksForConfirmationFirst() {
        setCard()

        composeTestRule.onNodeWithTag("delete_session_recording_1771372800000").performClick()
        assertNull(deleted)

        composeTestRule.onNodeWithTag("confirm_delete_session_recording").performClick()
        assertEquals(recording, deleted)
    }

    @Test
    fun switchAndActionsAreLockedWhileMonitoring() {
        setCard(enabled = true, isMonitoring = true)

        composeTestRule.onNodeWithTag("record_full_session_switch").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_session_recording_1771372800000").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("delete_session_recording_1771372800000").assertIsNotEnabled()
    }

    @Test
    fun actionsAreLockedDuringAnExportButTheSwitchIsNot() {
        setCard(exportProgress = "Exporting audio recording...")

        composeTestRule.onNodeWithTag("record_full_session_switch").assertIsEnabled()
        composeTestRule.onNodeWithTag("save_session_recording_1771372800000").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Exporting audio recording...").assertExists()
    }
}
