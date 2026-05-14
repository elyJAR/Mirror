package com.antigravity.mirror.stream.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

private const val TAG = "MirrorApp/ScreenCaptureEngine"
private const val VIRTUAL_DISPLAY_NAME = "MirrorDisplay"

/**
 * Manages [MediaProjection], [VirtualDisplay], and the [android.view.Surface] input to
 * [VideoEncoder].
 *
 * Creates a [VirtualDisplay] at the negotiated resolution and DPI, feeding frames directly
 * into the encoder's input surface.
 *
 * Requirements: 8.1, 8.3
 */
class ScreenCaptureEngine(
    private val projection: MediaProjection,
    private val encoder: VideoEncoder
) {

    private var virtualDisplay: VirtualDisplay? = null

    /**
     * Callback wired by the consumer (e.g. `MirrorService` in `app/`) to forward encoded
     * NAL units to the active transport's sender (`RtpSender` for Miracast, the LAN
     * protocol client for the LAN transport).
     */
    private var nalUnitCallback: ((ByteArray, Long) -> Unit)? = null

    /**
     * Registers the callback that receives encoded H.264 NAL units from [VideoEncoder].
     *
     * Call this before [start] so that the encoder output is forwarded to the active
     * transport's sender.
     *
     * @param callback Invoked with (nalUnitBytes, presentationTimeUs) for each encoded NAL unit.
     */
    fun setNalUnitCallback(callback: (ByteArray, Long) -> Unit) {
        nalUnitCallback = callback
    }

    /**
     * Configures the encoder, creates the [VirtualDisplay], and starts the encoding loop.
     *
     * Steps:
     * 1. Call [VideoEncoder.configure] to obtain the encoder's input [android.view.Surface].
     * 2. Create a [VirtualDisplay] backed by that surface via [MediaProjection].
     * 3. Call [VideoEncoder.start] with the registered [nalUnitCallback].
     *
     * @param width  Negotiated display width in pixels.
     * @param height Negotiated display height in pixels.
     * @param dpi    Display density in dots per inch.
     *
     * Requirements: 8.1, 8.3
     */
    fun start(width: Int, height: Int, dpi: Int) {
        // Android 14 (API 34) requires a callback to be registered before createVirtualDisplay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stop()
                }
            }, null)
        }

        // Step 1: configure encoder and get its input surface
        val surface = encoder.configure()

        // Step 2: create VirtualDisplay feeding frames into the encoder surface
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,  // no VirtualDisplay.Callback needed
            null   // use default handler
        )

        Log.d(TAG, "VirtualDisplay created: ${width}x${height} @ ${dpi}dpi")

        // Step 3: start encoding loop, forwarding NAL units via the registered callback
        encoder.start { nalBytes, presentationTimeUs ->
            nalUnitCallback?.invoke(nalBytes, presentationTimeUs)
        }
    }

    /**
     * Stops the encoder, releases the [VirtualDisplay], and stops the [MediaProjection].
     *
     * Requirements: 8.1
     */
    fun stop() {
        try {
            encoder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "encoder.stop() failed: ${e.message}")
        }

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "virtualDisplay.release() failed: ${e.message}")
        } finally {
            virtualDisplay = null
        }

        try {
            projection.stop()
        } catch (e: Exception) {
            Log.w(TAG, "projection.stop() failed: ${e.message}")
        }

        Log.d(TAG, "ScreenCaptureEngine stopped")
    }
}
