package com.aistudio.snoredetector.afkwd.audio

import com.aistudio.snoredetector.afkwd.data.SessionRecordingStore
import com.aistudio.snoredetector.afkwd.dsp.WavWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams every captured audio frame of a session to a WAV file on its own thread.
 *
 * [write] is called from the detection loop and never blocks: if storage cannot keep up, frames
 * are dropped from the recording and replaced by silence so the file stays aligned with event
 * timestamps. Any failure ends the recording only; detection carries on. The header is refreshed
 * regularly, so the file stays playable if the process is killed overnight.
 *
 * A plain thread rather than a service coroutine, so the last frames are still written after the
 * service scope is cancelled.
 */
class SessionRecorder(
    private val directory: File,
    private val sampleRate: Int,
    queueCapacity: Int = QUEUE_CAPACITY_FRAMES,
    private val minFreeBytes: Long = SessionRecordingStore.MIN_FREE_BYTES,
    private val freeBytes: () -> Long = { directory.usableSpace },
    private val onFailure: (message: String, cause: Throwable?) -> Unit
) {
    private val queue = ArrayBlockingQueue<ShortArray>(queueCapacity)
    private val droppedSamples = AtomicLong(0L)
    private val accepting = AtomicBoolean(true)
    private var encoded = ByteArray(2048)
    private var thread: Thread? = null

    fun start() {
        thread = Thread(::record, "SessionRecorder").also { it.start() }
    }

    /** The recorder keeps a reference to [frame], so it must not be modified afterwards. */
    fun write(frame: ShortArray) {
        if (!accepting.get()) return
        if (!queue.offer(frame)) {
            droppedSamples.addAndGet(frame.size.toLong())
        }
    }

    /** Stops accepting frames. Queued frames are still written; returns without waiting for that. */
    fun close() {
        accepting.set(false)
    }

    fun awaitFinished(timeoutMillis: Long) {
        thread?.join(timeoutMillis)
    }

    private fun record() {
        var file: File? = null
        try {
            directory.mkdirs()
            SessionRecordingStore.prune(directory, SessionRecordingStore.MAX_KEPT_RECORDINGS - 1)
            if (freeBytes() < minFreeBytes) {
                onFailure("Full-session recording skipped: less than ${SessionRecordingStore.formatSize(minFreeBytes)} of storage free", null)
                return
            }

            file = SessionRecordingStore.newFile(directory, System.currentTimeMillis())
            FileOutputStream(file).use { fileStream ->
                val out = BufferedOutputStream(fileStream, WRITE_BUFFER_BYTES)
                try {
                    WavWriter.writeHeader(out, 1, sampleRate, 16, 0)
                    writeFrames(out, fileStream.channel)
                } finally {
                    // Storage may be full here; the header then describes what actually reached the file
                    runCatching { out.flush() }
                    WavWriter.updateHeaderSizes(fileStream.channel)
                }
            }
        } catch (e: Exception) {
            onFailure("Full-session recording stopped early: ${e.message}", e)
        } finally {
            accepting.set(false)
            queue.clear()
            if (file != null && file.length() <= WavWriter.HEADER_SIZE) {
                file.delete()
            }
        }
    }

    private fun writeFrames(out: BufferedOutputStream, channel: FileChannel) {
        var framesSinceCheckpoint = 0
        while (true) {
            val frame = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (accepting.get()) continue else return
            }

            writeSilence(out, droppedSamples.getAndSet(0L))
            writeSamples(out, frame)

            if (++framesSinceCheckpoint >= CHECKPOINT_INTERVAL_FRAMES) {
                framesSinceCheckpoint = 0
                out.flush()
                WavWriter.updateHeaderSizes(channel)
                if (freeBytes() < minFreeBytes) {
                    onFailure("Full-session recording stopped early: less than ${SessionRecordingStore.formatSize(minFreeBytes)} of storage free", null)
                    return
                }
            }
        }
    }

    private fun writeSamples(out: OutputStream, frame: ShortArray) {
        val byteCount = frame.size * 2
        if (encoded.size < byteCount) encoded = ByteArray(byteCount)
        for (i in frame.indices) {
            val value = frame[i].toInt()
            encoded[i * 2] = (value and 0xFF).toByte()
            encoded[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        out.write(encoded, 0, byteCount)
    }

    private fun writeSilence(out: OutputStream, samples: Long) {
        var remaining = samples * 2
        if (remaining == 0L) return
        val zeros = ByteArray(4096)
        while (remaining > 0L) {
            val chunk = remaining.coerceAtMost(zeros.size.toLong()).toInt()
            out.write(zeros, 0, chunk)
            remaining -= chunk
        }
    }

    companion object {
        private const val QUEUE_CAPACITY_FRAMES = 512 // ~33 s of audio, 1 MB of memory
        private const val CHECKPOINT_INTERVAL_FRAMES = 156 // ~10 s
        private const val WRITE_BUFFER_BYTES = 64 * 1024 // ~2 s, keeps storage idle between bursts
        private const val POLL_TIMEOUT_MILLIS = 100L
    }
}
