package io.github.austriancanvassociety.firehoseremote.protocol

/**
 * Sends a Wake-on-LAN magic packet — what actually wakes a deeply asleep Fire TV.
 *
 * Measured 2026-09-25 (`docs/protocol.md § 3`): against `the test TV` left asleep,
 * sixteen DIAL wake requests never got a connection, and one magic packet —
 * sent from a laptop, with no request of any kind — woke it through the TV
 * maker's logo to Home. The vendor app sends one alongside every wake attempt.
 * The address is the one the TV announces in the `WAKEUP` header of its SSDP
 * answer ([Ssdp.SsdpResponse.wakeupMac]); a TV that announces none gets no packet.
 *
 * A seam in `protocol/`, like [Transport]; the socket-backed implementation is
 * `net/UdpWakeOnLan.kt`.
 */
interface WakeOnLan {

    /**
     * Send one magic packet for [mac]. Best effort by contract: a packet that
     * cannot be sent costs a slower wake, not the press, so an implementation
     * does not throw for a network fault.
     */
    fun send(mac: String)

    companion object {
        /** The conventional Wake-on-LAN port, and the one the packet that woke `the test TV` went to. */
        const val PORT = 9

        /** Sends nothing: for tests that are not about waking. */
        val None: WakeOnLan = object : WakeOnLan {
            override fun send(mac: String) {}
        }

        /**
         * The magic packet for [mac]: six `0xFF` bytes, then the MAC's six bytes
         * sixteen times — 102 bytes.
         *
         * Only the shape `WAKEUP` carries is accepted — six colon-separated hex
         * pairs, the same shape [Ssdp.macFromWakeup] lets through. Anything else
         * is refused rather than guessed at: a packet built from a wrong address
         * wakes nothing and says nothing.
         */
        fun magicPacket(mac: String): ByteArray {
            val parts = mac.split(':')
            require(parts.size == 6 && parts.all { it.length == 2 && it.all { c -> c.isAsciiHex() } }) {
                "firehose-remote: wake-on-lan: not a MAC address: \"$mac\""
            }
            val address = ByteArray(6) { parts[it].toInt(16).toByte() }
            return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { address[it % 6] }
        }
    }
}
