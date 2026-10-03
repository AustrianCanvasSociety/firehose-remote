package app.burro.firehoseremote.net

import app.burro.firehoseremote.protocol.WakeOnLan
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * [WakeOnLan] over a UDP broadcast socket.
 *
 * The packet goes to every address [broadcastAddresses] returns at the moment of
 * sending — asked each time, because the phone can change networks between one
 * press and the next. An address that cannot be sent to costs that address only.
 * [log] gets one line per address, beside the transport's own request lines.
 */
class UdpWakeOnLan(
    private val broadcastAddresses: () -> List<InetAddress>,
    private val port: Int = WakeOnLan.PORT,
    private val log: (String) -> Unit = {}
) : WakeOnLan {

    override fun send(mac: String) {
        val packet = WakeOnLan.magicPacket(mac)
        for (address in broadcastAddresses()) {
            val target = "${address.hostAddress}:$port"
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(packet, packet.size, address, port))
                }
                log("WOL $mac -> $target sent")
            } catch (e: IOException) {
                log("WOL $mac -> $target failed (${e.javaClass.simpleName})")
            }
        }
    }
}
