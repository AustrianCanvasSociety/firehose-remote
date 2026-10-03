package io.github.austriancanvassociety.firehoseremote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WakeOnLanTest {

    /** The standard shape: six `0xFF` bytes, then the MAC sixteen times — 102 bytes. */
    @Test
    fun aMagicPacketIsSixFFsThenTheMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket("00:00:5e:00:53:01")
        val mac = byteArrayOf(0x00, 0x00, 0x5e, 0x00, 0x53, 0x01)

        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) {
            assertArrayEquals("repetition $i", mac, packet.copyOfRange(6 + 6 * i, 12 + 6 * i))
        }
    }

    /**
     * Only the shape `WAKEUP` carries — six colon-separated hex pairs — is
     * accepted. Anything else is refused rather than guessed at: a packet
     * built from a wrong address wakes nothing and says nothing.
     */
    @Test
    fun aMalformedMacIsRefused() {
        for (bad in listOf("", "00:00:5e:00:53", "00-00-5e-00-53-01", "00:00:5e:00:53:zz", "00:00:5e:00:53:01:00")) {
            try {
                WakeOnLan.magicPacket(bad)
                fail("expected \"$bad\" to be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
