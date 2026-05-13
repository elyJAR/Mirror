package com.antigravity.mirror.ui

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.antigravity.mirror.R

/**
 * RecyclerView adapter that displays a list of discovered Wi-Fi Direct peer devices.
 *
 * Each item shows the device name and MAC address. Tapping an item invokes [onDeviceClick].
 *
 * Requirements: 6.1, 6.2
 */
class DeviceAdapter(
    private var devices: List<WifiP2pDevice>,
    private val onDeviceClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    // -------------------------------------------------------------------------
    // ViewHolder
    // -------------------------------------------------------------------------

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        val deviceAddress: TextView = itemView.findViewById(R.id.deviceAddress)
    }

    // -------------------------------------------------------------------------
    // Adapter overrides
    // -------------------------------------------------------------------------

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.deviceName.text = device.deviceName.ifBlank { "Unknown Device" }
        holder.deviceAddress.text = device.deviceAddress
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    // -------------------------------------------------------------------------
    // Data update
    // -------------------------------------------------------------------------

    /**
     * Replaces the current device list and refreshes the RecyclerView.
     *
     * @param newDevices The updated list of discovered [WifiP2pDevice] peers.
     */
    fun updateDevices(newDevices: List<WifiP2pDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
