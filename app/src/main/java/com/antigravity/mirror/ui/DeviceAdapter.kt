package com.antigravity.mirror.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.antigravity.mirror.R
import com.antigravity.mirror.stream.api.Receiver

/**
 * RecyclerView adapter that displays a list of discovered screen mirror receivers.
 *
 * Each item shows the receiver name and its host address/port.
 * Tapping an item invokes [onDeviceClick].
 *
 * Requirements: 6.1, 6.2
 */
class DeviceAdapter(
    private var devices: List<Receiver>,
    private val onDeviceClick: (Receiver) -> Unit
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
        holder.deviceName.text = device.name.ifBlank { "Unknown Receiver" }
        holder.deviceAddress.text = "${device.host}:${device.port} (${device.transportId})"
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    // -------------------------------------------------------------------------
    // Data update
    // -------------------------------------------------------------------------

    /**
     * Replaces the current receiver list and refreshes the RecyclerView.
     *
     * @param newDevices The updated list of discovered [Receiver] peers.
     */
    fun updateDevices(newDevices: List<Receiver>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
