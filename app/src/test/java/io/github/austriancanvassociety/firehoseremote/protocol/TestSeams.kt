package io.github.austriancanvassociety.firehoseremote.protocol

import java.util.Collections

/**
 * Test-only seam: a synthetic `Clock` where sleep() returns immediately but
 * advances a virtual "now" counter. Lets tests inspect millisecond gaps in
 * `FireTvClient` without wall-clock waits.
 */
class TestClock(private var now: Long = 0L) : Clock {
    val sleepCalls = mutableListOf<Long>()

    @Synchronized override fun nowMillis(): Long = now

    @Synchronized override fun sleep(millis: Long) {
        sleepCalls.add(millis)
        now += millis
    }

    /**
     * Move "now" without recording a sleep: time a fake transport spends — a
     * connect that times out, say — rather than time the client chose to wait.
     */
    @Synchronized fun advance(millis: Long) {
        now += millis
    }
}

/**
 * Test-only seam: an [SsdpSearch] that answers with canned responses instead of
 * opening a socket. Records how many searches ran, so a test can prove the
 * sweep stayed out of the way when the search already found something.
 */
class FakeSsdp(
    var responses: List<Ssdp.SsdpResponse> = emptyList()
) : SsdpSearch {

    /** Search windows requested, in order. */
    val searches = mutableListOf<Long>()

    /** When set, [search] throws this instead of answering. */
    var failure: RuntimeException? = null

    override fun search(timeoutMillis: Long): List<Ssdp.SsdpResponse> {
        searches.add(timeoutMillis)
        failure?.let { throw it }
        return responses
    }

    /** Build a response the way a real datagram parse would. */
    companion object {
        /**
         * The default USN carries a uuid derived from the address, so two
         * fixtures built for different addresses are two devices unless a test
         * deliberately gives them the same uuid.
         */
        fun response(
            ip: String,
            location: String = "http://$ip:60000/dd.xml",
            usn: String? = "uuid:test-$ip::${Ssdp.SERVICE_TYPE}",
            wakeupMac: String? = null
        ) = Ssdp.SsdpResponse(sourceIp = ip, location = location, usn = usn, wakeupMac = wakeupMac)
    }
}

/**
 * Test-only seam: a [WakeOnLan] that records each magic packet it was asked to
 * send, and when — so a test can line the packets up against the wake requests.
 */
class FakeWakeOnLan(private val clock: TestClock = TestClock()) : WakeOnLan {

    data class Sent(val mac: String, val atMillis: Long)

    val sent = mutableListOf<Sent>()

    override fun send(mac: String) {
        sent += Sent(mac, clock.nowMillis())
    }
}

/**
 * Test-only seam: a `Transport` that records every call and dispatches to a
 * pluggable responder. Thread-safe so parallel Discovery scans can be tested.
 */
class FakeTransport(private val clock: TestClock = TestClock()) : Transport {

    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
        val atMillis: Long,
        /** The per-request limit the caller asked for; null means the transport's own. */
        val timeoutMs: Int?
    )

    private val _calls = Collections.synchronizedList(mutableListOf<Call>())
    val calls: List<Call> get() = _calls.toList()

    @Volatile
    var responder: (Call) -> TransportResponse = { TransportResponse(200, "") }

    override fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int?
    ): TransportResponse {
        val call = Call(method, url, body, headers, clock.nowMillis(), timeoutMs)
        _calls.add(call)
        return responder(call)
    }
}
