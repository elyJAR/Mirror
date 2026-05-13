package com.antigravity.mirror.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Centralises runtime permission logic for Miracast discovery and streaming.
 *
 * Permission mapping by API level:
 *  - API 21–22: Normal permissions only (no runtime grants needed)
 *  - API 23–28: + ACCESS_FINE_LOCATION (required for Wi-Fi Direct peer discovery)
 *  - API 29–30: + FOREGROUND_SERVICE
 *  - API 31–32: NEARBY_WIFI_DEVICES replaces ACCESS_FINE_LOCATION, + FOREGROUND_SERVICE
 *  - API 33+:   Same as 31–32 + FOREGROUND_SERVICE_MEDIA_PROJECTION
 *
 * NEVER includes RECORD_AUDIO, CAMERA, or any camera-related permission.
 */
object PermissionManager {

    // Base permissions required on all API levels (21+).
    // These are normal permissions and do not require runtime grants, but are
    // included so callers have a complete picture of what the app uses.
    private val BASE_PERMISSIONS = listOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.INTERNET,
        Manifest.permission.WAKE_LOCK
    )

    /**
     * Returns the exact set of permissions required for Miracast discovery and streaming
     * on the given [apiLevel]. No permission is included that is not used; no required
     * permission is omitted.
     *
     * @param apiLevel The Android API level to query (e.g. [Build.VERSION.SDK_INT]).
     * @return Ordered list of permission strings required at that API level.
     */
    fun requiredPermissions(apiLevel: Int): List<String> {
        val permissions = BASE_PERMISSIONS.toMutableList()

        when {
            apiLevel >= 33 -> {
                // API 33+: NEARBY_WIFI_DEVICES (replaces fine location), FOREGROUND_SERVICE,
                // and FOREGROUND_SERVICE_MEDIA_PROJECTION (new in API 33).
                permissions += Manifest.permission.NEARBY_WIFI_DEVICES
                permissions += Manifest.permission.FOREGROUND_SERVICE
                permissions += Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            }
            apiLevel >= 31 -> {
                // API 31–32: NEARBY_WIFI_DEVICES replaces ACCESS_FINE_LOCATION.
                permissions += Manifest.permission.NEARBY_WIFI_DEVICES
                permissions += Manifest.permission.FOREGROUND_SERVICE
            }
            apiLevel >= 29 -> {
                // API 29–30: Fine location still required; foreground service added.
                permissions += Manifest.permission.ACCESS_FINE_LOCATION
                permissions += Manifest.permission.FOREGROUND_SERVICE
            }
            apiLevel >= 23 -> {
                // API 23–28: Fine location required for Wi-Fi Direct peer discovery.
                permissions += Manifest.permission.ACCESS_FINE_LOCATION
            }
            // API 21–22: base permissions only; all are normal permissions.
        }

        return permissions
    }

    /**
     * Returns the subset of [requiredPermissions] that have not yet been granted on
     * the current device. Uses [ContextCompat.checkSelfPermission] for each permission
     * at the device's current API level ([Build.VERSION.SDK_INT]).
     *
     * @param context Any valid [Context] (Application or Activity).
     * @return List of permission strings that are not yet granted.
     */
    fun missingPermissions(context: Context): List<String> {
        return requiredPermissions(Build.VERSION.SDK_INT).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Delegates to [ActivityCompat.shouldShowRequestPermissionRationale] to determine
     * whether the app should display a rationale UI before requesting [permission].
     *
     * @param activity The current foreground [Activity].
     * @param permission The permission string to check.
     * @return `true` if a rationale should be shown; `false` otherwise.
     */
    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}
