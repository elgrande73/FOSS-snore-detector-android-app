package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.audio.SessionRecorder
import com.aistudio.snoredetector.afkwd.data.SessionRecording
import com.aistudio.snoredetector.afkwd.data.SessionRecordingStore
import com.aistudio.snoredetector.afkwd.dsp.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SessionRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val failures = CopyOnWriteArrayList<String>()

    private fun recorder(
        directory: File,
        queueCapacity: Int = 2048,
        freeBytes: () -> Long = { Long.MAX_VALUE }
    ) = SessionRecorder(
        directory = directory,
        sampleRate = 16000,
        queueCapacity = queueCapacity,
        freeBytes = freeBytes,
        onFailure = { message, _ -> failures.add(message) }
    )

    private fun File.littleEndianInt(offset: Int): Int =
        ByteBuffer.wrap(readBytes(), offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun File.samples(): ShortArray {
        val bytes = readBytes()
        val out = ShortArray((bytes.size - WavWriter.HEADER_SIZE) / 2)
        ByteBuffer.wrap(bytes, WavWriter.HEADER_SIZE, out.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun assertHeaderMatchesFile(file: File) {
        val audioBytes = (file.length() - WavWriter.HEADER_SIZE).toInt()
        assertEquals("RIFF", String(file.readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals(audioBytes + 36, file.littleEndianInt(4))
        assertEquals(16000, file.littleEndianInt(24))
        assertEquals(audioBytes, file.littleEndianInt(40))
    }

    @Test
    fun recordsEveryFrameInOrderWithAValidHeader() {
        val directory = tempFolder.newFolder()
        val recorder = recorder(directory)
        recorder.start()

        val written = (0 until 1000).flatMap { frame ->
            val samples = ShortArray(1024) { i -> (frame * 7 + i - 2000).toShort() }
            recorder.write(samples)
            samples.toList()
        }
        recorder.close()
        recorder.awaitFinished(5000)

        val file = SessionRecordingStore.list(directory).single().file
        assertHeaderMatchesFile(file)
        assertEquals(written, file.samples().toList())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun writeNeverBlocksAndDroppedFramesBecomeSilenceSoTimestampsStayAligned() {
        val directory = tempFolder.newFolder()
        val recorder = recorder(directory, queueCapacity = 2)

        // Nothing is consuming yet: stands in for storage that has stalled
        val startNanos = System.nanoTime()
        repeat(5) { recorder.write(ShortArray(4) { 7 }) }
        assertTrue(System.nanoTime() - startNanos < 1_000_000_000L)

        recorder.start()
        recorder.close()
        recorder.awaitFinished(5000)

        val samples = SessionRecordingStore.list(directory).single().file.samples().toList()
        assertEquals("all 5 frames must be accounted for", 20, samples.size)
        assertEquals(12, samples.count { it == 0.toShort() })
        assertEquals(8, samples.count { it == 7.toShort() })
    }

    @Test
    fun doesNotStartWhenStorageIsBelowTheReserve() {
        val directory = tempFolder.newFolder()
        val recorder = recorder(directory, freeBytes = { 0L })
        recorder.start()
        recorder.write(ShortArray(1024))
        recorder.awaitFinished(5000)

        assertTrue(SessionRecordingStore.list(directory).isEmpty())
        assertEquals(1, failures.size)
    }

    @Test
    fun stopsAndFinalisesTheFileWhenStorageRunsLowMidSession() {
        val directory = tempFolder.newFolder()
        val freeChecks = AtomicInteger(0)
        // Enough space for the check at start, none at the first check during recording
        val recorder = recorder(directory, freeBytes = {
            if (freeChecks.incrementAndGet() == 1) Long.MAX_VALUE else 0L
        })

        repeat(1000) { recorder.write(ShortArray(1024) { 1 }) }
        recorder.start()
        recorder.awaitFinished(5000)

        val file = SessionRecordingStore.list(directory).single().file
        assertHeaderMatchesFile(file)
        assertTrue(file.samples().size in 1..(999 * 1024))
        assertEquals(1, failures.size)

        // Later frames from the detection loop are ignored without error
        recorder.write(ShortArray(1024))
        assertEquals(1, failures.size)
    }

    @Test
    fun failureToWriteIsReportedInsteadOfThrown() {
        // A regular file where the directory should be: nothing can be created inside it
        val notADirectory = tempFolder.newFile()
        val recorder = recorder(notADirectory)
        recorder.start()
        recorder.write(ShortArray(1024))
        recorder.close()
        recorder.awaitFinished(5000)

        assertEquals(1, failures.size)
    }

    @Test
    fun sessionWithoutAudioLeavesNoFile() {
        val directory = tempFolder.newFolder()
        val recorder = recorder(directory)
        recorder.start()
        recorder.close()
        recorder.awaitFinished(5000)

        assertTrue(SessionRecordingStore.list(directory).isEmpty())
    }

    @Test
    fun startingARecordingDeletesTheOldestBeyondTheLimit() {
        val directory = tempFolder.newFolder()
        val old = (1L..4L).map { start ->
            SessionRecordingStore.newFile(directory, start).apply { writeBytes(ByteArray(100)) }
        }

        val recorder = recorder(directory)
        recorder.start()
        recorder.write(ShortArray(1024))
        recorder.close()
        recorder.awaitFinished(5000)

        assertEquals(SessionRecordingStore.MAX_KEPT_RECORDINGS, SessionRecordingStore.list(directory).size)
        assertFalse(old[0].exists())
        assertFalse(old[1].exists())
        assertTrue(old[2].exists())
        assertTrue(old[3].exists())
    }

    @Test
    fun updateHeaderSizesDoesNotDisturbTheWritePosition() {
        val file = tempFolder.newFile()
        java.io.FileOutputStream(file).use { out ->
            WavWriter.writeHeader(out, 1, 16000, 16, 0)
            out.write(ByteArray(10))
            WavWriter.updateHeaderSizes(out.channel)
            out.write(ByteArray(6))
            WavWriter.updateHeaderSizes(out.channel)
        }

        assertEquals(WavWriter.HEADER_SIZE + 16L, file.length())
        assertHeaderMatchesFile(file)
    }

    @Test
    fun listIgnoresUnrelatedFilesAndSortsNewestFirst() {
        val directory = tempFolder.newFolder()
        SessionRecordingStore.newFile(directory, 100L).writeBytes(ByteArray(10))
        SessionRecordingStore.newFile(directory, 300L).writeBytes(ByteArray(10))
        SessionRecordingStore.newFile(directory, 200L).writeBytes(ByteArray(10))
        File(directory, "session_abc.wav").writeBytes(ByteArray(10))
        File(directory, "notes.txt").writeBytes(ByteArray(10))

        assertEquals(listOf(300L, 200L, 100L), SessionRecordingStore.list(directory).map { it.startTimeMillis })

        SessionRecordingStore.deleteAll(directory)
        assertTrue(SessionRecordingStore.list(directory).isEmpty())
        assertTrue("unrelated files stay", File(directory, "notes.txt").exists())
    }

    @Test
    fun listOfMissingDirectoryIsEmpty() {
        assertTrue(SessionRecordingStore.list(File(tempFolder.root, "missing")).isEmpty())
    }

    @Test
    fun durationAndSizeAreDerivedFromTheFile() {
        val recording = SessionRecording(
            file = File("session_0.wav"),
            startTimeMillis = 0L,
            sizeBytes = WavWriter.HEADER_SIZE + 2 * SessionRecordingStore.BYTES_PER_HOUR
        )

        assertEquals(7200L, recording.durationSeconds)
        assertEquals("2 h 0 min", SessionRecordingStore.formatDuration(recording.durationSeconds))
        assertEquals("42 min", SessionRecordingStore.formatDuration(42 * 60L))
        assertEquals("230 MB", SessionRecordingStore.formatSize(recording.sizeBytes))
        assertEquals("1.4 GB", SessionRecordingStore.formatSize(12 * SessionRecordingStore.BYTES_PER_HOUR))
        assertTrue(recording.exportFileName.matches(Regex("SnoreDetector_Session_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.wav")))
    }
}
