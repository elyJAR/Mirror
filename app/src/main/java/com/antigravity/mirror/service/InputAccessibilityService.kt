package com.antigravity.mirror.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.antigravity.mirror.stream.transport.TransportEvent

private const val TAG = "MirrorApp/InputService"

/**
 * Service that provides input injection (Reverse Control) capabilities.
 * 
 * Must be enabled by the user in Settings > Accessibility.
 */
class InputAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: InputAccessibilityService? = null
        
        fun getInstance(): InputAccessibilityService? = instance
        
        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "InputAccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "InputAccessibilityService unbinding")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    fun injectTouch(action: Int, x: Float, y: Float) {
        val displayMetrics = resources.displayMetrics
        val screenX = x * displayMetrics.widthPixels
        val screenY = y * displayMetrics.heightPixels

        Log.d(TAG, "Injecting touch: action=$action, x=$screenX, y=$screenY")

        // Map protocol actions to GestureDescription
        // 0=down, 1=up, 2=move
        // Since AccessibilityService gesture injection is stroke-based, we'll
        // simulate a tap for "down" and ignore "move" for now to keep it simple and responsive.
        // A full remote control implementation would track the finger state.
        
        if (action == 0) { // Down/Tap
            val path = Path()
            path.moveTo(screenX, screenY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            dispatchGesture(gesture, null, null)
        }
    }

    fun injectKey(code: Int) {
        Log.d(TAG, "Injecting key code: $code")
        
        // Handle common Android global actions
        when (code) {
            4 -> performGlobalAction(GLOBAL_ACTION_BACK) // KEYCODE_BACK
            3 -> performGlobalAction(GLOBAL_ACTION_HOME) // KEYCODE_HOME
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS) // KEYCODE_APP_SWITCH
            else -> {
                Log.w(TAG, "Key code $code not supported for injection via AccessibilityService")
            }
        }
    }
}
