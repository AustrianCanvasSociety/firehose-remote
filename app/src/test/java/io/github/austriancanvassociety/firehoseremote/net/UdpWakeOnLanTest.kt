package io.github.austriancanvassociety.firehoseremote.net

import io.github.austriancanvassociety.firehoseremote.protocol.WakeOnLan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * The real sender against a real socket on the loopback — the half a fake
 * cannot show: that the bytes leave, whole, for the address they were meant for.
 */
class UdpWakeOnLanTest {

    @Test
    fun theMagicPacketArrivesByteForByte() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 2000
            val lines = mutableListOf<String>()
            val wol = UdpWakeOnLan(
                broadcastAddresses = { listOf(loopback) },
                port = receiver.localPort,
                log = { lines += it }
            )

            wol.send("00:00:5e:00:53:01")

            val buf = ByteArray(256)
            val got = DatagramPacket(buf, buf.size)
            receiver.receive(got)
            assertArrayEquals(WakeOnLan.magicPacket("00:00:5e:00:53:01"), buf.copyOf(got.length))
            assertTrue(lines.toString(), lines.single().startsWith("WOL 00:00:5e:00:53:01 -> 127.0.0.1:"))
        }
    }
}
