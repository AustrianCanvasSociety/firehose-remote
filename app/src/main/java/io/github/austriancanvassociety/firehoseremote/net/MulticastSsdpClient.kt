package io.github.austriancanvassociety.firehoseremote.net

import android.content.Context
import android.net.wifi.WifiManager
import io.github.austriancanvassociety.firehoseremote.protocol.Ssdp
import io.github.austriancanvassociety.firehoseremote.protocol.SsdpSearch
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * The socket half of SSDP discovery — the only part of this feature that
 * `protocol/` cannot hold, because `MulticastSocket` is `java.net.*` and that
 * package may not import it.
 *
 * Deliberately thin: it sends the datagram [Ssdp] builds, hands back the raw
 * text of every answer, and makes no judgement about what any of it means.
 * Every decision — which answers are usable, which devices they identify, what
 * the names are — lives in `protocol/`, where the JVM suite covers it.
 *
 * Sends from an ephemeral port rather than binding 1900. SSDP responders reply
 * unicast to the source port of the search, not to the multicast port, so
 * binding 1900 buys nothing here — and on Android it is a port other apps
 * already hold, which turns a working search into an `Address already in use`.
 */
class MulticastSsdpClient(private val context: Context?) : SsdpSearch {

    override fun search(timeoutMillis: Long): List<Ssdp.SsdpResponse> {
        // Android filters multicast arriving at the Wi-Fi chip unless a lock is
        // held; without this the search sends cleanly and hears nothing back,
        // which looks exactly like a network with no TVs on it.
        val lock = acquireMulticastLock()
        try {
            MulticastSocket().use { socket ->
                socket.timeToLive = TIME_TO_LIVE

                val group = InetAddress.getByName(Ssdp.MULTICAST_GROUP)
                val payload = Ssdp.searchRequest().toByteArray(Charsets.UTF_8)

                // Repeated because the vendor app repeats it — six per search.
                // SSDP rides UDP over Wi-Fi, where a single datagram is the
                // worst case for loss, and every repeat costs one packet.
                repeat(Ssdp.RETRIES) { attempt ->
                    socket.send(DatagramPacket(payload, payload.size, group, Ssdp.MULTICAST_PORT))
                    if (attempt < Ssdp.RETRIES - 1) Thread.sleep(SEND_GAP_MS)
                }

                return collect(socket, timeoutMillis)
            }
        } catch (e: IOException) {
            // A network that refuses multicast, or a socket the platform will
            // not hand over, is not an error the user can act on — it is the
            // condition the address sweep exists to cover. Answer empty and let
            // `Discovery` fall through to it.
            return emptyList()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        } finally {
            lock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun collect(socket: MulticastSocket, timeoutMillis: Long): List<Ssdp.SsdpResponse> {
        val found = mutableListOf<Ssdp.SsdpResponse>()
        val deadline = System.currentTimeMillis() + timeoutMillis
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)

        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            socket.soTimeout = remaining.toInt().coerceAtMost(SOCKET_TIMEOUT_MS)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                // A quiet interval, not the end of the window. Breaking on this
                // made the listen window a fixed 500 ms of silence however long
                // the caller asked for — and Discovery asks for 2500 ms, with a
                // KDoc saying it must exceed the responder's MX allowance. A TV
                // that used its full MX was simply missed.
                continue
            } catch (e: IOException) {
                // The socket is gone — closed under us, or the interface went
                // away. Nothing more will arrive on it.
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val from = packet.address?.hostAddress ?: continue
            Ssdp.parseResponse(text, from)?.let { found.add(it) }
            if (found.size >= Ssdp.MAX_RESPONDERS) break
        }
        return found
    }

    /**
     * Best-effort. A device whose Wi-Fi service will not hand one over still
     * gets a search attempt rather than a crash on a path the user cannot fix.
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        val wifi = context?.applicationContext
            ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        /** One hop. The search is for this link; routing it past the first hop finds nothing. */
        private const val TIME_TO_LIVE = 1

        private const val SOCKET_TIMEOUT_MS = 500
        private const val SEND_GAP_MS = 150L

        /** A descriptor-bearing answer is a few hundred bytes; anything near this is not one. */
        private const val MAX_DATAGRAM_BYTES = 8 * 1024

        // The responder cap lives in `Ssdp.MAX_RESPONDERS`, not here: `protocol/`
        // may not import `net/`, so the shared home has to be the protocol side,
        // and this half reads the same constant the descriptor half bounds on.

        private const val MULTICAST_LOCK_TAG = "firehose-remote-ssdp"
    }
}
