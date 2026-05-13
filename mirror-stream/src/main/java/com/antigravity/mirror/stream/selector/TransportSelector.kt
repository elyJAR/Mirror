package com.antigravity.mirror.stream.selector

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.antigravity.mirror.stream.api.Transport
import com.antigravity.mirror.stream.transport.TransportId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mirror_transport_selector")

private const val TAG = "MirrorApp/TransportSel"

/**
 * Persisted state for a device's Miracast support.
 */
enum class MiracastState { UNKNOWN, ALLOWED, DENIED }

/**
 * Decides which [com.antigravity.mirror.stream.transport.Transport] implementation to use for
 * a given session based on device compatibility heuristics and persistent history.
 *
 * Implements the logic defined in `design.md` §6.
 */
class TransportSelector(
    private val context: Context,
    private val dataStoreOverride: DataStore<Preferences>? = null,
    private val manufacturer: String = Build.MANUFACTURER,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    private val dataStore = dataStoreOverride ?: context.dataStore

    private val deviceFingerprint = "${manufacturer}_${sdkInt}"
    private val prefKey = stringPreferencesKey("miracast_state_$deviceFingerprint")

    /**
     * Selects the transport(s) to use for the session, in order of preference.
     *
     * @param preference The user's preferred transport mode from [com.antigravity.mirror.stream.api.MirrorConfig].
     * @return A list of [TransportId]s to attempt.
     */
    suspend fun selectTransports(preference: Transport): List<TransportId> {
        return when (preference) {
            Transport.LAN -> listOf(TransportId.LAN)
            Transport.MIRACAST -> listOf(TransportId.MIRACAST)
            Transport.AUTO -> {
                val state = getMiracastState()
                Log.d(TAG, "Auto-selection for $deviceFingerprint: state=$state")
                
                when (state) {
                    MiracastState.ALLOWED -> listOf(TransportId.MIRACAST, TransportId.LAN)
                    MiracastState.DENIED -> listOf(TransportId.LAN)
                    MiracastState.UNKNOWN -> {
                        if (applyHeuristic()) {
                            listOf(TransportId.MIRACAST, TransportId.LAN)
                        } else {
                            listOf(TransportId.LAN)
                        }
                    }
                }
            }
        }
    }

    /**
     * Records the outcome of a transport attempt.
     *
     * If a Miracast attempt fails (e.g. timeout or blocked by OEM), we demote it to [MiracastState.DENIED]
     * so future AUTO sessions skip it immediately.
     */
    suspend fun recordOutcome(transportId: TransportId, success: Boolean) {
        if (transportId == TransportId.MIRACAST) {
            val currentState = getMiracastState()
            val newState = if (success) MiracastState.ALLOWED else MiracastState.DENIED
            
            if (currentState != newState) {
                setMiracastState(newState)
            }
        }
    }

    /**
     * Clears the persisted state for the current device.
     *
     * Useful if the user wants to re-test Miracast after a system update.
     */
    suspend fun resetDetection() {
        dataStore.edit { it.remove(prefKey) }
    }

    private suspend fun getMiracastState(): MiracastState {
        val name = dataStore.data.map { it[prefKey] }.first() ?: return MiracastState.UNKNOWN
        return try {
            MiracastState.valueOf(name)
        } catch (e: Exception) {
            MiracastState.UNKNOWN
        }
    }

    private suspend fun setMiracastState(state: MiracastState) {
        Log.i(TAG, "Updating Miracast state for $deviceFingerprint to $state")
        dataStore.edit { it[prefKey] = state.name }
    }

    private fun applyHeuristic(): Boolean {
        // Miracast is known to work on older Google Pixels (API <= 33).
        // It is almost always blocked on Samsung One UI 6 (Android 14) and later.
        val m = manufacturer.lowercase()
        val s = sdkInt
        
        val result = m == "google" && s <= 33
        Log.d(TAG, "Heuristic check for $m (API $s): $result")
        return result
    }
}
