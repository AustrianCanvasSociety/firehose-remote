package app.burro.firehoseremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of SSDP: the datagram this app sends, and the reading of what
 * comes back. Every fixture is assembled with [CRLF] rather than written with
 * escape sequences, so no test file carries a backslash for the tooling to
 * mangle.
 */
class SsdpTest {

    private fun datagram(vararg headers: String): String =
        (listOf("HTTP/1.1 200 OK", "CACHE-CONTROL: max-age=1800") + headers + listOf("", ""))
            .joinToString(CRLF)

    // --- the question ----------------------------------------------------

    @Test
    fun mSearchAsksForTheDialService() {
        val request = Ssdp.searchRequest()
        val lines = request.split(CRLF)

        assertEquals("M-SEARCH * HTTP/1.1", lines.first())
        assertTrue(
            "the search must name the DIAL service, not ssdp:all — asking for everything also collects routers and NAS",
            request.contains("ST: ${Ssdp.SERVICE_TYPE}")
        )
        assertTrue("the multicast address the search is sent to", request.contains("HOST: 239.255.255.250:1900"))
        assertTrue("MX bounds how long a responder may wait", request.contains("MX: ${Ssdp.MX_SECONDS}"))
        assertTrue(
            "MAN must be quoted — a responder that validates it ignores an unquoted one",
            request.contains("MAN: \"ssdp:discover\"")
        )
        assertTrue("the datagram ends with a blank line", request.endsWith(CRLF + CRLF))
    }

    @Test
    fun theSearchIsRepeatedLikeTheVendorApp() {
        assertEquals("the official app repeats the search six times per discovery", 6, Ssdp.RETRIES)
    }

    // --- the answer ------------------------------------------------------

    @Test
    fun locationAndWakeupAreExtracted() {
        val parsed = Ssdp.parseResponse(
            datagram(
                "LOCATION: http://192.0.2.25:60000/dd.xml",
                "USN: uuid:abc::${Ssdp.SERVICE_TYPE}",
                "WAKEUP: MAC=00:00:5E:00:53:AA"
            ),
            sourceIp = "192.0.2.25"
        )

        assertEquals("192.0.2.25", parsed?.sourceIp)
        assertEquals("http://192.0.2.25:60000/dd.xml", parsed?.location)
        assertEquals("00:00:5E:00:53:AA", parsed?.wakeupMac)
    }

    @Test
    fun aReplyWithoutWakeupIsStillADevice() {
        // WAKEUP is an advertisement only newer Fire OS builds send. Requiring
        // it would make every older TV undiscoverable, and the header only ever
        // fed a wake-on-LAN feature that does not exist yet.
        val parsed = Ssdp.parseResponse(
            datagram("LOCATION: http://192.0.2.25:60000/dd.xml"),
            sourceIp = "192.0.2.25"
        )

        assertEquals("192.0.2.25", parsed?.sourceIp)
        assertNull("no WAKEUP header means no MAC, not a missing device", parsed?.wakeupMac)
    }

    @Test
    fun aReplyWithoutALocationIsNotADevice() {
        // LOCATION is the whole identity: it is both the proof that this host
        // serves DIAL and the address of the descriptor carrying the name.
        val parsed = Ssdp.parseResponse(
            datagram("USN: uuid:abc::${Ssdp.SERVICE_TYPE}"),
            sourceIp = "192.0.2.25"
        )

        assertNull(parsed)
    }

    @Test
    fun malformedResponseDoesNotThrow() {
        // Every UPnP device on the network sees this port, so an unrelated or
        // truncated packet is an ordinary Tuesday, not a fault in this process.
        assertNull(Ssdp.parseResponse("", "192.0.2.25"))
        assertNull(Ssdp.parseResponse("garbage", "192.0.2.25"))
        assertNull(Ssdp.parseResponse("HTTP/1.1 200 OK" + CRLF + "LOCATION: nonsense", "192.0.2.25"))
        assertNull(Ssdp.parseResponse(datagram("LOCATION: ftp://192.0.2.25/dd.xml"), "192.0.2.25"))
    }

    // --- the name --------------------------------------------------------

    @Test
    fun descriptorYieldsTheFriendlyName() {
        val descriptor = "<root><device><friendlyName>the test TV</friendlyName></device></root>"
        assertEquals("the test TV", Ssdp.parseFriendlyName(descriptor))
    }

    @Test
    fun theAppStatusDocumentIsNotADeviceName() {
        // The DIAL app-status endpoint answers with the *application's* name --
        // the same string on every Fire TV. Reading that as the device name
        // would have labelled the entire picker "FireTVRemote".
        val appStatus = "<service><name>FireTVRemote</name></service>"
        assertNull(Ssdp.parseFriendlyName(appStatus))
    }

    // --- what makes two answers the same device ---------------------------

    @Test
    fun theDeviceIsIdentifiedByItsAddressNotItsUuid() {
        val service = Ssdp.SERVICE_TYPE
        val claimed = "uuid:abc::$service"

        // This inverts what the test here asserted before. Keying on the uuid
        // listed one TV once when it answered from two addresses — the shape a
        // live scan found on 2026-09-17. But a uuid is an unauthenticated claim:
        // any host on the LAN reads the TV's own ssdp:alive multicast, repeats
        // it, and wins the first-wins dedupe, leaving the impostor as the only
        // entry for that TV. The address costs a duplicate listing and buys back
        // a picker that cannot be silently replaced.
        assertNotEquals(
            "one TV answering from two addresses is now listed twice -- the price of not trusting a claimed uuid",
            Ssdp.deviceKey(claimed, "192.0.2.25"),
            Ssdp.deviceKey(claimed, "192.0.2.31")
        )
        assertNotEquals(
            "a uuid claimed from somewhere else does not displace the address that owns it",
            Ssdp.deviceKey(claimed, "192.0.2.25"),
            Ssdp.deviceKey(claimed, "192.0.2.9")
        )
        assertEquals(
            "replies from one address are one device, whatever uuid they claim",
            Ssdp.deviceKey(claimed, "192.0.2.25"),
            Ssdp.deviceKey("uuid:def::$service", "192.0.2.25")
        )
        for (usn in listOf(null, "", "not-a-uuid", claimed)) {
            assertEquals("192.0.2.25", Ssdp.deviceKey(usn, "192.0.2.25"))
        }
    }

    @Test
    fun numericReferencesThatNameNoCharacterStayLiteral() {
        // `Int.toChar()` truncates instead of refusing, so an unguarded decode
        // turned `&#x10000;` and `&#0;` into a NUL and `&#x1F600;` into U+F600 —
        // characters the descriptor never contained, rendered to the user as a
        // device name. A reference that names no character stays as written.
        assertEquals(
            "the test TV &#x10000; &#0; &#x1F600; Room",
            Ssdp.parseFriendlyName("<friendlyName>the test TV &#x10000; &#0; &#x1F600; Room</friendlyName>")
        )
        assertEquals(
            "a surrogate half names no character either",
            "&#xD800;",
            Ssdp.parseFriendlyName("<friendlyName>&#xD800;</friendlyName>")
        )
    }

    @Test
    fun inRangeNumericReferencesStillDecode() {
        // The other half: refusing the out-of-range cases must not have cost the
        // ordinary ones, in either base.
        assertEquals("A", Ssdp.parseFriendlyName("<friendlyName>&#65;</friendlyName>"))
        assertEquals("A", Ssdp.parseFriendlyName("<friendlyName>&#x41;</friendlyName>"))
        assertEquals("the test TV & Room", Ssdp.parseFriendlyName("<friendlyName>the test TV &amp; Room</friendlyName>"))
    }

    @Test
    fun aDescriptorWithoutANameYieldsNull() {
        // Falls back to the address rather than to garbage -- which is exactly
        // how the app behaved before this step, so nothing is lost.
        assertNull(Ssdp.parseFriendlyName("<root><device></device></root>"))
        assertNull(Ssdp.parseFriendlyName("<root><friendlyName></friendlyName></root>"))
        assertNull(Ssdp.parseFriendlyName(""))
    }

    @Test
    fun entityEncodedNamesAreDecodedBeforeDisplay() {
        // The descriptor is scanned rather than parsed, but its text is shown to
        // the user: a device named "the test TV &amp; Room" rendered as that literal
        // markup in the picker.
        assertEquals(
            "the test TV & Room",
            Ssdp.parseFriendlyName(
                "<root><device><friendlyName>the test TV &amp; Room</friendlyName></device></root>"
            )
        )
        assertEquals(
            "predefined entities and numeric references both decode",
            "A&B",
            Ssdp.parseFriendlyName("<friendlyName>&#65;&amp;&#x42;</friendlyName>")
        )
        assertEquals(
            "an entity it does not know survives as itself",
            "x &bogus; y",
            Ssdp.parseFriendlyName("<friendlyName>x &bogus; y</friendlyName>")
        )
    }

    @Test
    fun aDoubledWakeupHeaderDoesNotYieldACompoundMac() {
        assertEquals("00:00:5E:00:53:AA", Ssdp.macFromWakeup("MAC=00:00:5E:00:53:AA"))
        assertEquals(
            "the first field wins, not the whole tail",
            "00:00:5E:00:53:AA",
            Ssdp.macFromWakeup("MAC=00:00:5E:00:53:AA;MAC=00:00:5E:00:53:BB")
        )
        assertNull("a value that is not a MAC is not a MAC", Ssdp.macFromWakeup("MAC=zzz"))
        assertNull("a short value is not a MAC", Ssdp.macFromWakeup("MAC=AA:BB"))
    }
}
