package com.antigravity.mirror.stream.media

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "MirrorApp/AudioEncoder"
private const val MIME_TYPE = "audio/mp4a-latm" // AAC
private const val SAMPLE_RATE = 44100
private const val CHANNEL_COUNT = 2
private const val BITRATE = 128000
private const val BUFFER_SIZE_FACTOR = 4 // Increased for better headroom
private const val PCM_BUFFER_SIZE = 8192 // Increased chunk size to 8KB (approx 46ms)

/**
 * Captures system audio via [MediaProjection] and encodes it to AAC.
 * 
 * Requirements: tasks.md §Stretch/v2
 */
class AudioEncoder(private val mediaProjection: MediaProjection) {
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    
    @Volatile
    private var isRunning = false
    
    private var encodingThread: Thread? = null

    /**
     * Starts audio capture and encoding.
     * Only works on Android 10+ (API 29).
     */
    @SuppressLint("MissingPermission", "NewApi")
    fun start(onAudioData: (ByteArray, Long) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Audio capture requires Android 10+")
            return
        }

        if (isRunning) return
        isRunning = true
        
        try {
            // 1. Configure Codec
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // 2. Configure AudioRecord (System Capture)
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setBufferSizeInBytes(minBufferSize * BUFFER_SIZE_FACTOR)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord?.startRecording()

            // 3. Start Loop
            encodingThread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                runLoop(onAudioData)
            }, "AudioEncoder-Thread").apply { start() }
            
            Log.i(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture: ${e.message}", e)
            isRunning = false
        }
    }

    private fun runLoop(onAudioData: (ByteArray, Long) -> Unit) {
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ByteArray(PCM_BUFFER_SIZE)

        try {
            while (isRunning) {
                // Read PCM from AudioRecord
                val readSize = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                if (readSize <= 0) {
                    if (!isRunning) break
                    Thread.yield()
                    continue
                }

                // Feed into MediaCodec
                val inputIndex = codec?.dequeueInputBuffer(10000) ?: -1
                if (inputIndex >= 0) {
                    val inputBuffer = codec?.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, 0, readSize)
                    codec?.queueInputBuffer(inputIndex, 0, readSize, System.nanoTime() / 1000, 0)
                }

                // Drain MediaCodec
                var outputIndex = codec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                while (outputIndex >= 0) {
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isCodecConfig && bufferInfo.size > 0) {
                        val outputBuffer = codec?.getOutputBuffer(outputIndex)!!
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        onAudioData(data, bufferInfo.presentationTimeUs)
                    }
                    codec?.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Audio encoding loop error: ${e.message}")
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping audio capture...")
        isRunning = false
        encodingThread?.join(1000)
        encodingThread = null
        
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {}
        codec = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }
}
