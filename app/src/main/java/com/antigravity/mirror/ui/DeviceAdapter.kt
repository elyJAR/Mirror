package com.antigravity.mirror.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.antigravity.mirror.R
import com.antigravity.mirror.stream.api.Receiver
import com.antigravity.mirror.stream.transport.TransportId

/**
 * RecyclerView adapter that displays a categorized list of discovered screen mirror receivers.
 */
class DeviceAdapter(
    private var rawDevices: List<Receiver>,
    private val onDeviceClick: (Receiver) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class Device(val receiver: Receiver) : ListItem()
    }

    private var items: List<ListItem> = emptyList()

    init {
        rebuildItems()
    }

    private fun rebuildItems() {
        val newItems = mutableListOf<ListItem>()
        
        // Group 1: Miracast (Direct)
        val miracast = rawDevices.filter { it.transportId == TransportId.MIRACAST }
        if (miracast.isNotEmpty()) {
            newItems.add(ListItem.Header("Direct Connect (Wireless Display)"))
            newItems.addAll(miracast.map { ListItem.Device(it) })
        }

        // Group 2: LAN (Nearby)
        val lan = rawDevices.filter { it.transportId == TransportId.LAN }
        if (lan.isNotEmpty()) {
            newItems.add(ListItem.Header("Nearby Displays (Local Network / LAN)"))
            newItems.addAll(lan.map { ListItem.Device(it) })
        }

        items = newItems
    }

    // -------------------------------------------------------------------------
    // ViewHolders
    // -------------------------------------------------------------------------

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.headerTitle)
    }

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.deviceName)
        val deviceAddress: TextView = view.findViewById(R.id.deviceAddress)
        val connectButton: View = view.findViewById(R.id.connectButton)
    }

    // -------------------------------------------------------------------------
    // Adapter overrides
    // -------------------------------------------------------------------------

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Header -> 0
            is ListItem.Device -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false))
            else -> DeviceViewHolder(inflater.inflate(R.layout.item_device, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> {
                (holder as HeaderViewHolder).title.text = item.title
            }
            is ListItem.Device -> {
                val h = holder as DeviceViewHolder
                val device = item.receiver
                h.deviceName.text = device.name.ifBlank { "Unknown Receiver" }
                h.deviceAddress.text = "${device.host}:${device.port}"
                h.itemView.setOnClickListener { onDeviceClick(device) }
                h.connectButton.setOnClickListener { onDeviceClick(device) }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateDevices(newDevices: List<Receiver>) {
        rawDevices = newDevices
        rebuildItems()
        notifyDataSetChanged()
    }
}
