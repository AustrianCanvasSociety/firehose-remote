package io.github.austriancanvassociety.firehoseremote.protocol

import io.github.austriancanvassociety.firehoseremote.net.HttpUrlTransport
import io.github.austriancanvassociety.firehoseremote.ui.ALL_CONTROLS
import io.github.austriancanvassociety.firehoseremote.ui.Gate
import io.github.austriancanvassociety.firehoseremote.ui.PairingFlow
import io.github.austriancanvassociety.firehoseremote.ui.allDrawnControls
import io.github.austriancanvassociety.firehoseremote.ui.capabilitiesTakeSomethingAway
import io.github.austriancanvassociety.firehoseremote.ui.drawnRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FireTvClientTest {

    private val HOST = "192.0.2.10"
    private val TOKEN = "TOKEN_ABC"

    @Test
    fun keyPressGapIs220Ms() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        assertEquals(2, transport.calls.size)
        assertTrue("first call is keyDown", transport.calls[0].body!!.contains("keyDown"))
        assertTrue("second call is keyUp", transport.calls[1].body!!.contains("keyUp"))
        val gap = transport.calls[1].atMillis - transport.calls[0].atMillis
        assertEquals(
            "keyDown/keyUp gap must be 220 ms measured after keyDown response",
            220L,
            gap
        )
    }

    @Test
    fun verifyPinRetriesOnLiteralOK() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val responses = mutableListOf(
            "{\"description\":\"OK\"}",
            "{\"description\":\"OK\"}",
            "{\"description\":\"zBMBFhY\"}"
        )
        transport.responder = { TransportResponse(200, responses.removeAt(0)) }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")

        assertEquals(PinOutcome.Paired("zBMBFhY"), outcome)
        assertEquals(3, transport.calls.size)
        assertEquals(
            "must sleep 1 s between the two retries but not after the successful third attempt",
            listOf(1000L, 1000L),
            clock.sleepCalls
        )
    }

    @Test
    fun verifyPinFailsAfterThreeConsecutiveOks() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        try {
            FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("expected IOException after 3 OK responses")
        } catch (e: IOException) {
            assertTrue(
                e.message!!,
                e.message!!.contains("firehose-remote: pin/verify:")
            )
        }
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun aMalformedReplyFollowedByOksNamesTheRealCause() {
        // A malformed reply followed by the OK sentinel used to be reported as
        // "none carrying a readable description" — a confident falsehood about
        // replies that plainly carried one. The closing message must describe
        // what actually came back, not the worst thing seen along the way.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val responses = mutableListOf(
            """{"somethingElse":1}""",
            """{"description":"OK"}""",
            """{"description":"OK"}"""
        )
        transport.responder = { TransportResponse(200, responses.removeAt(0)) }

        try {
            FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("expected IOException after a malformed reply and two OKs")
        } catch (e: IOException) {
            val message = e.message!!
            assertTrue(
                "must not claim every reply was unreadable, got: $message",
                !message.contains("none carrying a readable description")
            )
            assertTrue(
                "must name the count that actually came back, got: $message",
                message.contains("2 of 3")
            )
        }
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun wakeOnlyOnSocketTimeout() {
        // TransportTimeout on keyDown → wake fires and keyDown retries and succeeds.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempt = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "keyDown" in (call.body ?: "") && attempt++ == 0 ->
                    throw TransportTimeout("simulated sleeping tv")
                else -> TransportResponse(200, "")
            }
        }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        val urls = transport.calls.map { it.url }
        assertTrue("expected exactly one wake call, got: $urls",
            urls.count { WAKE_URL in it } == 1)
        assertEquals(
            "keyDown-timeout, wake, status poll, keyDown-retry, keyUp",
            5, transport.calls.size
        )
    }

    /**
     * A refused port is an **idle** TV, not a wrong address.
     *
     * This test used to assert the opposite — that a `TransportConnectionRefused`
     * propagated without a wake, on the reasoning that a refusal meant the IP was
     * wrong. Measured 2026-09-17 against a Fire TV Stick: the address was right,
     * the TV was on, and its control API was closed because the device had gone
     * idle into its screen saver. The vendor app wakes on a refusal exactly as it
     * does on a timeout. Refusing to wake is what left the remote dead against a
     * TV that was plainly on.
     */
    @Test
    fun wakeAlsoFiresOnConnectionRefused() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url -> TransportResponse(403, "unauthorized")
                else -> if (attempts++ == 0) {
                    throw TransportConnectionRefused("nothing listening")
                } else {
                    TransportResponse(200, "{}")
                }
            }
        }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        val urls = transport.calls.map { it.url }
        assertTrue("a refusal must wake the TV; got: $urls",
            urls.any { WAKE_URL in it })
        assertTrue("the status endpoint is polled between wake and retry; got: $urls",
            urls.any { "/v1/FireTV/status" in it })
        assertTrue("the press lands after the wake; got: $urls",
            urls.last().endsWith("action=dpad_right"))
    }

    @Test
    fun wakeWrapsKeyDownNotWholeTap() {
        // Once keyDown succeeds (after a wake retry), keyUp fires WITHOUT
        // another wake wrap — the 220 ms gap lands on an already-awake TV.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var keyDownAttempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "keyDown" in (call.body ?: "") -> {
                    if (keyDownAttempts++ == 0) throw TransportTimeout("first keyDown times out")
                    TransportResponse(200, "")
                }
                "keyUp" in (call.body ?: "") -> TransportResponse(200, "")
                else -> TransportResponse(200, "")
            }
        }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        val wakeCalls = transport.calls.count { WAKE_URL in it.url }
        assertEquals("wake must fire exactly once around keyDown, never around keyUp", 1, wakeCalls)

        val keyUpIndex = transport.calls.indexOfFirst { "keyUp" in (it.body ?: "") }
        val followsWakeImmediately = keyUpIndex > 0 &&
            WAKE_URL in transport.calls[keyUpIndex - 1].url
        assertTrue("keyUp must not directly follow a wake — it lands on an awake TV",
            !followsWakeImmediately)
    }

    /**
     * The settle window must be a whole number of poll intervals.
     *
     * The polling loop tests `now < deadline` *before* each poll and then sleeps
     * a full [FireTvClient.WAKE_POLL_INTERVAL_MS]. A window that is not a
     * multiple of the interval leaves a partial trailing interval, so the last
     * poll lands past the deadline, never runs, and the window is silently
     * shorter than the constant claims. A measured window must therefore be
     * rounded up to the next interval when it is written into the constant —
     * this test is what makes that rounding load-bearing rather than cosmetic.
     */
    @Test
    fun wakeSettleWindowIsAWholeNumberOfPollIntervals() {
        assertEquals(
            "WAKE_SETTLE_MS must be a multiple of WAKE_POLL_INTERVAL_MS, or the " +
                "trailing partial interval is never polled and the loop gives up early",
            0L,
            FireTvClient.WAKE_SETTLE_MS % FireTvClient.WAKE_POLL_INTERVAL_MS
        )
    }

    /**
     * The window is consumed exactly — no early give-up, no overshoot.
     *
     * A status endpoint that refuses every poll must be polled once per interval
     * across the whole window, and the clock must land exactly on
     * [FireTvClient.WAKE_SETTLE_MS] when polling stops. Fewer polls than that
     * means the loop gave up before its own deadline; a larger clock reading
     * means it slept past the deadline it was written to respect. Both are the
     * failure this constant exists to avoid, one interval at a time.
     */
    @Test
    fun statusPollingConsumesTheSettleWindowExactly() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url ->
                    throw TransportConnectionRefused("idle tv")
                "keyDown" in (call.body ?: "") -> throw TransportTimeout("still asleep")
                else -> TransportResponse(200, "")
            }
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("expected the retried keyDown to time out — the TV never came back")
        } catch (expected: IOException) {
            // The retry is allowed to fail; what is under test is the wait.
        }

        assertEquals(
            "one status poll per interval across the whole settle window",
            (FireTvClient.WAKE_SETTLE_MS / FireTvClient.WAKE_POLL_INTERVAL_MS).toInt(),
            transport.calls.count { "/v1/FireTV/status" in it.url }
        )
        assertEquals(
            "polling must stop exactly at the deadline, never past it",
            FireTvClient.WAKE_SETTLE_MS,
            clock.nowMillis()
        )
    }

    /**
     * The last poll inside the window is honoured.
     *
     * The loop exits the moment `now >= deadline`, so the final in-window poll is
     * the last chance the API has to answer before the wake is called a failure.
     * If the window were an interval short, that poll would never run and a TV
     * that came up in time would still be reported as unreachable — the same
     * class of error as the wake-on-refusal bug, one interval narrower.
     */
    @Test
    fun theLastInWindowStatusPollIsHonoured() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val intervals =
            (FireTvClient.WAKE_SETTLE_MS / FireTvClient.WAKE_POLL_INTERVAL_MS).toInt()
        var statusPolls = 0
        var keyDownAttempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url ->
                    if (++statusPolls < intervals) {
                        throw TransportConnectionRefused("idle tv")
                    } else {
                        // Any answer means the port is open, including a rejection.
                        TransportResponse(403, "unauthorized")
                    }
                "keyDown" in (call.body ?: "") ->
                    if (keyDownAttempts++ == 0) {
                        throw TransportTimeout("asleep")
                    } else {
                        TransportResponse(200, "")
                    }
                else -> TransportResponse(200, "")
            }
        }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        assertEquals(
            "the API answers on the final in-window poll, not before it",
            intervals,
            statusPolls
        )
        assertTrue(
            "the press must land once the API answers inside the window",
            transport.calls.last().url.endsWith("action=dpad_right")
        )
    }

    /**
     * A press that lands first time never enters the wake path.
     *
     * The retry exists for a TV that was asleep. When the keyDown answers
     * straight away there is nothing to wait for, so the settle loop must not
     * run at all — the only sleep on this path is the 220 ms key gap. A poll
     * sleep here would mean the client waited out a wake it never needed, and
     * every responsive TV would pay that latency on every press.
     */
    @Test
    fun aPressThatLandsFirstTimeNeverSleepsAPollInterval() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "") }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        assertEquals(
            "only the key gap — no poll sleep when the TV answers first time",
            listOf(FireTvClient.KEY_PRESS_GAP_MS),
            clock.sleepCalls
        )
        assertEquals(
            "keyDown and keyUp, with no wake and no status poll between them",
            2,
            transport.calls.size
        )
    }

    /**
     * One refusal, then an answer: exactly one poll interval is waited.
     *
     * This is the ordinary shape of a wake on an idle TV, and where an
     * off-by-one in the loop would hide. Poll 1 refuses — the port is genuinely
     * closed — the loop sleeps one interval, and poll 2 answers. The answer is a
     * `403`, and that still counts: any reply means the port is open, which is
     * the contract [FireTvClient.awaitCommandApi] documents. Two poll sleeps
     * would mean sleeping past an answer already in hand; none would mean giving
     * up on a TV that came back.
     */
    @Test
    fun aRefusalThenAnAnswerSleepsExactlyOnePollInterval() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var statusPolls = 0
        var keyDownAttempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url ->
                    if (++statusPolls == 1) {
                        throw TransportConnectionRefused("idle tv")
                    } else {
                        // Any answer means the port is open, including a rejection.
                        TransportResponse(403, "unauthorized")
                    }
                "keyDown" in (call.body ?: "") ->
                    if (keyDownAttempts++ == 0) {
                        throw TransportTimeout("asleep")
                    } else {
                        TransportResponse(200, "")
                    }
                else -> TransportResponse(200, "")
            }
        }

        FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")

        assertEquals(
            "exactly one poll interval waited: a refusal, then an answer",
            listOf(FireTvClient.WAKE_POLL_INTERVAL_MS),
            clock.sleepCalls.filter { it == FireTvClient.WAKE_POLL_INTERVAL_MS }
        )
        val keyDowns = transport.calls.filter { "keyDown" in (it.body ?: "") }
        assertEquals(
            "the original press is retried once, not replaced by a probe",
            2,
            keyDowns.size
        )
        assertTrue(
            "the retry carries the key the user pressed",
            keyDowns.all { it.url.endsWith("action=dpad_right") }
        )
    }

    @Test
    fun readTimeoutIsExplicit() {
        val transport = HttpUrlTransport()
        assertTrue(
            "read timeout must be > 0 (default HttpURLConnection value is 0 = infinite)",
            transport.configuredReadTimeoutMs > 0
        )
        assertTrue(
            "connect timeout must be > 0 (default HttpURLConnection value is 0 = infinite)",
            transport.configuredConnectTimeoutMs > 0
        )
    }

    @Test
    fun verifyPinTreatsUnknownStatusAsError() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(500, "") }

        try {
            FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("expected IOException on HTTP 500")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("firehose-remote: pin/verify:"))
            assertTrue(e.message!!, e.message!!.contains("500"))
        }
    }

    // --- Step 3: an answer the TV gives is not automatically a credential ----

    @Test
    fun bodyReportedFailureIsNotAToken() {
        // docs/protocol.md § 1 documents no failure shape for pin/verify, so
        // nothing in the spec separates this from a minted token by content.
        // The client refuses to call it one rather than persisting "Invalid PIN"
        // as a credential and reporting a successful pairing.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"Invalid PIN\"}") }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "0000")

        assertEquals(PinOutcome.Unrecognized("Invalid PIN"), outcome)
    }

    @Test
    fun unparseableBodiesReportTheirOwnCause() {
        // Three unreadable shapes that used to collapse into one confident
        // falsehood — "TV returned OK for 3 attempts" — about replies that
        // never said OK. Each must now name itself.
        val bodies = listOf(
            "{\"somethingElse\":1}",
            "{\"description\":42}",
            "{\"description\":\"never closed"
        )
        val messages = mutableListOf<String>()
        for (body in bodies) {
            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = { TransportResponse(200, body) }
            try {
                FireTvClient(transport, clock).verifyPin(HOST, "1234")
                fail("expected a readable failure for body: $body")
            } catch (e: IOException) {
                val message = e.message!!
                assertTrue(
                    "must not claim the TV said OK, for body: $body — got: $message",
                    !message.contains("returned \"OK\"")
                )
                messages.add(message)
            }
        }
        assertEquals("each unreadable shape must produce its own message", 3, messages.toSet().size)
    }

    @Test
    fun controlCharactersEscapeSafely() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "") }

        FireTvClient(transport, clock).requestPin(HOST, "the test TVTV\nsecond line")

        val body = transport.calls.single().body!!
        assertTrue("a raw control character makes the body unparseable JSON: $body",
            body.none { it.code < 0x20 })
        assertTrue("a newline must survive as an escape: $body", body.contains("\\n"))
        assertTrue("a control character must survive as \\uXXXX: $body", body.contains("\\u0001"))
    }

    @Test
    fun tokenRoundTripsThroughEscape() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        // A is "A", and \\ is a single backslash. An unhandled \u drops the
        // escape's own letter through instead, so this token would come back as
        // "zBMu0041\FhY" — corrupted, persisted, and unrecoverable.
        transport.responder = { TransportResponse(200, "{\"description\":\"zBM\\u0041\\\\FhY\"}") }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")

        assertEquals(PinOutcome.Paired("zBMA\\FhY"), outcome)
    }

    @Test
    fun duplicateDescriptionIsRefusedRatherThanGuessed() {
        // JSON leaves duplicate names unspecified. Taking the first threw the
        // token away and then reported the body as though the TV had said "OK"
        // on all three attempts; taking the last would be a guess at a
        // credential. Neither is honest, so the body is refused instead.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = {
            TransportResponse(200, """{"description":"OK","description":"zBMBFhY"}""")
        }

        try {
            FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("expected IOException for a body carrying two descriptions")
        } catch (e: IOException) {
            val message = e.message!!
            assertTrue(
                "must not report a sentinel count for an ambiguous body, got: $message",
                !message.contains("3 of 3")
            )
            assertTrue(
                "must name the duplicate, got: $message",
                message.contains("more than one top-level description field")
            )
        }
    }

    @Test
    fun aValueThatReadsAsDescriptionIsNotASecondField() {
        // The word appears as a *value* before the real field. Tracking depth
        // without tracking key position counted it as a second top-level
        // description, refused the body, and threw away a token the TV had
        // minted — a false diagnosis on a well-formed reply, which is the shape
        // the Description type exists to prevent.
        for (body in listOf(
            """{"x":"description","description":"zBMBFhY"}""",
            """{"description":"zBMBFhY","x":"description"}"""
        )) {
            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = { TransportResponse(200, body) }

            assertEquals(
                "a value reading description must not be read as a second field: $body",
                PinOutcome.Paired("zBMBFhY"),
                FireTvClient(transport, clock).verifyPin(HOST, "1234")
            )
        }
    }

    @Test
    fun signedHexEscapeDoesNotFabricateACharacter() {
        // `toIntOrNull(16)` accepts a leading sign, so without a digit check a
        // body carrying a backslash, then u, then +041 decoded to 'A' — a
        // character it never contained, on its way to being stored as a
        // credential.
        //
        // This test used to assert the weaker fix: the malformed escape survived
        // as literal text and `au+041b` was stored as the token. That only moved
        // the fabrication — the client still persisted a credential the TV never
        // issued, and only the letters were different. A body carrying an escape
        // this client cannot decode is now refused outright.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        // Built from a code point so the sequence under test is unambiguous in
        // the source.
        val backslash = Char(0x5C)
        transport.responder = {
            TransportResponse(200, """{"description":"a${backslash}u+041b"}""")
        }

        try {
            val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("a body with an undecodable escape must be refused, not paired: $outcome")
        } catch (e: IOException) {
            assertTrue(
                "the refusal must name the escape: ${e.message}",
                e.message.orEmpty().contains("escape that cannot be decoded")
            )
        }
    }

    @Test
    fun unicodeHexDigitsDoNotFabricateACharacter() {
        // The other half of the same failure mode. The digit check was
        // Unicode-aware, so four Arabic-Indic digits passed it and the escape
        // decoded to 'A' — a character the body never contained, on its way to
        // being stored as the pairing token. Built from code points so the
        // sequence under test is unambiguous in the source.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val backslash = Char(0x5C)
        val arabicHex = listOf(0x0660, 0x0660, 0x0664, 0x0661).map { Char(it) }.joinToString("")
        transport.responder = {
            TransportResponse(200, """{"description":"a${backslash}u${arabicHex}b"}""")
        }

        try {
            val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")
            fail("a body with an undecodable escape must be refused, not paired: $outcome")
        } catch (e: IOException) {
            // Neither fabrication nor the weaker "keep it as literal text" fix:
            // the body cannot be read, so it is refused and nothing is persisted.
            // The digits must never be read as hex, and the mangled remainder
            // must never become the stored credential.
            assertTrue(
                "the refusal must name the escape: ${e.message}",
                e.message.orEmpty().contains("escape that cannot be decoded")
            )
        }
    }

    @Test
    fun nestedDescriptionDoesNotHijack() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = {
            TransportResponse(200, "{\"detail\":{\"description\":\"nested\"},\"description\":\"zBMBFhY\"}")
        }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")

        assertEquals(
            "a description nested inside another object is not the TV's answer",
            PinOutcome.Paired("zBMBFhY"),
            outcome
        )
    }

    @Test
    fun oversizedTokenIsRejected() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val huge = "z".repeat(FireTvClient.MAX_TOKEN_LENGTH + 1)
        transport.responder = { TransportResponse(200, "{\"description\":\"$huge\"}") }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")

        assertTrue(
            "an unbounded body must not become a stored credential, got: $outcome",
            outcome is PinOutcome.Unrecognized
        )
    }

    @Test
    fun sendKeyPropagatesIOExceptionAfterWakeFails() {
        // Wake succeeds, but the retried keyDown returns a non-2xx — the
        // resulting IOException must propagate rather than being swallowed.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var keyDownAttempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "keyDown" in (call.body ?: "") -> {
                    if (keyDownAttempts++ == 0) throw TransportTimeout("first keyDown times out")
                    TransportResponse(503, "service unavailable")
                }
                else -> TransportResponse(200, "")
            }
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("expected IOException after wake-then-503")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("503"))
        }
    }

    // --- Step 3: the control set is complete or honest -----------------------
    //
    // These read the control list itself rather than repeating it, so they assert
    // the shape of whatever ships. The list lives in `ui/` because a descriptor
    // carries its label resource; nothing here needs a device to enumerate it.

    /**
     * Each shipped control sends the press shape its `keyed` flag declares — two
     * requests across the measured gap, or one body-less POST.
     *
     * The flag and the request shape are the two halves that used to drift apart
     * in silence: a keyed control sent body-less produces a request that looks
     * correct on the wire and a TV that does nothing, which reads as a device
     * limitation rather than a bug (`docs/protocol.md § 2`, Control coverage).
     */
    @Test
    fun keyedControlsSendThePressAndReleasePairAndOthersDoNot() {
        val shipped = ALL_CONTROLS.filter { it.present }

        for (control in shipped) {
            // The two media controls ride other endpoints, and have their own
            // tests below. This one is about the key endpoint's two shapes.
            if (control.action in MEDIA_ACTIONS) continue

            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

            FireTvClient(transport, clock).pressKey(HOST, TOKEN, control.action, control.keyed)

            if (control.keyed) {
                assertEquals("${control.action} is keyed — two requests", 2, transport.calls.size)
                assertTrue("${control.action} keyDown", transport.calls[0].body!!.contains("keyDown"))
                assertTrue("${control.action} keyUp", transport.calls[1].body!!.contains("keyUp"))
            } else {
                assertEquals("${control.action} is discrete — one request", 1, transport.calls.size)
                assertNull("${control.action} carries no body", transport.calls[0].body)
                assertTrue(
                    "${control.action} targets the command endpoint",
                    transport.calls[0].url.endsWith("/v1/FireTV?action=${control.action}")
                )
            }
        }
    }

    /**
     * A control recorded absent is never drawn, and being drawn is the only way
     * this app can send anything — so absent is not a state a programmatic caller
     * can route around.
     *
     * The record and the drawn set are one list read two ways: flipping `present`
     * is what changes both, which is what stops the documentation and the UI
     * disagreeing about a control neither observed working.
     */
    @Test
    fun noAbsentControlIsSendable() {
        val absent = ALL_CONTROLS.filter { !it.present }
        assertTrue("this test is vacuous with no absent controls", absent.isNotEmpty())

        // `allDrawnControls` is the single answer for "what is on screen given
        // this capabilities read": D-pad, Options, rocker and rows all through
        // one helper. Reassembling the drawn set from the individual halves is
        // what let the pre-refactor version silently forget a whole surface
        // when a control moved between structures.
        val drawn = allDrawnControls(Capabilities(volume = true, power = true))
            .map { it.action }

        for (control in absent) {
            assertTrue(
                "${control.action} is recorded absent and must not be drawn",
                control.action !in drawn
            )
        }
    }

    /**
     * The device's own answer is the only thing that takes a gated control away,
     * and only when it says no. A read that never happened, or that answered
     * something unreadable, leaves the controls where they are — hiding a working
     * button is the failure this gate exists to avoid, not the one it exists to
     * cause.
     */
    @Test
    fun onlyAnExplicitNoSuppressesAGatedControl() {
        // Reads the gated set off [ALL_CONTROLS] rather than [REMOTE_ROWS]
        // alone, because Step 7 moved every present-and-gated control (the
        // volume rocker's three zones) out of the rows and into its own
        // structure. Narrowing to rows here would leave this test vacuous —
        // exactly the failure the test's own guard is asked to catch.
        val gated = ALL_CONTROLS.filter { it.present && it.gate != Gate.NONE }
        assertTrue("this test is vacuous with no gated controls", gated.isNotEmpty())

        val refused = allDrawnControls(Capabilities(volume = false, power = false))
            .map { it.action }
        for (control in gated) {
            assertTrue("${control.action} was refused, so it must not be drawn", control.action !in refused)
        }

        // null is "nobody answered"; the all-true object is "it said yes". Both
        // draw everything, which is why they are asserted together.
        for (capabilities in listOf(null, Capabilities(volume = true, power = true))) {
            val drawn = allDrawnControls(capabilities).map { it.action }
            for (control in gated) {
                assertTrue(
                    "${control.action} must survive a read that said yes or did not happen",
                    control.action in drawn
                )
            }
        }
    }

    /**
     * The paired screen is drawn ungated and asked second, and it is rebuilt
     * only when the answer takes something away. That decision must look at
     * every drawn structure, not just the button rows: Step 7 moved the volume
     * controls into the rocker, and a rows-only check then read a device's
     * `false` as "nothing changed" and left the rocker on screen for the life
     * of the process. Observed 2026-09-20 against a stick that reports
     * `isVolumeControlsSupported:false`.
     */
    @Test
    fun aReadThatOnlyHidesTheRockerStillRebuildsTheScreen() {
        // Vacuity guard: the assertion below means nothing unless some drawn
        // control is actually gated on volume.
        assertTrue(
            "no drawn control is gated on volume; this test is vacuous",
            ALL_CONTROLS.any { it.present && it.gate == Gate.VOLUME }
        )

        assertTrue(
            "a read that says no to volume takes the rocker away, so the screen must be rebuilt",
            capabilitiesTakeSomethingAway(Capabilities(volume = false, power = true))
        )
        assertFalse(
            "a read that says yes to everything takes nothing away",
            capabilitiesTakeSomethingAway(Capabilities(volume = true, power = true))
        )
    }

    /**
     * Rewind carries the exact backward-shuttle body the vendor app was
     * captured sending: same endpoint, same direction, same duration, same
     * speed. The field name reads as seconds but the observed step is roughly
     * ten minutes — the value stays at `"10"` because it is what was captured,
     * not because it names a step in any known unit (`docs/protocol.md § 6`).
     */
    @Test
    fun rewindCarriesTheCapturedBackwardShuttleBody() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        FireTvClient(transport, clock).scanBackward(HOST, TOKEN)

        val call = transport.calls.single()
        assertTrue("rewind goes to the media endpoint", call.url.endsWith("/v1/media?action=scan"))
        assertEquals(
            "and carries the captured shuttle body, direction back",
            """{"direction":"back","durationInSeconds":"10","speed":"1"}""",
            call.body
        )
    }

    /**
     * Fast-forward flips the direction field to its other legal value and
     * changes nothing else. `"forward"` was never captured — it is inferred from
     * the field, and it ships as a matched pair with rewind so an overshoot is
     * correctable. The endpoint, duration and speed stay identical to the
     * captured backward body.
     */
    @Test
    fun fastForwardFlipsOnlyTheDirectionFieldOfTheCapturedShuttleBody() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        FireTvClient(transport, clock).scanForward(HOST, TOKEN)

        val call = transport.calls.single()
        assertTrue("fast-forward goes to the same media endpoint", call.url.endsWith("/v1/media?action=scan"))
        assertEquals(
            "and carries the shuttle body with direction forward, duration and speed unchanged",
            """{"direction":"forward","durationInSeconds":"10","speed":"1"}""",
            call.body
        )
    }

    /**
     * The gating read, against the object the paired stick actually returned on
     * 2026-09-18 — it advertises none of the three, and the same stick was
     * measured ignoring them (`docs/protocol.md § 2`, Control coverage).
     */
    @Test
    fun theCapabilityReadGatesOnTheDevicesOwnAnswer() {
        val measured = """{"isEpgSupported":true,"isInternalIntentSupported":true,""" +
            """"isPropertiesApiSupported":true,"isVolumeControlsSupported":false,""" +
            """"powerCapabilityLevelOfSupport":"NotSupported","ringableRemoteCount":0,""" +
            """"volumeCapabilityLevelOfSupport":"NotSupported"}"""

        assertEquals(
            "the stick that ignored volume and mute says so",
            Capabilities(volume = false, power = false),
            answering(measured).capabilities(HOST, TOKEN)
        )
        assertEquals(
            "a device that claims support keeps its controls",
            Capabilities(volume = true, power = true),
            answering("""{"isVolumeControlsSupported":true,"powerCapabilityLevelOfSupport":"Supported"}""")
                .capabilities(HOST, TOKEN)
        )
    }

    /**
     * A value is not a key. A string value spelled like the field being looked
     * for must not be read as that field — the scan has to know that the string
     * after a key's colon is its value. Mistaking it here found no colon after
     * the "key", read the field as missing, and drew volume on a device that
     * had just said no.
     */
    @Test
    fun aValueSpelledLikeTheFieldIsNotTheField() {
        assertEquals(
            Capabilities(volume = false, power = true),
            answering("""{"note":"isVolumeControlsSupported","isVolumeControlsSupported":false}""")
                .capabilities(HOST, TOKEN)
        )
    }

    /**
     * Neither a refusal nor a failure to answer is a capability verdict. Both
     * draw the controls, because a network hiccup must not be mistaken for the
     * device saying no.
     */
    @Test
    fun aCapabilityReadThatDoesNotAnswerDrawsTheControls() {
        val everything = Capabilities(volume = true, power = true)

        assertEquals(
            "a rejected read is not the device saying it cannot",
            everything,
            answering("""{"description":"Request unauthorized, missing api key"}""", status = 403)
                .capabilities(HOST, TOKEN)
        )
        assertEquals(
            "neither is a body that is not JSON",
            everything,
            answering("not json at all").capabilities(HOST, TOKEN)
        )
        assertEquals(
            "nor silence from a sleeping TV",
            everything,
            clientAnswering { throw TransportTimeout("asleep") }.capabilities(HOST, TOKEN)
        )
    }

    /** A client whose every request gets [body] and [status]. */
    private fun answering(body: String, status: Int = 200): FireTvClient =
        clientAnswering { TransportResponse(status, body) }

    private fun clientAnswering(responder: (FakeTransport.Call) -> TransportResponse): FireTvClient {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = responder
        return FireTvClient(transport, clock)
    }

    /**
     * What Back or Cancel does to a request on the wire, as the platform HTTP
     * stack reports it: a bare [java.io.InterruptedIOException] with the
     * worker's interrupt flag already cleared (measured 2026-10-04 on a Redmi
     * Note 13 Pro 5G — the stack calls `Thread.interrupted()` before it throws).
     * So the flag is never set here: the exception type is all the code under
     * test gets, as on a phone.
     */
    private fun interruptMidRequest(): Nothing =
        throw java.io.InterruptedIOException("thread interrupted")

    /**
     * A [Clock] that throws [InterruptedException] from its Nth `sleep`, the
     * way `Thread.sleep` does once `shutdownNow()` has interrupted the worker.
     * Every other sleep is passed to [inner], so gaps stay measurable. Lives
     * here rather than in the shared seams: only these tests need it.
     */
    private class InterruptingClock(
        private val inner: TestClock,
        private val interruptOnSleep: Int
    ) : Clock {
        private var sleeps = 0
        override fun nowMillis(): Long = inner.nowMillis()
        override fun sleep(millis: Long) {
            if (++sleeps == interruptOnSleep) throw InterruptedException("simulated shutdownNow")
            inner.sleep(millis)
        }
    }

    /**
     * The TV has taken keyDown when the worker is stopped mid-gap. Its key-repeat
     * loop runs until keyUp arrives — 54 s observed on a stick left holding one —
     * so the release goes out before the interrupt is passed on.
     */
    @Test
    fun interruptInKeyGapSendsKeyUpBeforeStopping() {
        val testClock = TestClock()
        val transport = FakeTransport(testClock)
        transport.responder = { TransportResponse(200, "{}") }

        try {
            FireTvClient(transport, InterruptingClock(testClock, 1)).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupt must still stop the press")
        } catch (e: InterruptedException) {
            // expected
        }

        val bodies = transport.calls.map { it.body }
        assertEquals(
            "keyDown was answered, so keyUp is owed; got: $bodies",
            listOf("""{"keyActionType":"keyDown"}""", """{"keyActionType":"keyUp"}"""),
            bodies
        )
    }

    /**
     * The interrupt can also land while keyDown is on the wire. The platform
     * HTTP stack then fails the request with an `InterruptedIOException`
     * (observed 2026-09-28 on a Redmi Note 11, 119 ms into the request) — and
     * whether the TV took the keyDown is unknown. So the release goes out
     * anyway: a keyUp for a key that is not down is a no-op, a missing one is
     * a TV that scrolls until something else stops it.
     */
    @Test
    fun interruptedKeyDownRequestStillSendsKeyUp() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            if ("keyDown" in (call.body ?: "")) interruptMidRequest()
            TransportResponse(200, "{}")
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupted keyDown must stop the press")
        } catch (e: InterruptedException) {
            // expected: the caller sees "stopped", the same as an interrupt in the gap
        }

        val bodies = transport.calls.map { it.body }
        assertEquals(
            "keyDown may have landed, so keyUp goes out; got: $bodies",
            listOf("""{"keyActionType":"keyDown"}""", """{"keyActionType":"keyUp"}"""),
            bodies
        )
        assertTrue("no wake for an interrupt — it is not a sleeping TV",
            transport.calls.none { WAKE_URL in it.url })
    }

    /**
     * The interrupt can land while keyUp itself is on the wire — Back or Cancel
     * at the end of a tap. Whether the TV got it is unknown, and a lost keyUp is
     * a TV that repeats the key with no timeout of its own. So the keyUp goes
     * out once more before the press stops, and the caller hears "stopped",
     * not a TV that failed to answer.
     */
    @Test
    fun interruptedKeyUpRequestIsSentAgainBeforeStopping() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var keyUps = 0
        transport.responder = { call ->
            if ("keyUp" in (call.body ?: "") && ++keyUps == 1) {
                interruptMidRequest()
            }
            TransportResponse(200, "{}")
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupted keyUp must stop the press")
        } catch (e: InterruptedException) {
            // expected: the same "stopped" an interrupt anywhere else in the press gives
        }

        val bodies = transport.calls.map { it.body }
        assertEquals(
            "the interrupted keyUp may not have landed, so it goes out again; got: $bodies",
            listOf(
                """{"keyActionType":"keyDown"}""",
                """{"keyActionType":"keyUp"}""",
                """{"keyActionType":"keyUp"}"""
            ),
            bodies
        )
        assertTrue("no wake for an interrupt — it is not a sleeping TV",
            transport.calls.none { WAKE_URL in it.url })
    }

    /**
     * The keyUp timed out, and Cancel lands while its retry is on the wire. The
     * retry is a keyUp like the first, so it gets the same handling: it goes
     * out once more and the press ends as stopped — not as a TV that failed to
     * answer, and with no wake.
     */
    @Test
    fun interruptDuringTheKeyUpRetryAfterATimeoutSendsItAgainAndStops() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var keyUps = 0
        transport.responder = { call ->
            if ("keyUp" in (call.body ?: "")) {
                when (++keyUps) {
                    1 -> throw TransportTimeout("keyUp timed out")
                    2 -> interruptMidRequest()
                }
            }
            TransportResponse(200, "{}")
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupted keyUp retry must stop the press")
        } catch (e: InterruptedException) {
            // expected
        }

        val bodies = transport.calls.map { it.body }
        assertEquals(
            "timed-out keyUp, interrupted retry, one more keyUp; got: $bodies",
            listOf(
                """{"keyActionType":"keyDown"}""",
                """{"keyActionType":"keyUp"}""",
                """{"keyActionType":"keyUp"}""",
                """{"keyActionType":"keyUp"}"""
            ),
            bodies
        )
        assertTrue("no wake for an interrupt", transport.calls.none { WAKE_URL in it.url })
    }

    /**
     * A one-request control (Home, Back, Menu, Play, Rewind, Forward) cancelled
     * while its request is on the wire has stopped, not failed. Reported as a
     * failure, the screen would say the TV did not answer after a Cancel, and
     * the taps queued behind it would be dropped.
     */
    @Test
    fun interruptedOneRequestPressStopsInsteadOfFailing() {
        val presses: List<Pair<String, (FireTvClient) -> Unit>> = listOf(
            "home" to { c -> c.pressKey(HOST, TOKEN, "home", keyed = false) },
            "playPause" to { c -> c.playPause(HOST, TOKEN) },
            "scanBackward" to { c -> c.scanBackward(HOST, TOKEN) },
            "scanForward" to { c -> c.scanForward(HOST, TOKEN) }
        )
        for ((name, press) in presses) {
            val clock = TestClock()
            val transport = FakeTransport(clock)
            transport.responder = { interruptMidRequest() }

            try {
                press(FireTvClient(transport, clock))
                fail("$name: an interrupted request must stop the press")
            } catch (e: InterruptedException) {
                // expected
            }

            assertEquals("$name: one request, not retried; got: ${transport.calls}", 1, transport.calls.size)
            assertTrue("$name: no wake for an interrupt", transport.calls.none { WAKE_URL in it.url })
        }
    }

    /**
     * Stopped mid-wake, before any keyDown was answered: no key is down, so
     * nothing is owed and nothing further goes out.
     */
    @Test
    fun interruptDuringWakeSendsNoFurtherKey() {
        val testClock = TestClock()
        val transport = FakeTransport(testClock)
        transport.responder = { throw TransportConnectionRefused("idle tv") }

        try {
            // Sleep 1 is the pacing before the second wake attempt.
            FireTvClient(transport, InterruptingClock(testClock, 1)).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupt must stop the wake")
        } catch (e: InterruptedException) {
            // expected
        }

        val keyCalls = transport.calls.filter { it.url.endsWith("action=dpad_right") }
        assertEquals("only the refused first keyDown went out; got: $keyCalls", 1, keyCalls.size)
        assertTrue("no keyUp for a key the TV never took",
            keyCalls.none { "keyUp" in (it.body ?: "") })
    }

    /**
     * Cancel lands while a wake request is on the wire. The platform HTTP stack
     * fails it with a bare `InterruptedIOException` — not a timeout, so not a
     * TV that failed to answer — and the wake stops there instead of reporting
     * a failed wake or trying again.
     */
    @Test
    fun interruptedWakeRequestStopsTheWake() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            if (":${FireTvClient.PORT_WAKE}/" in call.url) interruptMidRequest()
            throw TransportConnectionRefused("idle tv")
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupted wake request must stop the press")
        } catch (e: InterruptedException) {
            // expected
        }

        assertEquals("one wake attempt, not the whole loop", 1,
            transport.calls.count { ":${FireTvClient.PORT_WAKE}/" in it.url })
        assertEquals("only the refused first keyDown went out", 1,
            transport.calls.count { it.url.endsWith("action=dpad_right") })
    }

    /**
     * Cancel lands while the wake waits for the command API. An interrupted
     * status poll is not "the API answered": no keyDown follows it.
     */
    @Test
    fun interruptedStatusPollSendsNoKey() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            when {
                ":${FireTvClient.PORT_WAKE}/" in call.url -> TransportResponse(200, "")
                call.url.endsWith("/v1/FireTV/status") -> interruptMidRequest()
                else -> throw TransportConnectionRefused("idle tv")
            }
        }

        try {
            FireTvClient(transport, clock).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupted poll must stop the press")
        } catch (e: InterruptedException) {
            // expected
        }

        assertEquals("only the refused first keyDown went out", 1,
            transport.calls.count { it.url.endsWith("action=dpad_right") })
    }

    /**
     * The interrupt can arrive between requests, where nothing throws. The wake
     * checks before each thing it sends, so a cancelled press sends no magic
     * packet and no wake request.
     */
    @Test
    fun interruptBeforeTheWakeSendsNothing() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val wol = FakeWakeOnLan(clock)
        transport.responder = {
            // Cancel arrives as the first keyDown is refused.
            Thread.currentThread().interrupt()
            throw TransportConnectionRefused("idle tv")
        }

        try {
            FireTvClient(transport, clock, wakeOnLan = wol, wakeupMacFor = { "00:00:5e:00:53:aa" })
                .sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupt must stop the wake before it sends")
        } catch (e: InterruptedException) {
            // expected
        } finally {
            Thread.interrupted() // leave the test thread clean
        }

        assertTrue("no magic packet", wol.sent.isEmpty())
        assertEquals("no wake request", 0, transport.calls.count { ":${FireTvClient.PORT_WAKE}/" in it.url })
    }

    /**
     * The release itself can fail on the way out. What the caller needs to hear
     * is still "stopped", with the failed release attached rather than in its
     * place.
     */
    @Test
    fun failedReleaseDuringInterruptKeepsTheInterrupt() {
        val testClock = TestClock()
        val transport = FakeTransport(testClock)
        transport.responder = { call ->
            if ("keyUp" in (call.body ?: "")) throw TransportTimeout("keyUp lost")
            TransportResponse(200, "{}")
        }

        try {
            FireTvClient(transport, InterruptingClock(testClock, 1)).sendKey(HOST, TOKEN, "dpad_right")
            fail("an interrupt must still stop the press")
        } catch (e: InterruptedException) {
            assertTrue(
                "the failed release rides along as suppressed; got: ${e.suppressed.toList()}",
                e.suppressed.any { it is TransportTimeout }
            )
        }
        assertEquals("keyUp was tried twice, as on any press",
            2, transport.calls.count { "keyUp" in (it.body ?: "") })
    }

    /** No key is held during PIN retries; an interrupt there just stops. */
    @Test
    fun interruptDuringPinRetryStops() {
        val testClock = TestClock()
        val transport = FakeTransport(testClock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        try {
            FireTvClient(transport, InterruptingClock(testClock, 1)).verifyPin(HOST, "1234")
            fail("an interrupt must stop the retries")
        } catch (e: InterruptedException) {
            // expected
        }
        assertEquals("no verify after the interrupted wait", 1, transport.calls.size)
    }

    private companion object {
        /** The controls that ride `/v1/media` rather than the key endpoint. */
        val MEDIA_ACTIONS = setOf(
            PairingFlow.ACTION_PLAY,
            PairingFlow.ACTION_SCAN_BACK,
            PairingFlow.ACTION_SCAN_FORWARD
        )

        /** The wake call's URL fragment, matched by every wake-recovery test. */
        val WAKE_URL = "${FireTvClient.PORT_WAKE}/apps/FireTVRemote"

        /** A MAC in the shape `WAKEUP` carries. */
        const val MAC = "00:00:5e:00:53:aa"
    }

    /**
     * Play/pause is one body-less request to its own endpoint, and it is the
     * same request whether the TV is playing or paused — the device decides.
     */
    @Test
    fun playTogglesThroughTheMediaEndpoint() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{\"description\":\"OK\"}") }

        val client = FireTvClient(transport, clock)
        client.playPause(HOST, TOKEN)
        client.playPause(HOST, TOKEN)

        assertEquals("two presses, two requests — no pair", 2, transport.calls.size)
        assertEquals(
            "both presses are byte-identical; the TV infers the direction",
            transport.calls[0].url to transport.calls[0].body,
            transport.calls[1].url to transport.calls[1].body
        )
        assertTrue(
            "play/pause goes to /v1/media, not the command endpoint",
            transport.calls.all { it.url.endsWith("/v1/media?action=play") }
        )
        assertNull("body-less", transport.calls[0].body)
    }

    /**
     * What a held D-pad direction sends on each repeat: one body-less POST, which
     * the TV takes as a complete press (the test TV 2026-09-27, `.22` 2026-09-28). No
     * `keyDown` ever goes out, so there is nothing on the TV for a dead app to
     * leave held.
     */
    @Test
    fun aBodyLessDirectionIsOneRequestAndNeverAKeyDown() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { TransportResponse(200, "{}") }

        FireTvClient(transport, clock).pressKey(HOST, TOKEN, "dpad_right", keyed = false)

        assertEquals("one request, no pair", 1, transport.calls.size)
        assertTrue(transport.calls[0].url.endsWith("/v1/FireTV?action=dpad_right"))
        assertNull("body-less — no keyActionType at all", transport.calls[0].body)
        assertTrue("no gap is slept: nothing is held", clock.sleepCalls.isEmpty())
    }

    /** A discrete press recovers from a sleeping TV the same way a keyed one does. */
    @Test
    fun aDiscretePressWakesASleepingTvAndRetries() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                else -> if (attempts++ == 0) throw TransportTimeout("asleep") else TransportResponse(200, "{}")
            }
        }

        FireTvClient(transport, clock).pressKey(HOST, TOKEN, "home", keyed = false)

        val urls = transport.calls.map { it.url }
        assertEquals("press, wake, status poll, press retried", 4, urls.size)
        assertTrue("first is the press that times out", urls[0].endsWith("/v1/FireTV?action=home"))
        assertTrue("second is the wake", urls[1].contains(WAKE_URL))
        assertTrue("third is the status poll", urls[2].contains("/v1/FireTV/status"))
        assertTrue("fourth is the press, retried", urls[3].endsWith("/v1/FireTV?action=home"))
    }

    /**
     * A TV being woken takes seconds to start accepting connections, and the
     * wake is retried until it does rather than waited on once.
     *
     * Measured 2026-09-25 against `the test TV`: the vendor app's attempts — 2 s
     * each, about 2.5 s apart, each with a Wake-on-LAN packet — got through on
     * the third, 6.5 s after the first packet (`docs/protocol.md § 3`). The fake
     * TV comes up 5 s after the first attempt; every attempt before that times
     * out on its own limit, as a connect to a TV that is not listening yet does.
     */
    @Test
    fun aWakeIsRetriedUntilTheTvAnswers() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var upAt: Long? = null
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> {
                    val up = upAt ?: (call.atMillis + 5_000).also { upAt = it }
                    if (call.atMillis >= up) {
                        TransportResponse(201, "")
                    } else {
                        clock.advance(call.timeoutMs!!.toLong())
                        throw TransportTimeout("not accepting connections yet")
                    }
                }
                else -> if (attempts++ == 0) throw TransportTimeout("asleep") else TransportResponse(200, "{}")
            }
        }

        FireTvClient(transport, clock).pressKey(HOST, TOKEN, "home", keyed = false)

        val wakes = transport.calls.filter { WAKE_URL in it.url }
        assertEquals(
            "attempts start one interval apart until one lands",
            listOf(0L, FireTvClient.WAKE_ATTEMPT_INTERVAL_MS, 2 * FireTvClient.WAKE_ATTEMPT_INTERVAL_MS),
            wakes.map { it.atMillis - wakes[0].atMillis }
        )
        assertTrue(
            "each attempt carries its own short limit; got: ${wakes.map { it.timeoutMs }}",
            wakes.all { it.timeoutMs == FireTvClient.WAKE_ATTEMPT_TIMEOUT_MS }
        )
        assertTrue(
            "the press lands once the TV answers; got: ${transport.calls.map { it.url }}",
            transport.calls.last().url.endsWith("/v1/FireTV?action=home")
        )
        assertTrue(
            "only the wake carries a limit of its own — commands keep the short default " +
                "that notices a sleeping TV; got: ${transport.calls.map { it.url to it.timeoutMs }}",
            transport.calls.filter { WAKE_URL !in it.url }.all { it.timeoutMs == null }
        )
    }

    /**
     * A wake that never lands gives up once [FireTvClient.WAKE_BUDGET_MS] is
     * spent, and the press fails — no status poll, no resend into a TV that
     * never woke.
     */
    @Test
    fun aWakeThatNeverLandsGivesUpAtTheBudget() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            if (WAKE_URL in call.url) clock.advance(call.timeoutMs!!.toLong())
            throw TransportTimeout("nothing answers")
        }

        try {
            FireTvClient(transport, clock).pressKey(HOST, TOKEN, "home", keyed = false)
            fail("a TV that never answers the wake must fail the press")
        } catch (e: TransportTimeout) {
            // The last attempt's own failure is what the caller reports.
        }

        val wakes = transport.calls.filter { WAKE_URL in it.url }
        assertEquals(
            "every attempt the budget holds, and no more",
            (FireTvClient.WAKE_BUDGET_MS / FireTvClient.WAKE_ATTEMPT_INTERVAL_MS).toInt(),
            wakes.size
        )
        assertTrue(
            "the last attempt starts inside the budget",
            wakes.last().atMillis - wakes.first().atMillis < FireTvClient.WAKE_BUDGET_MS
        )
        assertTrue(
            "nothing follows a wake that never landed; got: ${transport.calls.map { it.url }}",
            transport.calls.none { "/v1/FireTV/status" in it.url } &&
                transport.calls.count { it.url.endsWith("action=home") } == 1
        )
    }

    /**
     * The budget is time, not a count of attempts. One attempt's limit covers
     * the connect and then the read, so an attempt whose connect lands late and
     * whose read then goes silent costs about twice [FireTvClient.WAKE_ATTEMPT_TIMEOUT_MS]
     * — longer than the interval. Counting attempts would then carry the wake
     * well past [FireTvClient.WAKE_BUDGET_MS]; no attempt may start once it is spent.
     */
    @Test
    fun noWakeAttemptStartsOnceTheBudgetIsSpent() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            if (WAKE_URL in call.url) clock.advance(2L * call.timeoutMs!!)
            throw TransportTimeout("nothing answers")
        }

        try {
            FireTvClient(transport, clock).pressKey(HOST, TOKEN, "home", keyed = false)
            fail("a TV that never answers the wake must fail the press")
        } catch (e: TransportTimeout) {
            // The last attempt's own failure is what the caller reports.
        }

        val wakes = transport.calls.filter { WAKE_URL in it.url }
        assertTrue(
            "every attempt starts inside the budget; started at: ${wakes.map { it.atMillis - wakes.first().atMillis }}",
            wakes.all { it.atMillis - wakes.first().atMillis < FireTvClient.WAKE_BUDGET_MS }
        )
    }

    /** The caller hears once when a press has to wake the TV, and not at all when it lands first time. */
    @Test
    fun onWakingFiresOnlyWhenAPressWakesTheTv() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(201, "")
                else -> if (attempts++ == 0) throw TransportConnectionRefused("idle") else TransportResponse(200, "{}")
            }
        }
        val client = FireTvClient(transport, clock)
        var wakings = 0

        client.pressKey(HOST, TOKEN, "home", keyed = false, onWaking = { wakings++ })
        assertEquals("a press that needed a wake", 1, wakings)

        client.pressKey(HOST, TOKEN, "home", keyed = false, onWaking = { wakings++ })
        assertEquals("a press that lands first time wakes nothing", 1, wakings)
    }

    /**
     * A deeply asleep Fire TV wakes on a Wake-on-LAN magic packet, not on the
     * DIAL request: measured 2026-09-25, when one packet sent from a laptop
     * woke `the test TV` with no request at all, after sixteen DIAL-only attempts
     * had not (`docs/protocol.md § 3`). With the TV's MAC known, every wake
     * attempt sends the packet as it starts, as the vendor app's does.
     */
    @Test
    fun eachWakeAttemptSendsAMagicPacketAsItStarts() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val wol = FakeWakeOnLan(clock)
        var upAt: Long? = null
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> {
                    val up = upAt ?: (call.atMillis + 5_000).also { upAt = it }
                    if (call.atMillis >= up) {
                        TransportResponse(201, "")
                    } else {
                        clock.advance(call.timeoutMs!!.toLong())
                        throw TransportTimeout("not accepting connections yet")
                    }
                }
                else -> if (attempts++ == 0) throw TransportTimeout("asleep") else TransportResponse(200, "{}")
            }
        }

        FireTvClient(transport, clock, wakeOnLan = wol, wakeupMacFor = { MAC })
            .pressKey(HOST, TOKEN, "home", keyed = false)

        val wakes = transport.calls.filter { WAKE_URL in it.url }
        assertEquals("one packet per attempt, for the TV's own MAC", List(wakes.size) { MAC }, wol.sent.map { it.mac })
        assertEquals(
            "each packet goes out as its attempt starts",
            wakes.map { it.atMillis },
            wol.sent.map { it.atMillis }
        )
    }

    /** A TV that announced no MAC is woken by the DIAL request alone — no packet is invented for it. */
    @Test
    fun withoutAMacTheWakeSendsNoPacket() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        val wol = FakeWakeOnLan(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(201, "")
                else -> if (attempts++ == 0) throw TransportConnectionRefused("idle") else TransportResponse(200, "{}")
            }
        }

        FireTvClient(transport, clock, wakeOnLan = wol, wakeupMacFor = { null })
            .pressKey(HOST, TOKEN, "home", keyed = false)

        assertTrue("the DIAL wake still went out", transport.calls.any { WAKE_URL in it.url })
        assertTrue("but no packet did; got: ${wol.sent}", wol.sent.isEmpty())
    }

    @Test
    fun wakeBudgetIsAWholeNumberOfAttempts() {
        assertEquals(0L, FireTvClient.WAKE_BUDGET_MS % FireTvClient.WAKE_ATTEMPT_INTERVAL_MS)
        assertTrue(
            "a connect that times out ends before the next attempt is due",
            FireTvClient.WAKE_ATTEMPT_TIMEOUT_MS < FireTvClient.WAKE_ATTEMPT_INTERVAL_MS
        )
    }

    // --- pairing an idle TV -------------------------------------------------
    //
    // Found live 2026-09-17: a scan listed four Fire TVs by name, and tapping
    // two of them answered "nothing is listening". Both had answered the SSDP
    // search seconds earlier, so the address was right and the TV was on — its
    // control API had gone idle. The wake existed and the press path used it;
    // the pairing entry points did not.
    //
    // The device that woke was *Living Room Fire TV*. Do not read the fixtures
    // below as describing the test TV: the test TV is the other side of this boundary —
    // fully asleep, answering neither the SSDP search nor the control port, so
    // it never appears in a scan at all. That state was taken to need the vendor
    // app's cloud websocket; on 2026-09-25 the vendor app's plain LAN wake brought
    // the test TV back after a 7.4 s answer instead, and one Wake-on-LAN packet woke it
    // outright (`docs/protocol.md § 3`, "Waking a deeply asleep TV — the magic
    // packet"). The fixtures therefore use a
    // neutral name, so a renamed or retired television cannot make them read as
    // a claim about which device was observed.

    @Test
    fun aRefusedPinDisplayWakesTheTvAndRetries() {
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var attempts = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url -> TransportResponse(403, "unauthorized")
                else -> if (attempts++ == 0) {
                    throw TransportConnectionRefused("nothing listening")
                } else {
                    TransportResponse(200, "")
                }
            }
        }

        FireTvClient(transport, clock).requestPin(HOST, "TestTV")

        val urls = transport.calls.map { it.url }
        assertTrue(
            "a refused pin/display must wake the TV; got: $urls",
            urls.any { WAKE_URL in it }
        )
        assertTrue(
            "the status endpoint is polled between wake and retry; got: $urls",
            urls.any { "/v1/FireTV/status" in it }
        )
        assertEquals(
            "the PIN request is issued again after the wake; got: $urls",
            2,
            urls.count { it.endsWith("/v1/FireTV/pin/display") }
        )
    }

    @Test
    fun aRejectedPinDisplayIsNotWorthAWake() {
        // The TV answered — it is up, so the failure is already final and a
        // wake would spend its settle window learning nothing.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        transport.responder = { call ->
            if (WAKE_URL in call.url) TransportResponse(200, "")
            else TransportResponse(500, "")
        }

        var thrown: TransportStatus? = null
        try {
            FireTvClient(transport, clock).requestPin(HOST, "TestTV")
        } catch (e: TransportStatus) {
            thrown = e
        }

        assertNotNull("a rejected pin/display must surface as a status failure", thrown)
        assertTrue(
            "a TV that answered must not be woken",
            transport.calls.none { WAKE_URL in it.url }
        )
    }

    @Test
    fun aRefusedPinVerifyWakesTheTvAndRetriesTheSameAttempt() {
        // Retried within the attempt, not around the loop: each verify attempt
        // spends one of the TV's own pairing attempts, so a wake must not hand
        // the user a fourth one it was never granted.
        val clock = TestClock()
        val transport = FakeTransport(clock)
        var verifyCalls = 0
        transport.responder = { call ->
            when {
                WAKE_URL in call.url -> TransportResponse(200, "")
                "/v1/FireTV/status" in call.url -> TransportResponse(403, "unauthorized")
                "/v1/FireTV/pin/verify" in call.url -> {
                    verifyCalls++
                    if (verifyCalls == 1) throw TransportConnectionRefused("went idle")
                    TransportResponse(200, """{"description":"zBMBFhY"}""")
                }
                else -> TransportResponse(200, "")
            }
        }

        val outcome = FireTvClient(transport, clock).verifyPin(HOST, "1234")

        assertTrue("expected a paired outcome, got $outcome", outcome is PinOutcome.Paired)
        assertTrue(
            "a refused pin/verify must wake the TV",
            transport.calls.any { WAKE_URL in it.url }
        )
        assertEquals(
            "the wake retries the attempt that failed rather than restarting the budget",
            2,
            verifyCalls
        )
    }
}
