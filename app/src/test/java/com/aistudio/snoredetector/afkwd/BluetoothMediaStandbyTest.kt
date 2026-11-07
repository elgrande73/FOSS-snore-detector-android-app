package com.aistudio.snoredetector.afkwd

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.service.SnoreDetectionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothMediaStandbyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothMic = AudioDeviceInfoBuilder.newBuilder()
        .setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        .build()
        .also(::markAsInput)
    private var service: ServiceController<SnoreDetectionService>? = null

    @Before
    fun audibleCaptureSource() {
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(
                audioData: ShortArray,
                offsetInShorts: Int,
                shortsToRead: Int,
                isBlocking: Boolean
            ): Int {
                Thread.sleep(5)
                return shortsToRead
            }
        })
    }

    @After
    fun stopSession() {
        service?.destroy()
    }

    @Test
    fun routeIsYieldedOnlyWhenBluetoothMicSamplesWouldBeDiscarded() {
        assertTrue(yield(bluetooth = true, pause = true, media = true))
        assertFalse(yield(bluetooth = true, pause = false, media = true))
        assertFalse(yield(bluetooth = true, pause = true, media = false))
        assertFalse(yield(bluetooth = false, pause = true, media = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun standbyOutlastsAGapBetweenTracks() = runTest {
        var standingBy = false
        var ended = false
        launch {
            SnoreDetectionService.awaitMediaStandbyEnd(pollMillis = 100, graceMillis = 1000) { standingBy }
            ended = true
        }

        advanceTimeBy(500)
        standingBy = true
        advanceTimeBy(200)
        standingBy = false
        advanceTimeBy(800)
        runCurrent()
        assertFalse("A track starting inside the grace period restarts it", ended)

        advanceTimeBy(200)
        runCurrent()
        assertTrue(ended)
    }

    @Test
    fun sessionStartedDuringPlaybackStaysOffTheBluetoothLinkUntilMediaStops() {
        val shadow = shadowOf(audioManager)
        shadow.setIsMusicActive(true)

        startSession()

        assertRouteStaysDownFor(SETTLE_MS)

        shadow.setIsMusicActive(false)

        waitUntil("the route to come up after the grace period") { isRouteHeld() }
    }

    @Test
    fun routeIsReleasedDuringPlaybackAndRestoredAfterItWithTheTimelineIntact() {
        val shadow = shadowOf(audioManager)
        val sessionStart = System.currentTimeMillis()
        startSession()
        waitUntil("the route to come up") { isRouteHeld() }
        waitUntil("a first timeline point") {
            SnoreDetectionService.currentSessionData.value.any { it.timestamp >= sessionStart }
        }
        val firstPoint = SnoreDetectionService.currentSessionData.value.first()

        shadow.setIsMusicActive(true)
        shadow.setActivePlaybackConfigurationsFor(
            listOf(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            ),
            true
        )
        ShadowLooper.idleMainLooper()

        waitUntil("the route to be released") { !isRouteHeld() }
        assertRouteStaysDownFor(SETTLE_MS)

        shadow.setIsMusicActive(false)
        shadow.setActivePlaybackConfigurationsFor(emptyList(), true)
        ShadowLooper.idleMainLooper()

        waitUntil("the route to be restored") { isRouteHeld() }
        assertEquals(firstPoint, SnoreDetectionService.currentSessionData.value.first())
    }

    @Test
    fun routeStaysUpDuringPlaybackWhenThePauseSettingIsOff() {
        shadowOf(audioManager).setIsMusicActive(true)

        startSession(ignoreDuringMediaPlayback = false)

        waitUntil("the route to come up") { isRouteHeld() }
    }

    private fun yield(bluetooth: Boolean, pause: Boolean, media: Boolean) =
        SnoreDetectionService.shouldYieldBluetoothRoute(bluetooth, pause, media)

    private fun isRouteHeld() = audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun startSession(ignoreDuringMediaPlayback: Boolean = true) {
        val shadow = shadowOf(audioManager)
        shadow.setInputDevices(listOf(bluetoothMic))
        shadow.setAvailableCommunicationDevices(listOf(bluetoothMic))
        val intent = Intent(context, SnoreDetectionService::class.java)
            .putExtra("audioInputId", bluetoothMic.id)
            .putExtra("audioInputName", "Sleep mask")
            .putExtra("ignoreDuringMediaPlayback", ignoreDuringMediaPlayback)
        service = Robolectric.buildService(SnoreDetectionService::class.java, intent)
            .create()
            .startCommand(0, 1)
    }

    /**
     * AudioDeviceInfoBuilder leaves the port role unset, which makes the device an output, and its
     * capability arrays null, which AudioInputManager.logDeviceDetails cannot read.
     */
    private fun markAsInput(device: AudioDeviceInfo) {
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        val audioPort = Class.forName("android.media.AudioPort")
        ReflectionHelpers.setField(audioPort, port, "mRole", AUDIO_PORT_ROLE_SOURCE)
        audioPort.declaredFields.filter { it.type == IntArray::class.java }.forEach {
            ReflectionHelpers.setField(audioPort, port, it.name, IntArray(0))
        }
    }

    private fun assertRouteStaysDownFor(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            assertFalse("Route came up during playback", isRouteHeld())
            Thread.sleep(20)
        }
    }

    /** Capture runs on Dispatchers.Default, so these assertions wait on real time. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_LIMIT_MS
        while (!condition()) {
            assertTrue("Timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
        assertEquals(null, SnoreDetectionService.serviceError.value)
    }

    private companion object {
        const val WAIT_LIMIT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        const val AUDIO_PORT_ROLE_SOURCE = 1
    }
}
