package io.github.austriancanvassociety.firehoseremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun scansViaFakeTransport() {
        val transport = FakeTransport()
        val alive = setOf("192.0.2.10", "192.0.2.42")
        transport.responder = { call ->
            val ip = call.url.substringAfter("https://").substringBefore(":")
            if (ip in alive) TransportResponse(200, "")
            else throw TransportTimeout("no host at $ip")
        }

        val result = Discovery(transport).scan("192.0.2.0/24")

        val ips = result.devices.map { it.ip }.toSet()
        assertEquals(alive, ips)
    }

    @Test
    fun emptySubnetReturnsEmptyList() {
        val transport = FakeTransport()
        transport.responder = { throw TransportTimeout("no host") }

        val result = Discovery(transport).scan("192.0.2.0/24")

        assertTrue("expected no devices when every probe times out", result.devices.isEmpty())
    }

    @Test
    fun connectionRefusedIsNotACandidate() {
        val transport = FakeTransport()
        transport.responder = { throw TransportConnectionRefused("wrong host") }

        val result = Discovery(transport).scan("192.0.2.0/24")

        assertTrue(result.devices.isEmpty())
    }

    // --- Step 3: "no" stopped standing in for three different facts ---------

    @Test
    fun timeoutIsDistinctFromEmpty() {
        // One host takes the connection and goes quiet; the rest have nothing
        // listening. Only the first is worth telling the user about, and the
        // old `null`-per-probe shape could not tell them apart.
        val transport = FakeTransport()
        transport.responder = { call ->
            val ip = call.url.substringAfter("https://").substringBefore(":")
            if (ip == "192.0.2.10") throw TransportTimeout("accepted, then never replied")
            else throw TransportConnectionRefused("nothing listening")
        }

        val result = Discovery(transport).scan("192.0.2.0/24")

        assertTrue("no device was found", result.devices.isEmpty())
        assertEquals("exactly one host went silent", 1, result.notAnswering)
        assertEquals("the remaining 253 refused", 253, result.refused)
        assertTrue("a silent host is not an empty network", result.sawSilentHost)
    }

    @Test
    fun refusalIsDistinctFromBothAnEmptyNetworkAndASilentHost() {
        // The third fact the four-case ProbeOutcome was introduced to separate,
        // and the one nothing consumed: every host refuses, so nothing took the
        // connection, but the range is not empty either — something answered.
        val transport = FakeTransport()
        transport.responder = { throw TransportConnectionRefused("nothing listening") }

        val result = Discovery(transport).scan("192.0.2.0/24")

        assertTrue("no device was found", result.devices.isEmpty())
        assertEquals("nothing went silent", 0, result.notAnswering)
        assertEquals("every host refused", 254, result.refused)
        assertTrue("a refusing network is not an empty one", result.sawRefusingHost)
        assertTrue("and it is not the silent-host case", !result.sawSilentHost)
    }

    @Test
    fun probeErrorIsNotSwallowed() {
        // An Error is a fault in this process, not a fact about a host. Folding
        // it into "no Fire TV here" reports a broken scan as an empty network.
        val transport = FakeTransport()
        val fault = Error("simulated process fault")
        transport.responder = { throw fault }

        var propagated: Throwable? = null
        try {
            Discovery(transport).scan("192.0.2.0/24")
        } catch (e: Error) {
            propagated = e
        }

        assertTrue(
            "an Error must propagate rather than reading as 'no device here', got: $propagated",
            propagated === fault
        )
    }

    @Test
    fun enumerateHostsCoversTheUsableRange() {
        val hosts = Discovery(FakeTransport()).enumerateHosts("192.0.2.0/24")
        assertEquals(254, hosts.size)
        assertEquals("192.0.2.1", hosts.first())
        assertEquals("192.0.2.254", hosts.last())
    }

    // --- Step 3: the search first, the sweep only if it comes back empty ---

    /** Calls to the command API port, as opposed to the descriptor fetches. */
    private fun FakeTransport.commandApiCalls() = calls.filter { it.url.contains(":8080") }

    /**
     * The `WAKEUP` MAC a device announces rides along with it — it is the only
     * address a Wake-on-LAN packet can be built from, and the scan is the only
     * time this app hears it. A device that announces none carries none.
     */
    @Test
    fun anAnswerCarriesItsWakeupMacToTheDevice() {
        val transport = FakeTransport()
        transport.responder = { TransportResponse(404, "") }
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response("192.0.2.25", wakeupMac = "00:00:5e:00:53:01"),
                FakeSsdp.response("192.0.2.22")
            )
        )

        val devices = Discovery(transport, ssdp).scan("192.0.2.0/24").devices

        assertEquals("00:00:5e:00:53:01", devices.first { it.ip == "192.0.2.25" }.wakeupMac)
        assertEquals(null, devices.first { it.ip == "192.0.2.22" }.wakeupMac)
    }

    /**
     * Answers from one address that disagree about its MAC give it none. The
     * dedupe keeps the first answer, and a forged one can arrive first — so the
     * MAC is read across every answer, and an answer with no MAC does not veto
     * one that has it.
     */
    @Test
    fun answersFromOneAddressThatDisagreeAboutAMacGiveItNone() {
        val transport = FakeTransport()
        transport.responder = { TransportResponse(404, "") }
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response("192.0.2.25", usn = "uuid:forged::${Ssdp.SERVICE_TYPE}", wakeupMac = "00:00:5e:00:53:99"),
                FakeSsdp.response("192.0.2.25", wakeupMac = "00:00:5e:00:53:01"),
                FakeSsdp.response("192.0.2.22", usn = "uuid:one::${Ssdp.SERVICE_TYPE}"),
                FakeSsdp.response("192.0.2.22", wakeupMac = "00:00:5e:00:53:02")
            )
        )

        val devices = Discovery(transport, ssdp).scan("192.0.2.0/24").devices

        assertEquals("one entry per address", 2, devices.size)
        assertEquals(null, devices.single { it.ip == "192.0.2.25" }.wakeupMac)
        assertEquals("00:00:5e:00:53:02", devices.single { it.ip == "192.0.2.22" }.wakeupMac)
    }

    @Test
    fun aDeviceIsNamedFromItsOwnDescriptor() {
        val transport = FakeTransport()
        transport.responder = {
            TransportResponse(200, "<root><device><friendlyName>the test TV</friendlyName></device></root>")
        }
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.25")))

        val result = Discovery(transport, ssdp).scan("192.0.2.0/24")

        assertEquals(1, result.devices.size)
        assertEquals("the test TV", result.devices.first().name)
        assertEquals(
            "the descriptor is read from the LOCATION the answer itself carried",
            "http://192.0.2.25:60000/dd.xml",
            transport.calls.first().url
        )
    }

    @Test
    fun anAnswerIsADeviceEvenWhenItsDescriptorCannotBeRead() {
        // The search already proved this host serves DIAL. Losing the name must
        // not lose the device — a missing label costs a word, a missing device
        // costs the pairing.
        val transport = FakeTransport()
        transport.responder = { throw TransportTimeout("descriptor host too slow") }
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.25")))

        val result = Discovery(transport, ssdp).scan("192.0.2.0/24")

        assertEquals(1, result.devices.size)
        assertEquals("192.0.2.25", result.devices.first().name)
    }

    @Test
    fun aDeviceThatAnswersFromTwoAddressesIsListedTwiceByChoice() {
        // Found live on 2026-09-17: the scan listed "Home Gym" twice,
        // from two addresses, where the vendor app listed it once. De-duplicating
        // on the source address cannot see that those are one device; the USN's
        // uuid can.
        //
        // The uuid is deliberately not used, and this test now asserts that. A
        // uuid is an unauthenticated claim: any host on the LAN reads the real
        // TV's own ssdp:alive multicast and repeats it, and with first-wins
        // dedupe the impostor becomes the only entry for that TV — so the user
        // pairs with it and the token the TV mints passes through the attacker.
        // A duplicate listing is the price; both entries here are genuinely
        // reachable, which the impostor's would not be.
        val service = Ssdp.SERVICE_TYPE
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response("192.0.2.25", usn = "uuid:one-device::$service"),
                FakeSsdp.response("192.0.2.31", usn = "uuid:one-device::$service")
            )
        )

        val devices = Discovery(transport, ssdp).scan("192.0.2.0/24").devices

        assertEquals(
            "two addresses are two entries now -- a duplicate listing, not a replaced TV",
            2,
            devices.size
        )
        assertEquals(
            "and both are listed at their own address",
            listOf("192.0.2.25", "192.0.2.31"),
            devices.map { it.ip }
        )
    }

    @Test
    fun aUuidClaimedFromElsewhereDoesNotDisplaceTheDeviceThatOwnsIt() {
        // The attack the address key closes. The impostor answers first (a TV
        // waits a randomised MX), claiming the real TV's uuid from its own
        // address; before this, first-wins dedupe dropped the real TV and left
        // the impostor as the only entry, so the user paired through it and the
        // minted token was its to keep.
        val service = Ssdp.SERVICE_TYPE
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response("192.0.2.9", usn = "uuid:the-real-tv::$service"),
                FakeSsdp.response("192.0.2.25", usn = "uuid:the-real-tv::$service")
            )
        )

        val devices = Discovery(transport, ssdp).scan("192.0.2.0/24").devices

        assertTrue(
            "the real TV must still be listed at its own address: ${devices.map { it.ip }}",
            devices.any { it.ip == "192.0.2.25" }
        )
    }

    @Test
    fun twoDevicesWithDifferentIdsAreBothListed() {
        // The other half: de-duplication must not collapse two genuinely
        // separate TVs that happen to share a name — two rooms can each call
        // their TV the same thing.
        val service = Ssdp.SERVICE_TYPE
        val transport = FakeTransport()
        transport.responder = {
            TransportResponse(200, "<root><device><friendlyName>Room</friendlyName></device></root>")
        }
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response("192.0.2.25", usn = "uuid:first::$service"),
                FakeSsdp.response("192.0.2.31", usn = "uuid:second::$service")
            )
        )

        assertEquals(2, Discovery(transport, ssdp).scan("192.0.2.0/24").devices.size)
    }

    @Test
    fun theSweepRunsOnlyWhenSsdpIsEmpty() {
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.25")))

        Discovery(transport, ssdp).scan("192.0.2.0/24")

        assertEquals("one search per scan", 1, ssdp.searches.size)
        assertTrue(
            "the search answered, so no host should have been probed",
            transport.commandApiCalls().isEmpty()
        )
    }

    @Test
    fun aHostThatNeverAnsweredSsdpIsNotAFireTv() {
        // The search is the only thing that proves a host serves DIAL. A web
        // server sitting on the command port answers any probe with `200` --
        // the old rule read exactly that as a Fire TV -- so the question is
        // whether such a host can reach the list at all. It cannot: candidates
        // come from the search, and no sweep runs behind it to find one.
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.25")))

        val result = Discovery(transport, ssdp).scan("192.0.2.0/24")

        assertEquals(
            "only the host that answered the search may be listed",
            setOf("192.0.2.25"),
            result.devices.map { it.ip }.toSet()
        )
        assertTrue(
            "a host that stayed silent is not found by probing it",
            transport.commandApiCalls().isEmpty()
        )
    }

    @Test
    fun aFloodOfRespondersIsBounded() {
        // Any host on the LAN sees the M-SEARCH and can answer it, and each
        // distinct uuid a stranger invents costs one descriptor fetch — run
        // serially, on the worker thread the whole UI shares. The amount of work
        // a stranger can ask this phone to do has to be bounded.
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }
        val flood = (1..500).map {
            FakeSsdp.response(
                ip = "192.0.2.${it % 250 + 1}",
                usn = "uuid:flood-$it::${Ssdp.SERVICE_TYPE}"
            )
        }

        val result = Discovery(transport, FakeSsdp(flood)).scan("192.0.2.0/24")

        assertTrue(
            "500 responders must not become 500 descriptor fetches, got ${transport.calls.size}",
            transport.calls.size <= 32
        )
        assertTrue("the list is bounded too", result.devices.size <= 32)
    }

    @Test
    fun theSweepRunsWhenSsdpHearsNothing() {
        // The case the sweep is kept for: a network that carries unicast and
        // drops multicast. The TVs are reachable; the search just cannot hear
        // them.
        val transport = FakeTransport()
        transport.responder = { throw TransportConnectionRefused("nothing listening") }

        Discovery(transport, FakeSsdp(emptyList())).scan("192.0.2.0/24")

        assertEquals("the whole range must still be covered", 254, transport.commandApiCalls().size)
    }

    @Test
    fun aSearchThatFailsStillFallsThroughToTheSweep() {
        val transport = FakeTransport()
        transport.responder = { throw TransportConnectionRefused("nothing listening") }
        val ssdp = FakeSsdp().apply { failure = RuntimeException("multicast unavailable") }

        Discovery(transport, ssdp).scan("192.0.2.0/24")

        assertEquals(254, transport.commandApiCalls().size)
    }

    // --- Step 3: what the sweep asks, and what counts as an answer ---------

    @Test
    fun nonFireTvStatusIsNotACandidate() {
        // The old rule accepted any 2xx-4xx, so a router's admin page or a dev
        // server on the port was offered in the picker as a TV. `404` and `405`
        // mean the path is not this API.
        for (status in listOf(404, 405, 500)) {
            val transport = FakeTransport()
            transport.responder = { TransportResponse(status, "") }

            val result = Discovery(transport, FakeSsdp()).scan("192.0.2.0/24")

            assertTrue("a $status on the status path is not a Fire TV", result.devices.isEmpty())
        }
    }

    @Test
    fun anAuthGatedAnswerIsStillAFireTv() {
        // `401`/`403` prove the path exists and a REST API answered it. The wake
        // path already reads a 4xx here as "the device is up"; the sweep has to
        // agree, or a TV that is merely asking for a token would disappear from
        // the picker.
        for (status in listOf(200, 401, 403)) {
            val transport = FakeTransport()
            transport.responder = { TransportResponse(status, "") }

            val result = Discovery(transport, FakeSsdp()).scan("192.0.2.0/24")

            assertEquals("a $status is the command API answering", 254, result.devices.size)
        }
    }

    @Test
    fun theSweepAsksTheStatusPath() {
        val transport = FakeTransport()
        transport.responder = { TransportResponse(200, "") }

        Discovery(transport, FakeSsdp()).scan("192.0.2.0/24")

        assertTrue(
            "the sweep must ask an endpoint that separates this API from any other web server",
            transport.commandApiCalls().all { it.url.endsWith("/v1/FireTV/status") }
        )
    }

    // --- Step 3: a malformed range is refused before anything is contacted --

    @Test
    fun malformedCidrIsRejectedBeforeEnumerating() {
        for (bad in listOf("999.999.999.0/24", "abc.def.ghi.jkl/24", "-1.-1.-1.-1/24", "192.0.2.junk/24")) {
            val transport = FakeTransport()
            val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.25")))

            var refused = false
            try {
                Discovery(transport, ssdp).scan(bad)
            } catch (e: IllegalArgumentException) {
                refused = true
            }

            assertTrue("$bad must be refused rather than enumerating nonsense hosts", refused)
            assertTrue("$bad must be refused before any probe", transport.commandApiCalls().isEmpty())
            assertEquals("$bad must be refused before even the search runs", 0, ssdp.searches.size)
        }
    }
}
