package io.github.austriancanvassociety.firehoseremote.ui

import io.github.austriancanvassociety.firehoseremote.R
import io.github.austriancanvassociety.firehoseremote.protocol.Device
import io.github.austriancanvassociety.firehoseremote.protocol.FakeSsdp
import io.github.austriancanvassociety.firehoseremote.protocol.FakeTransport
import io.github.austriancanvassociety.firehoseremote.protocol.FakeWakeOnLan
import io.github.austriancanvassociety.firehoseremote.protocol.PairedDevice
import io.github.austriancanvassociety.firehoseremote.protocol.SsdpSearch
import io.github.austriancanvassociety.firehoseremote.protocol.TestClock
import io.github.austriancanvassociety.firehoseremote.protocol.TokenStore
import io.github.austriancanvassociety.firehoseremote.protocol.TransportConnectionRefused
import io.github.austriancanvassociety.firehoseremote.protocol.TransportResponse
import io.github.austriancanvassociety.firehoseremote.protocol.TransportTimeout
import io.github.austriancanvassociety.firehoseremote.protocol.WakeOnLan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan → select → pair orchestration, driven entirely over the `Transport`
 * and `TokenStore` seams. No device, no Android runtime — that is the point of
 * keeping [PairingFlow] free of `android.*` imports.
 */
class PairingFlowTest {

    private val cidr = "192.0.2.0/24"

    /** A MAC in the shape `WAKEUP` carries. */
    private val MAC = "00:00:5e:00:53:aa"
    private val OTHER_MAC = "00:00:5E:00:53:CC"
    private val TV_A = "192.0.2.10"
    private val TV_B = "192.0.2.11"
    private val tv = Device(name = "192.0.2.10", ip = "192.0.2.10")

    /**
     * Multi-entry fake honouring the whole [TokenStore] contract from Phase 3
     * Step 9. The one-argument constructor is preserved verbatim so the ~40
     * existing single-pairing call sites in this file keep compiling and
     * asserting the same thing — a single seed becomes a one-entry collection
     * with that host selected, which is the single-pairing shape those tests
     * were written against.
     */
    private class FakeTokenStore(stored: PairedDevice? = null) : TokenStore {
        private val entries: MutableList<PairedDevice> =
            if (stored != null) mutableListOf(stored) else mutableListOf()
        private var selectedHost: String? = stored?.host

        var saved: PairedDevice? = null
        var saveCount = 0
        var clearCount = 0
        var removeCount = 0

        /**
         * Multi-entry seed. [selected] must be one of the hosts in [stored] —
         * anything else is a bug in the test setup and worth failing loudly
         * on rather than silently defaulting.
         */
        constructor(stored: List<PairedDevice>, selected: String) : this(null) {
            entries.addAll(stored)
            require(entries.any { it.host == selected }) {
                "FakeTokenStore(stored, selected=$selected): the selected host is not in the stored list"
            }
            selectedHost = selected
        }

        override fun load(): PairedDevice? =
            selectedHost?.let { host -> entries.firstOrNull { it.host == host } }

        override fun all(): List<PairedDevice> = entries.toList()

        override fun save(device: PairedDevice) {
            entries.removeAll { it.host == device.host }
            entries.add(device)
            selectedHost = device.host
            saved = device
            saveCount++
        }

        override fun select(host: String): PairedDevice? {
            val target = entries.firstOrNull { it.host == host } ?: return null
            selectedHost = host
            return target
        }

        override fun rememberWakeupMac(host: String, mac: String) {
            val i = entries.indexOfFirst { it.host == host }
            if (i >= 0) entries[i] = entries[i].copy(wakeupMac = mac)
        }

        override fun remove(host: String) {
            val wasSelected = selectedHost == host
            val existed = entries.removeAll { it.host == host }
            if (!existed) return
            removeCount++
            if (wasSelected) selectedHost = entries.firstOrNull()?.host
        }

        override fun clear() {
            entries.clear()
            selectedHost = null
            clearCount++
        }
    }

    private fun flow(
        transport: FakeTransport,
        store: TokenStore = FakeTokenStore(),
        clock: TestClock = TestClock(),
        ssdp: SsdpSearch = SsdpSearch.None,
        wakeOnLan: WakeOnLan = WakeOnLan.None,
        introSeen: () -> Boolean = { true },
        markIntroSeen: () -> Unit = {}
    ) = PairingFlow(
        transport = transport,
        tokenStore = store,
        cidrProvider = { cidr },
        clock = clock,
        ssdp = ssdp,
        wakeOnLan = wakeOnLan,
        introSeen = introSeen,
        markIntroSeen = markIntroSeen
    )

    private fun discoveryResponder(alive: Set<String>): (FakeTransport.Call) -> TransportResponse = { call ->
        val ip = call.url.substringAfter("https://").substringBefore(":")
        if (ip in alive) TransportResponse(200, "") else throw TransportTimeout("no host at $ip")
    }

    @Test
    fun scanWhereNothingIsListeningReportsRefusalRatherThanAnEmptyNetwork() {
        // Every host on the range refuses the connection. That is a third fact:
        // a refusal proves a host is there with nothing listening on the control
        // port, which is what an idle Fire TV presents (`docs/protocol.md § 3`).
        // The count existed before this but reached no screen — it was computed
        // and thrown away, so a network full of refusing hosts read as an empty
        // one and the user got nothing to act on.
        val transport = FakeTransport()
        transport.responder = { throw TransportConnectionRefused("nothing listening") }

        val state = flow(transport).scan()

        assertTrue(
            "a refusing network must not read as an empty one, got $state",
            state is PairingFlow.State.HostRefused
        )
    }

    @Test
    fun scanListsDiscoveredDevices() {
        val transport = FakeTransport()
        val alive = setOf("192.0.2.10", "192.0.2.42")
        transport.responder = discoveryResponder(alive)

        val state = flow(transport).scan()

        assertTrue("expected a device list, got $state", state is PairingFlow.State.Discovered)
        val devices = (state as PairingFlow.State.Discovered).devices
        // `Discovery` labels a device with its host until the friendly-name path
        // is resolved against real hardware.
        assertEquals(alive, devices.map { it.name }.toSet())
        assertEquals(alive, devices.map { it.ip }.toSet())
    }

    @Test
    fun selectThenPairPersistsToken() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val store = FakeTokenStore()
        val flow = flow(transport, store)

        val selected = flow.select(tv)
        assertTrue("expected PIN entry, got $selected", selected is PairingFlow.State.AwaitingPin)

        val paired = flow.pair(tv, "1234")

        assertTrue("expected a paired state, got $paired", paired is PairingFlow.State.Paired)
        assertEquals("TOKEN-abc", (paired as PairingFlow.State.Paired).device.token)
        assertEquals(PairedDevice(host = tv.ip, name = tv.name, token = "TOKEN-abc"), store.saved)
    }

    @Test
    fun rejectedPinSurfacesWithoutPersisting() {
        val transport = FakeTransport()
        transport.responder = { call ->
            when {
                call.url.contains("pin/display") -> TransportResponse(200, "")
                call.url.contains("pin/verify") -> TransportResponse(403, """{"description":"invalid"}""")
                else -> TransportResponse(404, "")
            }
        }
        val store = FakeTokenStore()
        val flow = flow(transport, store)

        val state = flow.pair(tv, "0000")

        assertTrue("expected a failure, got $state", state is PairingFlow.State.Failed)
        assertTrue(
            "failure must carry a message for the user",
            (state as PairingFlow.State.Failed).message.isNotBlank()
        )
        assertNull("a rejected PIN must not persist anything", store.saved)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun aTvThatAnswersWithAnErrorIsNotReportedAsNotAnswering() {
        // The TV is on and replying — it just refused to show a PIN. Reporting
        // "didn't answer" sends the user to check a power cable on a device that
        // has plainly just spoken.
        val transport = FakeTransport()
        transport.responder = { TransportResponse(500, "") }
        val flow = flow(transport, FakeTokenStore())

        val state = flow.select(tv)

        assertTrue("expected a failure, got $state", state is PairingFlow.State.Failed)
        val failed = state as PairingFlow.State.Failed
        assertFalse(
            "a TV that answered must not be reported as not answering, got: ${failed.message}",
            failed.message.contains("didn't answer")
        )
        assertEquals(PairingFlow.State.Cause.PairingRefused, failed.cause)
    }

    @Test
    fun storedTokenSkipsScanOnRelaunch() {
        val transport = FakeTransport()
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "TOKEN-abc")

        val state = flow(transport, FakeTokenStore(stored)).start()

        assertTrue("expected the stored pairing, got $state", state is PairingFlow.State.Paired)
        assertEquals(stored, (state as PairingFlow.State.Paired).device)
        assertTrue("a stored token must skip the scan entirely", transport.calls.isEmpty())
    }

    @Test
    fun retryOnLiteralOkReachesFlow() {
        val transport = FakeTransport()
        var verifyAttempts = 0
        transport.responder = { call ->
            when {
                call.url.contains("pin/display") -> TransportResponse(200, "")
                call.url.contains("pin/verify") -> {
                    verifyAttempts++
                    if (verifyAttempts == 1) {
                        TransportResponse(200, """{"description":"OK"}""")
                    } else {
                        TransportResponse(200, """{"description":"TOKEN-abc"}""")
                    }
                }
                else -> TransportResponse(404, "")
            }
        }
        val clock = TestClock()

        val state = flow(transport, clock = clock).pair(tv, "1234")

        // "OK" is "not yet", not a token: the flow must retry and carry the real
        // token out, never surface the literal string as the pairing result.
        assertTrue("expected a paired state, got $state", state is PairingFlow.State.Paired)
        assertEquals("TOKEN-abc", (state as PairingFlow.State.Paired).device.token)
        assertEquals("expected the retry to reach the TV twice", 2, verifyAttempts)
        assertEquals("expected exactly one 1 s backoff", listOf(1000L), clock.sleepCalls)
    }

    // --- Step 3: failures name their cause, and a pairing is not a dead end --

    @Test
    fun failureNamesTheRealCause() {
        val transport = FakeTransport()
        transport.responder = { call ->
            when {
                call.url.contains("pin/display") -> TransportResponse(200, "")
                call.url.contains("pin/verify") ->
                    TransportResponse(200, """{"description":"Invalid PIN"}""")
                else -> TransportResponse(404, "")
            }
        }
        val store = FakeTokenStore()

        val state = flow(transport, store).pair(tv, "0000")

        assertTrue("expected a failure, got $state", state is PairingFlow.State.Failed)
        val failed = state as PairingFlow.State.Failed
        assertEquals(PairingFlow.State.Cause.PairingRefused, failed.cause)
        assertNull("a refused pairing must not persist anything", store.saved)
        assertTrue(
            "no exception's own words may reach the user: ${failed.message}",
            !failed.message.contains("firehose-remote:")
        )
    }

    @Test
    fun rescanIsReachableFromThePairedState() {
        val transport = FakeTransport()
        val alive = setOf("192.0.2.10")
        transport.responder = discoveryResponder(alive)
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "wBMBFhY")
        val flow = flow(transport, FakeTokenStore(stored))

        assertTrue("expected the stored pairing on launch",
            flow.start() is PairingFlow.State.Paired)

        val state = flow.scan()

        // Nothing has gone wrong here — the TV is answering. Scanning is this
        // app's own primary action, so the route back must not be gated on a
        // failure to trigger it.
        assertTrue(
            "scanning must be reachable from the paired state: $state",
            state is PairingFlow.State.Discovered
        )
        assertEquals(alive, (state as PairingFlow.State.Discovered).devices.map { it.ip }.toSet())
    }

    @Test
    fun forgettingClearsTheStoredToken() {
        val transport = FakeTransport()
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "wBMBFhY")
        val store = FakeTokenStore(stored)
        val flow = flow(transport, store)

        val state = flow.forget()

        assertTrue("expected the scan screen, got $state", state is PairingFlow.State.Scanning)
        assertEquals("the pairing must be dropped, not merely hidden", 1, store.removeCount)
        assertNull(store.load())
        assertEquals(
            "the next launch must scan rather than short-circuit to the paired screen",
            PairingFlow.State.Scanning,
            flow.start()
        )
    }

    // --- pressing controls -------------------------------------------------

    private fun pairedStore(host: String = "192.0.2.10") =
        FakeTokenStore(PairedDevice(host = host, name = "192.0.2.10", token = "TOKEN_ABC"))

    @Test
    fun pressingAButtonSendsOneKeyEvent() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }
        var result: String? = "unset"

        flow(transport, store = pairedStore(), clock = clock)
            .press("dpad_right", keyed = true) { result = it }

        assertEquals("two requests, in order", 2, transport.calls.size)
        assertTrue("keyDown first", transport.calls[0].body!!.contains("keyDown"))
        assertTrue("keyUp second", transport.calls[1].body!!.contains("keyUp"))
        assertEquals(
            "the stored token rides both requests",
            setOf("TOKEN_ABC"),
            transport.calls.map { it.headers["X-Client-Token"] }.toSet()
        )
        assertNull("success reports no failure", result)
    }

    /**
     * A phone off Wi-Fi cannot reach the TV, and a press that tried would run
     * the whole 40 s wake before saying so. It is told at once, and nothing is
     * sent.
     */
    @Test
    fun aPressWithNoNetworkSaysSoAtOnceAndSendsNothing() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val flow = PairingFlow(
            transport = transport,
            tokenStore = FakeTokenStore(PairedDevice(host = TV_A, name = "Living room", token = "TOKEN_ABC")),
            cidrProvider = { null },
            clock = clock,
            ssdp = SsdpSearch.None,
            wakeOnLan = WakeOnLan.None
        )
        var reported: String? = null

        val next = flow.press("dpad_right", keyed = true) { reported = it }

        assertEquals("Connect to the same Wi-Fi as Living room.", reported)
        assertNull("no follow-up screen", next)
        assertTrue("nothing sent", transport.calls.isEmpty())
        assertTrue("no time spent waiting", clock.sleepCalls.isEmpty())
    }

    private fun vpnFlow(transport: FakeTransport, clock: TestClock, vpnOn: Boolean) = PairingFlow(
        transport = transport,
        tokenStore = FakeTokenStore(PairedDevice(host = TV_A, name = "the test TV", token = "TOKEN_ABC")),
        cidrProvider = { cidr },
        clock = clock,
        ssdp = SsdpSearch.None,
        wakeOnLan = WakeOnLan.None,
        vpnOn = { vpnOn }
    )

    /**
     * A VPN that tunnels everything keeps the phone off the home network, and
     * the press fails exactly like a TV that is off (measured 2026-09-28 on the
     * Redmi 13 with a VPN app). With a VPN on, that is the likelier cause, so the
     * message names it rather than blaming the TV.
     */
    @Test
    fun aPressThatReachesNothingWithAVpnOnNamesTheVpn() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { throw TransportTimeout("tunnelled away") }
        var reported: String? = null

        vpnFlow(transport, clock, vpnOn = true).press("dpad_right", keyed = true) { reported = it }

        assertEquals(
            "Couldn't reach the test TV. A VPN is on, and it can keep this phone off your home network — " +
                "turn it off, or allow local network access in the VPN app, then try again.",
            reported
        )
    }

    /** A refused port with a VPN on fails the same way, and names the VPN the same way. */
    @Test
    fun aRefusedPressWithAVpnOnNamesTheVpn() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { throw TransportConnectionRefused("nothing listening") }
        var reported: String? = null

        vpnFlow(transport, clock, vpnOn = true).press("dpad_right", keyed = true) { reported = it }

        assertTrue("names the VPN: $reported", reported!!.contains("A VPN is on"))
    }

    /** The TV answered and said no: the VPN did not stop anything, so it goes unmentioned. */
    @Test
    fun aPressTheTvRefusedDoesNotBlameTheVpn() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(500, "") }
        var reported: String? = null

        vpnFlow(transport, clock, vpnOn = true).press("dpad_right", keyed = true) { reported = it }

        assertFalse("no VPN blame: $reported", reported!!.contains("VPN"))
    }

    /** Without a VPN, a press that reaches nothing keeps its own message. */
    @Test
    fun aPressThatReachesNothingWithNoVpnKeepsItsMessage() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { throw TransportTimeout("asleep") }
        var reported: String? = null

        vpnFlow(transport, clock, vpnOn = false).press("dpad_right", keyed = true) { reported = it }

        assertEquals("the test TV didn't answer, even after a wake.", reported)
    }

    /** Off Wi-Fi, the scan says Wi-Fi — a phone on mobile data is on a network. */
    @Test
    fun aScanWithNoNetworkNamesWiFi() {
        val transport = FakeTransport()
        val flow = PairingFlow(
            transport = transport,
            tokenStore = FakeTokenStore(),
            cidrProvider = { null },
            clock = TestClock(),
            ssdp = SsdpSearch.None,
            wakeOnLan = WakeOnLan.None
        )

        val state = flow.scan() as PairingFlow.State.Failed

        assertEquals(PairingFlow.State.Cause.NoNetwork, state.cause)
        assertEquals("This phone isn't on Wi-Fi. Join the same Wi-Fi as your TV, then scan again.", state.message)
        assertTrue("nothing sent", transport.calls.isEmpty())
    }

    /**
     * A press stopped because the screen is going away is not a TV that could
     * not be reached. The interrupt leaves [PairingFlow.press] as itself, so the
     * screen can end the task quietly, and nothing is reported as a failure —
     * the guard against a future catch-everything here.
     */
    @Test
    fun anInterruptedPressIsNotReportedAsAFailure() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{}") }
        val interrupting = object : io.github.austriancanvassociety.firehoseremote.protocol.Clock {
            override fun nowMillis(): Long = clock.nowMillis()
            override fun sleep(millis: Long) { throw InterruptedException("simulated shutdownNow") }
        }
        val flow = PairingFlow(
            transport = transport,
            tokenStore = pairedStore(),
            cidrProvider = { cidr },
            clock = interrupting,
            ssdp = SsdpSearch.None,
            wakeOnLan = WakeOnLan.None
        )
        var reported: String? = "unset"
        var interrupted = false

        try {
            flow.press("dpad_right", keyed = true) { reported = it }
        } catch (e: InterruptedException) {
            interrupted = true
        }

        assertTrue("the interrupt must propagate out of press", interrupted)
        assertEquals("no failure is reported for a stopped press", "unset", reported)
        assertTrue("the owed keyUp still went out",
            transport.calls.any { "keyUp" in (it.body ?: "") })
    }

    /**
     * Every control the remote can draw reaches the wire as itself, on the
     * endpoint its own action names.
     *
     * Driven by the control list rather than a list written here, so it covers
     * whatever ships: a control added to the remote and not to the routing fails
     * here as a wrong URL rather than on a TV as an unexplained silence. The
     * expected endpoint is written out per action instead of built from the
     * control, because a control routed to the wrong place would otherwise
     * supply its own expectation.
     */
    @Test
    fun everyShippedControlReachesItsOwnEndpoint() {
        val shipped = ALL_CONTROLS.filter { it.present }
        assertTrue("the remote must have controls", shipped.isNotEmpty())
        val seen = mutableSetOf<String>()

        for (control in shipped) {
            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

            flow(transport, store = pairedStore(), clock = clock).press(control.action, control.keyed) { }

            // The shuttle actions carry app-side aliases (`scan_back`,
            // `scan_forward`) that differ from the wire action `scan` — one wire
            // vocabulary, two directions. Every other control's alias equals the
            // wire action.
            val expectedUrl = when (control.action) {
                PairingFlow.ACTION_PLAY -> "/v1/media?action=play"
                PairingFlow.ACTION_SCAN_BACK, PairingFlow.ACTION_SCAN_FORWARD -> "/v1/media?action=scan"
                else -> "/v1/FireTV?action=${control.action}"
            }
            assertTrue(
                "${control.action} must reach $expectedUrl; got ${transport.calls.first().url}",
                transport.calls.first().url.endsWith(expectedUrl)
            )
            assertTrue("${control.action} collapsed with another control", seen.add(control.action))
        }
    }

    /**
     * The two shuttle controls share the wire vocabulary — `POST /v1/media?action=scan` —
     * and differ only in the request body's `direction` field. Routing on
     * wire-vocabulary alone would collapse the pair; routing on the app-side
     * alias keeps them distinct. This test proves the alias reaches the wire
     * intact, not just that the endpoint URL matches.
     */
    @Test
    fun shuttleDirectionsRouteToDistinctRequestBodies() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }
        val flow = flow(transport, store = pairedStore(), clock = clock)

        flow.press(PairingFlow.ACTION_SCAN_BACK, keyed = false) { }
        flow.press(PairingFlow.ACTION_SCAN_FORWARD, keyed = false) { }

        val bodies = transport.calls.map { it.body }
        assertEquals(
            "rewind then fast-forward must land as two calls with distinct direction bodies",
            listOf(
                """{"direction":"back","durationInSeconds":"10","speed":"1"}""",
                """{"direction":"forward","durationInSeconds":"10","speed":"1"}"""
            ),
            bodies
        )
    }

    /**
     * A press that has to wake the TV says so while it waits, by the TV's name.
     * A wake can take many seconds with the controls greyed out, and a remote
     * that sits greyed with no word reads as a broken one.
     */
    @Test
    fun aPressThatWakesTheTvSaysSoWhileItWaits() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                "/apps/FireTVRemote" in call.url -> TransportResponse(201, "")
                else -> if (attempts++ == 0) throw TransportConnectionRefused("idle") else TransportResponse(200, "{}")
            }
        }
        val store = FakeTokenStore(PairedDevice(host = "192.0.2.10", name = "TestTV", token = "TOKEN_ABC"))
        val progress = mutableListOf<String>()
        var result: String? = "unset"

        flow(transport, store = store, clock = clock)
            .press("home", keyed = false, onWaking = { progress += it }) { result = it }

        assertEquals(listOf("Waking TestTV…"), progress)
        assertNull("the press still lands", result)
    }

    // --- Wake-on-LAN: the MAC a scan hears is the one a wake sends to -------

    /** Pairing keeps the `WAKEUP` MAC the scan heard, so a later wake can use it. */
    @Test
    fun pairingKeepsTheWakeupMacTheScanHeard() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val store = FakeTokenStore()
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.10", wakeupMac = MAC)))
        val flow = flow(transport, store, ssdp = ssdp)

        val found = (flow.scan() as PairingFlow.State.Discovered).devices.single()
        flow.select(found)
        flow.pair(found, "1234")

        assertEquals(MAC, store.saved?.wakeupMac)
    }

    /**
     * A scan refreshes the MAC of a TV already paired — which is how a pairing
     * made before the MAC was kept learns it — and leaves the selection where it
     * was: a scan is not a choice of TV.
     */
    @Test
    fun aScanRefreshesAStoredTvsWakeupMacAndLeavesTheSelectionAlone() {
        val transport = FakeTransport()
        transport.responder = { TransportResponse(404, "") }
        val store = FakeTokenStore(
            listOf(
                PairedDevice(host = "192.0.2.10", name = "TestTV", token = "T1"),
                PairedDevice(host = "192.0.2.11", name = "OtherTV", token = "T2")
            ),
            selected = "192.0.2.11"
        )
        val ssdp = FakeSsdp(listOf(FakeSsdp.response("192.0.2.10", wakeupMac = MAC)))

        flow(transport, store, ssdp = ssdp).scan()

        assertEquals(MAC, store.all().first { it.host == "192.0.2.10" }.wakeupMac)
        assertEquals("the selection did not move", "192.0.2.11", store.load()?.host)
    }

    /**
     * A pairing with no MAC learns it without the user scanning. Measured
     * 2026-09-26: a phone whose the test TV pairing predated the MAC never scanned
     * again, so a press against the deeply asleep TV sent DIAL alone and the
     * TV stayed dark — while the vendor app woke it.
     */
    @Test
    fun aStoredTvWithNoMacLearnsItWithoutAScan() {
        val store = FakeTokenStore(PairedDevice(host = TV_A, name = "TestTV", token = "T1"))
        val ssdp = FakeSsdp(listOf(FakeSsdp.response(TV_A, wakeupMac = MAC)))

        flow(FakeTransport(), store, ssdp = ssdp).learnWakeupMac(TV_A)

        assertEquals(MAC, store.all().single().wakeupMac)
    }

    /** A TV that already has its MAC costs no search — and a stranger's answer cannot replace it. */
    @Test
    fun aStoredMacIsNeitherSearchedForNorReplaced() {
        val store = FakeTokenStore(PairedDevice(host = TV_A, name = "TestTV", token = "T1", wakeupMac = MAC))
        val ssdp = FakeSsdp(listOf(FakeSsdp.response(TV_A, wakeupMac = OTHER_MAC)))

        flow(FakeTransport(), store, ssdp = ssdp).learnWakeupMac(TV_A)

        assertEquals(emptyList<Long>(), ssdp.searches)
        assertEquals(MAC, store.all().single().wakeupMac)
    }

    /**
     * The search runs for a TV with no MAC, and answers claiming other
     * addresses change nothing: a stored TV that already has a MAC keeps it,
     * and an address that is not stored gets nothing.
     */
    @Test
    fun aSearchForOneTvCannotReplaceAnotherTvsMac() {
        val store = FakeTokenStore(
            listOf(
                PairedDevice(host = TV_A, name = "A", token = "T1"),
                PairedDevice(host = TV_B, name = "B", token = "T2", wakeupMac = MAC)
            ),
            selected = TV_A
        )
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response(TV_B, wakeupMac = OTHER_MAC),
                FakeSsdp.response("192.0.2.99", wakeupMac = OTHER_MAC)
            )
        )

        flow(FakeTransport(), store, ssdp = ssdp).learnWakeupMac(TV_A)

        assertEquals(1, ssdp.searches.size)
        assertEquals(MAC, store.all().first { it.host == TV_B }.wakeupMac)
        assertNull(store.all().first { it.host == TV_A }.wakeupMac)
        assertEquals(2, store.all().size)
    }

    /**
     * A press that lands on TV A spends A's search only. TV B, asleep and
     * silent at the time, still gets its own search once a press lands on it.
     */
    @Test
    fun aPressOnOneTvDoesNotSpendAnotherTvsSearch() {
        val store = FakeTokenStore(
            listOf(PairedDevice(host = TV_A, name = "A", token = "T1"), PairedDevice(host = TV_B, name = "B", token = "T2")),
            selected = TV_A
        )
        val ssdp = FakeSsdp(listOf(FakeSsdp.response(TV_A)))
        val flow = flow(FakeTransport(), store, ssdp = ssdp)

        flow.learnWakeupMac(TV_A)
        ssdp.responses = listOf(FakeSsdp.response(TV_B, wakeupMac = MAC))
        flow.learnWakeupMac(TV_B)

        assertEquals(2, ssdp.searches.size)
        assertEquals(MAC, store.all().first { it.host == TV_B }.wakeupMac)
    }

    /**
     * Answers from one address that disagree about its MAC store nothing. A
     * forged answer can then block the fill, but it cannot plant a MAC.
     */
    @Test
    fun answersThatDisagreeAboutAMacStoreNothing() {
        val store = FakeTokenStore(PairedDevice(host = TV_A, name = "TestTV", token = "T1"))
        val ssdp = FakeSsdp(
            listOf(
                FakeSsdp.response(TV_A, wakeupMac = MAC),
                FakeSsdp.response(TV_A),
                FakeSsdp.response(TV_A, wakeupMac = OTHER_MAC)
            )
        )

        flow(FakeTransport(), store, ssdp = ssdp).learnWakeupMac(TV_A)

        assertNull(store.all().single().wakeupMac)
    }

    /**
     * One search per TV per screen. A TV that announces no `WAKEUP` (`.22` on
     * the test network) would otherwise hold the shared worker for a search
     * window after every press.
     */
    @Test
    fun aTvThatAnnouncesNoMacIsSearchedForOnce() {
        val store = FakeTokenStore(PairedDevice(host = TV_A, name = "TestTV", token = "T1"))
        val ssdp = FakeSsdp(listOf(FakeSsdp.response(TV_A)))
        val flow = flow(FakeTransport(), store, ssdp = ssdp)

        flow.learnWakeupMac(TV_A)
        flow.learnWakeupMac(TV_A)

        assertEquals(1, ssdp.searches.size)
        assertNull(store.all().single().wakeupMac)
    }

    /**
     * A fault in the search is not a fact about the TV: it does not reach the
     * caller, and it does not spend the TV's one search.
     */
    @Test
    fun aFailingSearchIsTriedAgain() {
        val store = FakeTokenStore(PairedDevice(host = TV_A, name = "TestTV", token = "T1"))
        val ssdp = FakeSsdp(listOf(FakeSsdp.response(TV_A, wakeupMac = MAC))).apply { failure = RuntimeException("socket") }
        val flow = flow(FakeTransport(), store, ssdp = ssdp)

        flow.learnWakeupMac(TV_A)
        assertNull(store.all().single().wakeupMac)

        ssdp.failure = null
        flow.learnWakeupMac(TV_A)
        assertEquals(MAC, store.all().single().wakeupMac)
    }

    /** A press that has to wake a stored TV sends the magic packet for that TV's own MAC. */
    @Test
    fun aPressWakesAStoredTvWithItsMac() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                "/apps/FireTVRemote" in call.url -> TransportResponse(201, "")
                else -> if (attempts++ == 0) throw TransportConnectionRefused("idle") else TransportResponse(200, "{}")
            }
        }
        val wol = FakeWakeOnLan(clock)
        val store = FakeTokenStore(
            PairedDevice(host = "192.0.2.10", name = "TestTV", token = "TOKEN_ABC", wakeupMac = MAC)
        )

        flow(transport, store = store, clock = clock, wakeOnLan = wol).press("home", keyed = false) { }

        assertEquals(listOf(MAC), wol.sent.map { it.mac })
    }

    @Test
    fun aRefusedPressNamesTheRefusalRatherThanTheWake() {
        // A stored token that has gone stale answers 401. The press path threw
        // plain IOException for that, so it fell through to the wake message —
        // "didn't answer, even after a wake" — and sent the user to check the
        // TV's power when the TV had answered and the fix was to pair again.
        // The catch for TransportStatus existed but could never fire.
        //
        // Since Phase 3 Step 9 the 401/403 branch is not merely reported: the
        // flow drops the stored token for that host and returns the PIN screen
        // so re-pairing is one tap away. The assertion here still catches the
        // regression this test exists for — the wake-silence message must
        // never surface on a token failure — and additionally checks the new
        // routing so the re-pair affordance cannot silently disappear.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = tokenRejectingResponder(401)
        var result: String? = null

        val followUp = flow(transport, store = pairedStore(), clock = clock)
            .press("select", keyed = true) { result = it }
        val message = result

        assertTrue(
            "a token refusal must not be reported as wake silence; got: $message",
            message != null && !message.contains("didn't answer")
        )
        assertTrue(
            "the user-facing message must name the re-pair affordance; got: $message",
            message?.contains("pair") == true
        )
        assertTrue(
            "a 401 stored-token rejection must return the PIN screen so re-pairing is one tap away; got: $followUp",
            followUp is PairingFlow.State.AwaitingPin
        )
    }

    @Test
    fun pressFailureSurfacesWithoutCrashing() {
        // A refusal and a timeout must both arrive as a message, not as a throw
        // out of the flow — the remote has to stay on screen.
        val cases = listOf<(FakeTransport.Call) -> TransportResponse>(
            { TransportResponse(500, "boom") },
            { throw TransportTimeout("no answer") }
        )
        val messages = mutableListOf<String?>()

        for (responder in cases) {
            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = responder
            var result: String? = null

            flow(transport, store = pairedStore(), clock = clock)
                .press("select", keyed = true) { result = it }

            messages += result
        }

        assertTrue(
            "every failure must carry a message meant for the user, got $messages",
            messages.all { !it.isNullOrBlank() }
        )
    }

    @Test
    fun pressingWithNoStoredPairingReportsRatherThanThrows() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{}") }
        var result: String? = null

        flow(transport, store = FakeTokenStore(), clock = clock).press("home", keyed = false) { result = it }

        assertTrue("expected a message, got $result", !result.isNullOrBlank())
        assertEquals("nothing may reach the network with no token", 0, transport.calls.size)
    }

    // --- Step 2: the manual route to a TV the scan cannot find ---------------

    /**
     * A TV that refuses the stored token with [status] but still starts pairing
     * — the PIN screen is only reached once the TV has been asked to show a PIN.
     */
    private fun tokenRejectingResponder(status: Int): (FakeTransport.Call) -> TransportResponse = { call ->
        if (call.url.contains("pin/display")) TransportResponse(200, "") else TransportResponse(status, "")
    }

    /** A responder that completes a pairing, so a test can reach `Paired`. */
    private fun pairingResponder(): (FakeTransport.Call) -> TransportResponse = { call ->
        when {
            call.url.contains("pin/display") -> TransportResponse(200, "")
            call.url.contains("pin/verify") -> TransportResponse(200, """{"description":"TOKEN-abc"}""")
            else -> TransportResponse(404, "")
        }
    }

    @Test
    fun typedAddressPairsWithoutAScan() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val store = FakeTokenStore()
        val flow = flow(transport, store)

        // No scan() anywhere in this test: that is the whole point of the route.
        val selected = flow.selectByAddress("192.0.2.10")

        assertTrue(
            "a typed address must reach PIN entry, got $selected",
            selected is PairingFlow.State.AwaitingPin
        )
        val device = (selected as PairingFlow.State.AwaitingPin).device
        assertEquals("192.0.2.10", device.ip)

        val paired = flow.pair(device, "1234")

        assertTrue("expected a paired state, got $paired", paired is PairingFlow.State.Paired)
        assertEquals(
            "the typed route must persist the same shape the scan route does",
            PairedDevice(host = "192.0.2.10", name = "192.0.2.10", token = "TOKEN-abc"),
            store.saved
        )
    }

    @Test
    fun malformedAddressIsRejectedBeforeAnyCall() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val malformed = listOf(
            "",                  // nothing typed
            "   ",               // whitespace only
            "192.0. 4.22",     // whitespace inside
            "abc.def.ghi.jkl",   // a hostname, not an address
            "192.0.2.999",     // octet out of range
            "192.0.2",         // too few octets
            "192.0..1"         // a missing octet
        )

        for (text in malformed) {
            val state = flow.selectByAddress(text)
            assertTrue("<$text> must be refused, got $state", state is PairingFlow.State.Failed)
            assertEquals(
                "<$text> must be refused as a malformed address",
                PairingFlow.State.Cause.MalformedAddress,
                (state as PairingFlow.State.Failed).cause
            )
        }

        // The assertion that matters: not "no pairing call", but no call at all.
        assertEquals(
            "a malformed address must never reach the network",
            0,
            transport.calls.size
        )
    }

    @Test
    fun typedAddressAndScannedDeviceTakeTheSamePath() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()

        val typedStore = FakeTokenStore()
        val typedFlow = flow(transport, typedStore)
        val typedPick = typedFlow.selectByAddress("192.0.2.10") as PairingFlow.State.AwaitingPin
        typedFlow.pair(typedPick.device, "1234")

        val scannedStore = FakeTokenStore()
        val scannedFlow = flow(transport, scannedStore)
        val scannedPick = scannedFlow.select(tv) as PairingFlow.State.AwaitingPin
        scannedFlow.pair(scannedPick.device, "1234")

        assertEquals(
            "the same address reached both ways must yield the same pairing",
            scannedStore.saved,
            typedStore.saved
        )
    }

    @Test
    fun aTypedAddressIsTrimmedBeforeItBecomesADevice() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val state = flow.selectByAddress("  192.0.2.10  ") as PairingFlow.State.AwaitingPin

        assertEquals("192.0.2.10", state.device.ip)
        assertEquals(
            "surrounding whitespace must not travel into the address",
            "192.0.2.10",
            state.device.name
        )
    }

    @Test
    fun aPortOrCidrSuffixIsRejectedRatherThanTruncated() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        for (text in listOf("192.0.2.10:8080", "192.0.2.0/24")) {
            val state = flow.selectByAddress(text)
            assertTrue("<$text> must be refused, got $state", state is PairingFlow.State.Failed)
        }
        assertEquals("nothing may be guessed at and dialled", 0, transport.calls.size)
    }

    @Test
    fun theMalformedAddressCauseIsNotTvNotReachable() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val state = flow.selectByAddress("abc.def.ghi.jkl") as PairingFlow.State.Failed

        assertEquals(
            "nothing was contacted, so 'the TV did not answer' would be a lie",
            PairingFlow.State.Cause.MalformedAddress,
            state.cause
        )
    }

    @Test
    fun eachRejectionClassReadsDifferently() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        // The four classes the plan names, in its own order: empty, hostname,
        // out-of-range octet, embedded whitespace.
        val messages = listOf("", "abc.def.ghi.jkl", "192.0.2.999", "192.0. 4.22").map {
            (flow.selectByAddress(it) as PairingFlow.State.Failed).message
        }

        assertEquals(
            "each kind of mistake must be tellable from the others, got $messages",
            4,
            messages.toSet().size
        )
    }

    @Test
    fun theRejectionMessageQuotesTheAddressBack() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val typed = "abc.def.ghi.jkl"
        val message = (flow.selectByAddress(typed) as PairingFlow.State.Failed).message

        assertTrue(
            "the offending text must be quoted back so a report is actionable, got: $message",
            message.contains(typed)
        )
    }

    // --- Step 4: the typed text survives a rejected address ------------------

    @Test
    fun aRejectedAddressCarriesTheTypedTextForwardOnTheFailedState() {
        // Motivation: the retry screen has no address to pre-fill unless the
        // Failed state itself carries the text. This is Step 4's whole shape.
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val typed = "abc.def.ghi.jkl"
        val failed = flow.selectByAddress(typed) as PairingFlow.State.Failed

        assertEquals(
            "the state must carry the raw text so the retry screen can pre-fill it, got: ${failed.attemptedHost}",
            typed,
            failed.attemptedHost
        )
    }

    @Test
    fun theAttemptedHostIsTheTrimmedTypedText() {
        // Motivation: users routinely paste addresses with leading or trailing
        // whitespace; the retry field should show the trimmed value they will
        // pair with, not the raw paste. The message already quotes the trimmed
        // form back to the user (see `malformedAddressMessage`), and the retry
        // field must agree with the message it lives under.
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val flow = flow(transport, FakeTokenStore())

        val failed = flow.selectByAddress("  abc.def.ghi.jkl  ") as PairingFlow.State.Failed

        assertEquals(
            "the retry field must show the trimmed value, got: ${failed.attemptedHost}",
            "abc.def.ghi.jkl",
            failed.attemptedHost
        )
    }

    @Test
    fun anUnreachableTypedAddressCarriesTheTypedTextForward() {
        // Motivation: a parse that succeeds does not guarantee the TV is
        // reachable at the parsed address. When the TV never answers, the
        // typed-address retry screen still needs to pre-fill the value —
        // otherwise the user pays a full re-scan for a typo on an octet
        // that looked plausible enough to parse.
        val transport = FakeTransport()
        transport.responder = { _ -> throw TransportConnectionRefused("nothing at that address") }
        val flow = flow(transport, FakeTokenStore())

        val typed = "192.0.2.99"
        val failed = flow.selectByAddress(typed) as PairingFlow.State.Failed

        assertEquals(
            "even a parse-valid address that never answers must carry the typed text back, got: ${failed.attemptedHost}",
            typed,
            failed.attemptedHost
        )
    }

    @Test
    fun aScannedListDeviceFailureDoesNotSetAttemptedHost() {
        // Motivation: the attempted-host field only applies to the typed-
        // address route. Selecting a device the user picked from the scanned
        // list is a different flow — the user did not type anything, so there
        // is nothing to pre-fill. Setting the field on that path would leak
        // the scanned address back into the address entry field on retry.
        val transport = FakeTransport()
        transport.responder = { _ -> throw TransportConnectionRefused("scan-picked device is not there") }
        val flow = flow(transport)

        val failed = flow.select(tv) as PairingFlow.State.Failed

        assertNull(
            "a failure on the scanned-list route must not carry a typed-text pre-fill, got: ${failed.attemptedHost}",
            failed.attemptedHost
        )
    }

    // --- Step 3: the screen flow survives the user changing their mind ------

    @Test
    fun backFromPinKeepsTheDiscoveredList() {
        val transport = FakeTransport()
        val alive = setOf("192.0.2.10", "192.0.2.42")
        transport.responder = discoveryResponder(alive)
        val flow = flow(transport)

        val scanned = flow.scan() as PairingFlow.State.Discovered
        flow.select(scanned.devices.first())
        val callsBeforeBack = transport.calls.size

        val back = flow.goBackFromPin()

        assertTrue("Back from the PIN screen lands on the list, got $back", back is PairingFlow.State.Discovered)
        assertEquals(
            "the same devices the scan found, not a second scan's answer",
            scanned.devices.toSet(),
            (back as PairingFlow.State.Discovered).devices.toSet()
        )
        assertEquals(
            "Back must not re-scan — that discards the result the user was looking at",
            callsBeforeBack,
            transport.calls.size
        )
    }

    @Test
    fun emptyPinNeverReachesTheTv() {
        for (blank in listOf("", "   ")) {
            val transport = FakeTransport()
            transport.responder = { TransportResponse(200, "") }
            val flow = flow(transport)
            val callsBefore = transport.calls.size

            val state = flow.pair(tv, blank)

            assertTrue("a blank PIN must fail, got $state", state is PairingFlow.State.Failed)
            assertEquals(
                "a blank PIN must not consume one of the TV's pairing attempts",
                callsBefore,
                transport.calls.size
            )
            assertTrue(
                "no pin/verify may be issued for a blank PIN",
                transport.calls.none { it.url.contains("pin/verify") }
            )
        }
    }

    // --- the TV this app has already paired with --------------------------

    private val pairedAway = PairedDevice(host = "192.0.2.99", name = "the test TV", token = "TOKEN_Z")

    @Test
    fun scanListIncludesPreviouslyPairedDevicesNotFoundInScan() {
        // The whole point of the step. A paired TV that is off or asleep answers
        // nothing, so a list built purely from what the network said drops the
        // one device the user can still act on -- while the app already holds
        // its name, address and token.
        val transport = FakeTransport()
        transport.responder = discoveryResponder(setOf("192.0.2.10"))

        val state = flow(transport, FakeTokenStore(pairedAway)).scan()

        assertTrue("expected a device list, got $state", state is PairingFlow.State.Discovered)
        val devices = (state as PairingFlow.State.Discovered).devices
        assertEquals(
            "the TV that answered and the TV that was paired are both listed",
            setOf("192.0.2.10", "192.0.2.99"),
            devices.map { it.ip }.toSet()
        )
        val stored = devices.single { it.ip == "192.0.2.99" }
        assertFalse("the paired TV did not answer this scan", stored.answering)
        assertEquals("it keeps the name it was paired under, not its address", "the test TV", stored.name)
        assertTrue(
            "the TV that did answer is still marked as answering",
            devices.single { it.ip == "192.0.2.10" }.answering
        )
    }

    @Test
    fun aPairedTvThatAlsoAnsweredIsListedOnce() {
        // Dedupe on the address. Without it a TV that is awake and was already
        // paired appears twice -- once from the scan, once from the store.
        val transport = FakeTransport()
        transport.responder = discoveryResponder(setOf("192.0.2.99"))
        val store = FakeTokenStore(PairedDevice(host = "192.0.2.99", name = "the test TV", token = "TOKEN_Z"))

        val devices = (flow(transport, store).scan() as PairingFlow.State.Discovered).devices

        assertEquals("one entry, not two", 1, devices.size)
        assertTrue("and it is the answering one, not the stored copy", devices.single().answering)
    }

    @Test
    fun withNothingPairedTheListIsExactlyTheScanResult() {
        // The identity case. This step must not change what an unpaired user
        // sees -- no phantom entry, no reordering.
        val transport = FakeTransport()
        val alive = setOf("192.0.2.10", "192.0.2.42")
        transport.responder = discoveryResponder(alive)

        val devices = (flow(transport).scan() as PairingFlow.State.Discovered).devices

        assertEquals(alive, devices.map { it.ip }.toSet())
        assertTrue("every device here answered", devices.all { it.answering })
    }

    @Test
    fun theDevicesThatAnsweredComeBeforeTheOneThatDidNot() {
        val transport = FakeTransport()
        transport.responder = discoveryResponder(setOf("192.0.2.10"))

        val devices = (flow(transport, FakeTokenStore(pairedAway)).scan() as PairingFlow.State.Discovered).devices

        assertEquals(
            "the device the user can use now goes on top; the fallback sits under it",
            "192.0.2.99",
            devices.last().ip
        )
    }

    @Test
    fun aStoredTvThatIsNotAnsweringOpensTheRemoteRatherThanAskingForAPinAgain() {
        // Lock 7. Re-pairing a TV the user already paired is the regression this
        // path exists to prevent -- and the PIN it would ask for is on a screen
        // nobody can read while the TV is off. The wake path runs on the first
        // press instead.
        val transport = FakeTransport()
        transport.responder = discoveryResponder(setOf("192.0.2.10"))
        val store = FakeTokenStore(pairedAway)
        val flow = flow(transport, store)
        val devices = (flow.scan() as PairingFlow.State.Discovered).devices
        val stored = devices.single { it.ip == "192.0.2.99" }

        val state = flow.select(stored)

        assertTrue("expected the paired screen, got $state", state is PairingFlow.State.Paired)
        assertEquals(pairedAway, (state as PairingFlow.State.Paired).device)
        assertTrue(
            "no PIN may be requested for a TV this app already paired with",
            transport.calls.none { it.url.contains("pin") }
        )
    }

    @Test
    fun aPairedTvThatIsOffOutranksTheDidNotAnswerMessage() {
        // The case a merge inside the `Discovered` arm would miss. When nothing
        // answers, `scan()` normally returns the "something went quiet" screen
        // -- no list at all. A stored TV has to outrank that message, or the one
        // device the user can act on is replaced by an explanation of why they
        // cannot.
        val transport = FakeTransport()
        transport.responder = { throw TransportTimeout("the TV is asleep") }

        val state = flow(transport, FakeTokenStore(pairedAway)).scan()

        assertTrue(
            "a stored TV must produce a list, not a message, got $state",
            state is PairingFlow.State.Discovered
        )
        val devices = (state as PairingFlow.State.Discovered).devices
        assertEquals("192.0.2.99", devices.single().ip)
        assertFalse(devices.single().answering)
    }

    // --- Step 9: more than one paired TV ------------------------------------

    private val tvA = PairedDevice(host = "192.0.2.10", name = "TV A", token = "TOKEN-A")
    private val tvB = PairedDevice(host = "192.0.2.42", name = "TV B", token = "TOKEN-B")

    @Test
    fun pairingASecondTvKeepsTheFirst() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val store = FakeTokenStore()
        val flow = flow(transport, store)

        val deviceA = Device(name = "TV A", ip = "192.0.2.10")
        val awaitingA = flow.select(deviceA) as PairingFlow.State.AwaitingPin
        flow.pair(awaitingA.device, "1234")

        val deviceB = Device(name = "TV B", ip = "192.0.2.42")
        val awaitingB = flow.select(deviceB) as PairingFlow.State.AwaitingPin
        flow.pair(awaitingB.device, "5678")

        assertEquals(
            "both pairings persist, in insertion order",
            listOf("192.0.2.10", "192.0.2.42"),
            store.all().map { it.host }
        )
        assertEquals(
            "the freshly-paired TV becomes the selected one",
            "192.0.2.42",
            store.load()?.host
        )
    }

    @Test
    fun selectingAStoredTvOpensRemoteWithNoPinOnTheWire() {
        val transport = FakeTransport()
        // Any wire call this test allows is one it must fail on — the whole
        // point is that a switch to a stored TV needs no pairing exchange.
        transport.responder = { call ->
            error("no wire call may reach the network for a stored switch, got ${call.url}")
        }
        val store = FakeTokenStore(listOf(tvA, tvB), selected = tvA.host)

        val state = flow(transport, store).switchTo(tvB.host)

        assertTrue("expected the paired screen for TV B, got $state", state is PairingFlow.State.Paired)
        assertEquals(tvB.host, (state as PairingFlow.State.Paired).device.host)
        assertEquals("the selected pointer moved to TV B", tvB.host, store.load()?.host)
        assertTrue("nothing may reach the network for a stored switch", transport.calls.isEmpty())
    }

    @Test
    fun stored403EntersPinPathForThatHostOnly() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = tokenRejectingResponder(403)
        val store = FakeTokenStore(listOf(tvA, tvB), selected = tvA.host)
        val flow = flow(transport, store, clock)

        var reported: String? = null
        val followUp = flow.press("home", keyed = false) { reported = it }

        assertTrue(
            "expected a PIN-screen follow-up for TV A, got $followUp",
            followUp is PairingFlow.State.AwaitingPin
        )
        val awaiting = followUp as PairingFlow.State.AwaitingPin
        assertEquals("TV A's host is what the PIN screen must target", tvA.host, awaiting.device.ip)
        assertEquals("TV A's name travels forward so the screen reads correctly", tvA.name, awaiting.device.name)
        assertTrue(
            "the TV must be asked to show a PIN, or the PIN screen has nothing to read; got: ${transport.calls.map { it.url }}",
            transport.calls.any { it.url.contains("pin/display") && it.url.contains(tvA.host) }
        )

        assertEquals(
            "TV A must be dropped from the store — only its token was rejected",
            listOf(tvB),
            store.all()
        )
        assertEquals("TV B's token is untouched", tvB.token, store.load()?.token)
        assertEquals("the selected pointer advanced past the removed TV",
            tvB.host, store.load()?.host)
        assertTrue(
            "the user-facing message must name the rejected TV: $reported",
            reported?.contains(tvA.name) == true
        )
    }

    /**
     * Re-pairing a TV after it rejected the stored token keeps the Wake-on-LAN
     * MAC it had. The scan is the only time the MAC is heard, and a TV deep
     * enough asleep to need it answers no scan — so a MAC dropped here stays
     * dropped, and the next deep sleep cannot be woken.
     */
    @Test
    fun rePairingAfterA403KeepsTheWakeupMac() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            when {
                call.url.contains("pin/display") -> TransportResponse(200, "")
                call.url.contains("pin/verify") -> TransportResponse(200, """{"description":"TOKEN-new"}""")
                else -> TransportResponse(403, "")
            }
        }
        val mac = "00:00:5e:00:53:01"
        val store = FakeTokenStore(listOf(tvA.copy(wakeupMac = mac), tvB), selected = tvA.host)
        val flow = flow(transport, store, clock)

        val awaiting = flow.press("home", keyed = false) {} as PairingFlow.State.AwaitingPin
        flow.pair(awaiting.device, "1234")

        val repaired = store.all().single { it.host == tvA.host }
        assertEquals("TOKEN-new", repaired.token)
        assertEquals("the MAC survives the re-pair", mac, repaired.wakeupMac)
    }

    /**
     * Pairing by typed address with a TV this app already stores keeps its MAC.
     * The typed route never hears SSDP, so it has no MAC of its own to offer.
     */
    @Test
    fun pairingAStoredTvByTypedAddressKeepsItsWakeupMac() {
        val transport = FakeTransport()
        transport.responder = pairingResponder()
        val mac = "00:00:5e:00:53:01"
        val store = FakeTokenStore(listOf(tvA.copy(wakeupMac = mac)), selected = tvA.host)
        val flow = flow(transport, store)

        val awaiting = flow.selectByAddress(tvA.host) as PairingFlow.State.AwaitingPin
        flow.pair(awaiting.device, "1234")

        assertEquals("the MAC survives the typed-address pairing", mac, store.all().single().wakeupMac)
    }

    @Test
    fun forgetRemovesOneTv() {
        val transport = FakeTransport()
        val store = FakeTokenStore(listOf(tvA, tvB), selected = tvA.host)
        val flow = flow(transport, store)

        val state = flow.forget()

        assertTrue(
            "expected the paired screen for TV B after forgetting selected TV A, got $state",
            state is PairingFlow.State.Paired
        )
        assertEquals(tvB.host, (state as PairingFlow.State.Paired).device.host)
        assertEquals(listOf(tvB), store.all())
        assertEquals("the selected pointer advanced to TV B", tvB.host, store.load()?.host)
    }

    @Test
    fun forgetByHostLeavesTheSelectedTvUntouched() {
        // Edge: forget(host) targets a specific TV, not the selected one.
        // Forgetting the non-selected TV keeps the selected pointer where it is.
        val transport = FakeTransport()
        val store = FakeTokenStore(listOf(tvA, tvB), selected = tvA.host)
        val flow = flow(transport, store)

        val state = flow.forget(tvB.host)

        assertTrue("selected TV A stays paired, got $state", state is PairingFlow.State.Paired)
        assertEquals(tvA.host, (state as PairingFlow.State.Paired).device.host)
        assertEquals(listOf(tvA), store.all())
        assertEquals("the selected pointer stays on TV A", tvA.host, store.load()?.host)
    }

    @Test
    fun switchToUnknownHostReturnsNullAndLeavesTheSelectionAlone() {
        // Edge: the overflow menu can fall out of step with the store (a stored
        // TV was removed by another route between menu render and tap). Rather
        // than throw, switchTo returns null so the caller re-renders from start.
        val transport = FakeTransport()
        val store = FakeTokenStore(listOf(tvA, tvB), selected = tvA.host)
        val flow = flow(transport, store)

        val state = flow.switchTo("192.0.2.99")

        assertNull("an unknown host must return null, got $state", state)
        assertEquals("the selection must not change on an unknown-host switch",
            tvA.host, store.load()?.host)
    }

    // --- Back ------------------------------------------------------------

    @Test
    fun backLeavesTheAppFromTheRemote() {
        // The remote is the app's home once a TV is paired. Back there has
        // nowhere in the app to go, so it leaves — before this it re-rendered
        // the start screen, which with a stored pairing *is* the remote, so
        // Back did nothing a user could see.
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "TOKEN-abc")
        val flow = flow(FakeTransport(), store = FakeTokenStore(stored))

        assertNull(flow.back(PairingFlow.State.Paired(stored)))
    }

    @Test
    fun backLeavesTheAppFromTheStartScreen() {
        assertNull(flow(FakeTransport()).back(PairingFlow.State.Scanning))
    }

    @Test
    fun backFromThePinScreenReturnsToTheListTheUserPickedFrom() {
        val transport = FakeTransport()
        transport.responder = discoveryResponder(alive = setOf(TV_A))
        val flow = flow(transport)
        val listed = flow.scan()
        val picked = (listed as PairingFlow.State.Discovered).devices.single()

        assertEquals(listed, flow.back(PairingFlow.State.AwaitingPin(picked)))
    }

    @Test
    fun backFromATypedAddressFailureReturnsToTheAddressEntry() {
        val failed = PairingFlow.State.Failed(
            "no answer", PairingFlow.State.Cause.TvNotReachable, attemptedHost = "192.0.2.99"
        )

        assertEquals(
            PairingFlow.State.Discovered(emptyList()),
            flow(FakeTransport()).back(failed)
        )
    }

    @Test
    fun backFromEveryOtherScreenReturnsToTheStart() {
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "TOKEN-abc")
        val unpaired = flow(FakeTransport())
        val paired = flow(FakeTransport(), store = FakeTokenStore(stored))
        val others = listOf(
            PairingFlow.State.Discovered(listOf(tv)),
            PairingFlow.State.HostDidNotAnswer,
            PairingFlow.State.HostRefused,
            PairingFlow.State.Failed("scan failed", PairingFlow.State.Cause.ScanFailed)
        )

        for (state in others) {
            assertEquals("from $state", PairingFlow.State.Scanning, unpaired.back(state))
            assertEquals("from $state", PairingFlow.State.Paired(stored), paired.back(state))
        }
    }

    // --- Which scan-list rows have to carry their address --------------------

    @Test
    fun twoDevicesSharingANameBothNeedTheirAddressShown() {
        // Two TVs can announce the same name. Then the name alone does not say
        // which is which, and the address is the only thing that does.
        val devices = listOf(
            Device(name = "Home Gym", ip = "192.0.2.10"),
            Device(name = "Home Gym", ip = "192.0.2.11"),
            Device(name = "the test TV", ip = "192.0.2.12")
        )

        assertEquals(
            setOf("Home Gym"),
            flow(FakeTransport()).namesNeedingAddress(devices)
        )
    }

    @Test
    fun threeDevicesSharingANameAllNeedTheirAddressShown() {
        // The rule is "more than one", not "exactly two".
        val devices = listOf(
            Device(name = "HDMI device", ip = "192.0.2.10"),
            Device(name = "HDMI device", ip = "192.0.2.11"),
            Device(name = "HDMI device", ip = "192.0.2.12")
        )

        assertEquals(
            setOf("HDMI device"),
            flow(FakeTransport()).namesNeedingAddress(devices)
        )
    }

    @Test
    fun distinctNamesNeedNoAddress() {
        val devices = listOf(
            Device(name = "the test TV", ip = "192.0.2.10"),
            Device(name = "ROOM", ip = "192.0.2.11")
        )

        assertTrue(
            "no two rows share a name, so neither needs its address, got " +
                flow(FakeTransport()).namesNeedingAddress(devices),
            flow(FakeTransport()).namesNeedingAddress(devices).isEmpty()
        )
    }

    @Test
    fun aDeviceWithNoNameNeverCollides() {
        // `Discovery` falls back to the address when a TV announces no name, so
        // two unnamed devices have two different names. A row showing an address
        // as its name is a device that never had a name, not a collision.
        val devices = listOf(
            Device(name = "192.0.2.10", ip = "192.0.2.10"),
            Device(name = "192.0.2.11", ip = "192.0.2.11")
        )

        assertTrue(flow(FakeTransport()).namesNeedingAddress(devices).isEmpty())
    }

    @Test
    fun anEmptyScanNeedsNoAddress() {
        assertTrue(flow(FakeTransport()).namesNeedingAddress(emptyList()).isEmpty())
    }

    // --- How a scan row reads ------------------------------------------------

    /**
     * Stands in for `getString`, mirroring the three formats in `strings.xml`.
     *
     * What these tests assert is the *order* the parts are applied in — the part
     * no device here can show, because showing it needs two TVs sharing one name
     * and this network has none. The wording itself is `strings.xml`'s and is not
     * what is under test.
     */
    private val rowFormat: (Int, List<Any>) -> String = { resId, args ->
        when (resId) {
            R.string.scan_list_name_with_address -> "${args[0]} (${args[1]})"
            R.string.device_not_answering -> "${args[0]} (not answering)"
            R.string.scan_list_paired_suffix -> "${args[0]} (paired)"
            else -> error("unexpected string resource: $resId")
        }
    }

    @Test
    fun aSharedNameShowsItsAddressBesideIt() {
        val device = Device(name = "Home Gym", ip = "192.0.2.10")

        assertEquals(
            "Home Gym (192.0.2.10)",
            flow(FakeTransport()).scanRowLabel(
                device,
                storedHosts = emptySet(),
                shared = setOf("Home Gym"),
                format = rowFormat
            )
        )
    }

    @Test
    fun theAddressGoesInsideTheNameAndBothSuffixesWrapIt() {
        // The row carrying all three: a shared name, silent, and stored. The
        // address sits next to the name it disambiguates rather than trailing
        // after the state — an order nothing asserted until this test existed.
        val device = Device(name = "Home Gym", ip = "192.0.2.10", answering = false)

        assertEquals(
            "Home Gym (192.0.2.10) (not answering) (paired)",
            flow(FakeTransport()).scanRowLabel(
                device,
                storedHosts = setOf("192.0.2.10"),
                shared = setOf("Home Gym"),
                format = rowFormat
            )
        )
    }

    @Test
    fun anUnsharedStoredNameKeepsItsNameAndGainsOnlyThePairedSuffix() {
        val device = Device(name = "the test TV", ip = "192.0.2.10")

        assertEquals(
            "the test TV (paired)",
            flow(FakeTransport()).scanRowLabel(
                device,
                storedHosts = setOf("192.0.2.10"),
                shared = emptySet(),
                format = rowFormat
            )
        )
    }

    @Test
    fun anOrdinaryAnsweringRowIsJustTheName() {
        val device = Device(name = "the test TV", ip = "192.0.2.10")

        assertEquals(
            "the test TV",
            flow(FakeTransport()).scanRowLabel(
                device,
                storedHosts = emptySet(),
                shared = emptySet(),
                format = rowFormat
            )
        )
    }

    // --- The first-run screen ------------------------------------------------

    @Test
    fun firstRunShowsBeforeScanningWhenItHasNotBeenSeen() {
        assertEquals(
            PairingFlow.State.FirstRun,
            flow(FakeTransport(), introSeen = { false }).start()
        )
    }

    @Test
    fun firstRunDoesNotReturnOnceSeen() {
        assertEquals(
            PairingFlow.State.Scanning,
            flow(FakeTransport(), introSeen = { true }).start()
        )
    }

    @Test
    fun aStoredPairingSkipsFirstRunEvenWhenItHasNeverBeenSeen() {
        // Someone with a pairing already stored has used this app. The screen
        // that explains what the app needs is not for them, whatever the flag
        // says — the flag is only consulted when there is nothing paired.
        val stored = PairedDevice(host = tv.ip, name = tv.name, token = "TOKEN-abc")

        assertEquals(
            PairingFlow.State.Paired(stored),
            flow(FakeTransport(), store = FakeTokenStore(stored), introSeen = { false }).start()
        )
    }

    @Test
    fun dismissingFirstRunRecordsItAndMovesToScan() {
        var seen = false
        val flow = flow(FakeTransport(), introSeen = { seen }, markIntroSeen = { seen = true })

        assertEquals(PairingFlow.State.Scanning, flow.dismissIntro())
        assertTrue("dismissing must record it, or the screen returns on every launch", seen)
        assertEquals("and the next launch goes straight to Scan", PairingFlow.State.Scanning, flow.start())
    }

    @Test
    fun backLeavesTheAppFromTheFirstRunScreen() {
        // It is the app's own opening screen, like Scanning. Falling through to
        // `start()` would return FirstRun again and make Back a no-op that never
        // exits the app.
        assertNull(flow(FakeTransport(), introSeen = { false }).back(PairingFlow.State.FirstRun))
    }
}
