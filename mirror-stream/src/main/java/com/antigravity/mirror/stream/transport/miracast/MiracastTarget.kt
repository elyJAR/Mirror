package com.antigravity.mirror.stream.transport.miracast

import android.net.wifi.p2p.WifiP2pDevice
import com.antigravity.mirror.stream.transport.TransportId
import com.antigravity.mirror.stream.transport.TransportTarget

/**
 * A Miracast-specific [TransportTarget] wrapping a [WifiP2pDevice].
 */
data class MiracastTarget(
    val device: WifiP2pDevice
) : TransportTarget {
    override val name: String = device.deviceName
    override val host: String = device.deviceAddress
    override val port: Int = 0
    override val transportId: TransportId = TransportId.MIRACAST
}
