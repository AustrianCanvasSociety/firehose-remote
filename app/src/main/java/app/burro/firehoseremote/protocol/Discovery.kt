package app.burro.firehoseremote.protocol

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A TV this app can show the user.
 *
 * [answering] is false for a device the app knows about but this scan did not
 * hear from — a TV already paired with that is now off or asleep. It is a
 * fourth state alongside [ProbeOutcome]'s three, and it is deliberately not
 * folded into them: those describe what a *probe* found, and nothing probed
 * this device at all. Calling it "unreachable" would be a cause this code has
 * not earned; "not answering" is the one thing actually observed, which is that
 * the network said nothing about it.
 *
 * Defaulted to true so every existing construction site — `Discovery`, the SSDP
 * path and every test fixture — keeps meaning "this answered" without an edit.
 *
 * [wakeupMac] is the MAC the device announced in its SSDP `WAKEUP` header, or
 * null when it announced none — the only address a Wake-on-LAN packet can be
 * built from ([WakeOnLan]). Only the SSDP path can know it.
 */
data class Device(
    val name: String,
    val ip: String,
    val answering: Boolean = true,
    val wakeupMac: String? = null
)

/**
 * What a single probe of one host found.
 *
 * Four answers rather than a nullable [Device], because "no" had been standing
 * in for three different facts. A host that timed out, a host that refused the
 * connection, and a host that answered with something unusable all read as
 * `null`, so a TV whose control API had gone silent was indistinguishable from
 * an empty network — the scan came back short and said nothing about why.
 */
sealed class ProbeOutcome {
    /** Answered, and looks like a Fire TV command API. */
    data class Found(val device: Device) : ProbeOutcome()

    /** Answered, but not with anything this client recognises as a Fire TV. */
    object NotAFireTv : ProbeOutcome()

    /**
     * Accepted the TCP connection and then never replied. This is the signature
     * of a **sleeping TV — or of a control API that has stopped servicing
     * requests while the TV is awake and playing**; the two are byte-identical
     * on the wire (`docs/protocol.md § 3`). Never report which of the two it is.
     */
    object NotAnswering : ProbeOutcome()

    /** Refused the connection outright — nothing is listening at that address. */
    object Refused : ProbeOutcome()
}

/**
 * The result of scanning a range: the devices found, plus the counts needed to
 * tell an empty result apart from a silent network.
 */
data class ScanResult(
    val devices: List<Device>,
    /** Hosts that accepted a connection and then went quiet. */
    val notAnswering: Int,
    /** Hosts that refused the connection. */
    val refused: Int
) {
    /**
     * Nothing was found, but at least one host went silent after connecting —
     * so something is there and is not answering.
     */
    val sawSilentHost: Boolean get() = devices.isEmpty() && notAnswering > 0

    /**
     * Nothing was found, but at least one host refused the connection.
     *
     * A third fact, not a restatement of [sawSilentHost]: a refusal proves a
     * host is there *and* that nothing is listening on the control port, which
     * is what an idle Fire TV presents (`docs/protocol.md § 3`, measured
     * 2026-09-17 — port open while in use, refused once the screen saver
     * started). It still does not say the TV is asleep; a TV that is on and
     * playing with a wedged API times out rather than refusing, and which of the
     * two it is remains the one thing this app never guesses at.
     */
    val sawRefusingHost: Boolean get() = devices.isEmpty() && refused > 0
}

/**
 * Finding the devices that answer on this network.
 *
 * Two mechanisms, in order, for two different networks.
 *
 * **SSDP first.** A multicast `M-SEARCH` for the DIAL service is what the
 * vendor app itself does ([Ssdp]), and it is the better question to ask: a host
 * answers it only if it serves the DIAL service, so identity comes from the
 * device rather than from a status code. It is also one datagram instead of 254
 * connections, so a scan that used to take seconds can take one.
 *
 * **The address sweep second, and only when SSDP came back empty.** Some
 * networks carry unicast and drop multicast, and on those the search hears
 * nothing while the TVs are plainly reachable. The sweep is what covers that
 * case — it is kept for the network shape, not as a second opinion, which is
 * why it does not run when SSDP already answered.
 *
 * The sweep is the version Phase 1 shipped, with one change: what it asks. See
 * [probe].
 */
class Discovery(
    private val transport: Transport,
    private val ssdp: SsdpSearch = SsdpSearch.None,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val clock: Clock = SystemClock
) {

    fun scan(cidr: String): ScanResult {
        // Expanded first, so a malformed range is refused before a single
        // packet leaves the phone — neither the search below nor the sweep
        // behind it runs on a range that cannot be valid.
        val hosts = enumerateHosts(cidr)
        val bySsdp = discoverBySsdp()
        if (bySsdp.isNotEmpty()) return ScanResult(devices = bySsdp, notAnswering = 0, refused = 0)
        return sweep(hosts)
    }

    /**
     * Ask the network, and read the names out of each answer's own descriptor.
     *
     * Every responder is listed. Answering the DIAL search proves the device
     * serves DIAL, not that it is a Fire TV — a Samsung TV answers the identical
     * search — so this is deliberately not an identity filter: the list shows
     * what answered and the user picks. The descriptor is fetched only for the
     * name, so a device whose descriptor cannot be reached is still listed,
     * under its address. That is the same fallback Phase 1 shipped, and it is the
     * right way round: a missing name costs a label, a missing device costs the
     * pairing.
     */
    private fun discoverBySsdp(): List<Device> {
        val responses = try {
            ssdp.search(SSDP_WINDOW_MS)
        } catch (e: Error) {
            throw e
        } catch (e: RuntimeException) {
            // A fault in the search is not a fact about the network, but falling
            // through to the sweep is the right recovery: the sweep exists for
            // the network that drops multicast, and it still finds the TV. The
            // known cost is stated rather than hidden — the sweep is ~254 probes
            // and nothing on screen tells the user the better mechanism was
            // skipped. Carrying that distinction needs a shape the caller can
            // see, which this function's return type does not have.
            return emptyList()
        }

        // The naming phase gets a wall-clock budget of its own. Capping the
        // *count* of fetches at Ssdp.MAX_RESPONDERS does not cap their duration,
        // and each is a serial request — a stranger answering 32 times with
        // blackholed `LOCATION` values would otherwise hold the scan, and with it
        // the single worker thread the whole UI shares, for a minute or more.
        // Past the budget the remaining devices keep their address as a name,
        // which is the same fallback an unreachable descriptor already gets.
        val namingDeadline = clock.nowMillis() + NAMING_BUDGET_MS

        return responses
            // Keyed on the address the answer came from, not on the `USN` it
            // claims. A USN is an unauthenticated assertion: any host on the LAN
            // can read the real TV's uuid out of the TV's own `ssdp:alive`
            // multicast, answer the search claiming it, and win the first-wins
            // dedupe — which leaves the impostor as the only entry for that TV,
            // so the user pairs with it and the token the TV mints passes
            // through the attacker. An address cannot be claimed that way,
            // because a forged source address never receives this phone's
            // descriptor request.
            //
            // The cost is real and is the honest trade: a TV that answers from
            // two addresses now lists twice, which is how a live scan on
            // 2026-09-17 returned "Home Gym" from two addresses. Two
            // entries for one TV, both genuinely reachable, is worth less than a
            // picker that can be silently replaced.
            .distinctBy { Ssdp.deviceKey(it.usn, it.sourceIp) }
            // Bounded here as well as in the socket client. This is the seam
            // every fake traverses, and each entry costs one descriptor fetch —
            // serialized, on the worker thread the UI shares. One hostile
            // responder choosing a fresh uuid per reply otherwise decides how
            // much work this phone does.
            .take(Ssdp.MAX_RESPONDERS)
            .map {
                Device(name = nameFor(it, namingDeadline) ?: it.sourceIp, ip = it.sourceIp, wakeupMac = it.wakeupMac)
            }
    }

    /**
     * The device's own name, or null when its descriptor cannot be read.
     *
     * Never throws for a network fault: the search already proved this device
     * answers, and losing its name must not lose it from the list.
     */
    private fun nameFor(response: Ssdp.SsdpResponse, deadline: Long): String? {
        if (clock.nowMillis() >= deadline) return null
        if (!isFetchableDescriptorAddress(response.location)) return null
        return try {
            val descriptor = transport.request(
                method = "GET",
                url = response.location,
                body = null,
                headers = emptyMap()
            )
            if (descriptor.ok) Ssdp.parseFriendlyName(descriptor.body) else null
        } catch (e: Error) {
            throw e
        } catch (e: RuntimeException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Whether a device descriptor may be fetched from this address.
     *
     * The responder chooses its own `LOCATION`, and this phone fetches it with
     * no headers and no binding to the address the answer came from — so without
     * a check, one multicast packet asks the phone to issue a request to any host
     * the sender names. On a phone inside a LAN that is a request the sender
     * could not necessarily make itself.
     *
     * The complete fix — requiring the descriptor's host to be the address the
     * answer arrived from — is deliberately **not** applied here, because it is
     * unverified. `docs/protocol.md § 5` records the `LOCATION` *paths* Fire OS
     * sends but not whether their host always equals the responder's address, and
     * guessing wrong would drop the user's own TV from the picker. This bounds
     * the damage without that risk: a descriptor is never fetched from loopback,
     * from link-local (which is where cloud metadata services answer), from the
     * "this network" range, or from multicast and reserved space.
     */
    private fun isFetchableDescriptorAddress(location: String): Boolean {
        val start = location.indexOf("://") + 3
        if (start < 3) return false
        val host = location.substring(start).substringBefore('/').substringBefore(':')
        val parsed = Ipv4.parse(host)
        if (parsed !is AddressParse.Valid) return false
        val octets = parsed.normalized.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 0 -> false
            first >= 224 -> false
            else -> true
        }
    }

    /** The Phase 1 sweep: probe every host in the range, in parallel. */
    private fun sweep(hosts: List<String>): ScanResult {
        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = hosts.map { ip ->
                executor.submit(Callable { probe(ip) })
            }
            val outcomes = futures.map { collect(it) }
            return ScanResult(
                devices = outcomes.filterIsInstance<ProbeOutcome.Found>().map { it.device },
                notAnswering = outcomes.count { it is ProbeOutcome.NotAnswering },
                refused = outcomes.count { it is ProbeOutcome.Refused }
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Await one probe's result.
     *
     * `Error` is deliberately rethrown rather than folded into an outcome. An
     * Error is a fault in this process, not a fact about a host — turning it
     * into "no Fire TV here" would report a broken scan as an empty network.
     */
    private fun collect(future: Future<ProbeOutcome>): ProbeOutcome = try {
        future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        ProbeOutcome.NotAnswering
    } catch (e: ExecutionException) {
        val cause = e.cause
        if (cause is Error) throw cause
        ProbeOutcome.NotAnswering
    } catch (e: InterruptedException) {
        // Restore the flag so the cancellation keeps propagating, and let it
        // out: `scan`'s finally still shuts the pool down, which is what makes
        // a scan cancellable at all.
        Thread.currentThread().interrupt()
        throw e
    }

    /**
     * Ask one host whether it is a Fire TV.
     *
     * The question changed in Step 3, and it is the whole of the endpoint
     * discrimination this step set out to do. It used to be `GET /`, where any
     * answer at all — `200` from a router's admin page, `401` from a dev server
     * — counted as a TV, so the picker offered devices that were nothing of the
     * kind. It also could not be tightened by excluding statuses, because a
     * *real* Fire TV answers `405` to `GET /` (`docs/protocol.md § 7`): the old
     * question had no answer that separated the two.
     *
     * `/v1/FireTV/status` does separate them. It is a documented endpoint on the
     * command API, and this client already leans on it — [FireTvClient] polls
     * the same path to decide whether a woken TV has come up. A Fire TV answers
     * it `200` given a token, or `401`/`403` without one; an auth-gated answer
     * is itself the signal, because the path resolved and a REST API answered
     * it. Anything else — `404` for a path that does not exist, `405`, or a
     * router's login page — is not this API.
     *
     * Counting `401`/`403` as success is not laxity. It is the same distinction
     * the wake path draws: the host answered *as this API*, and merely will not
     * proceed without a token.
     */
    private fun probe(ip: String): ProbeOutcome = try {
        val res = transport.request(
            method = "GET",
            url = "https://$ip:${FireTvClient.PORT_COMMAND}$STATUS_PATH",
            body = null,
            headers = mapOf("X-Api-Key" to FireTvClient.API_KEY)
        )
        if (res.status in COMMAND_API_STATUSES) ProbeOutcome.Found(Device(name = ip, ip = ip))
        else ProbeOutcome.NotAFireTv
    } catch (t: TransportTimeout) {
        ProbeOutcome.NotAnswering
    } catch (t: TransportConnectionRefused) {
        ProbeOutcome.Refused
    } catch (e: IOException) {
        // A host that will not complete a TLS handshake, or answers with
        // something this client cannot read, is not the command API. `Error` is
        // not caught here, for the reason given on collect().
        ProbeOutcome.NotAFireTv
    }

    /**
     * The addresses in a CIDR range.
     *
     * The per-octet check is [Ipv4.parse], authored in Step 2 and consumed here
     * rather than written a second time — so a validation gap has one place to
     * be fixed. It is applied to the *prefix*, before the range is expanded:
     * the parser rejects anything carrying a `/`, and by then the prefix has
     * been split off. Without this, `999.999.999.0/24` expanded to 254
     * nonsense hosts and the scan spent thirty seconds finding nothing.
     */
    internal fun enumerateHosts(cidr: String): List<String> {
        val slash = cidr.indexOf('/')
        if (slash < 0) throw IllegalArgumentException("firehose-remote: discovery: not a CIDR: $cidr")
        val prefix = cidr.substring(0, slash)
        val bits = cidr.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("firehose-remote: discovery: bad prefix length: $cidr")
        if (bits != 24) {
            throw IllegalArgumentException("firehose-remote: discovery: only /24 supported for now, got /$bits")
        }
        val parsed = Ipv4.parse(prefix)
        if (parsed is AddressParse.Rejected) {
            throw IllegalArgumentException(
                "firehose-remote: discovery: bad IP in CIDR: $prefix (${parsed.defect})"
            )
        }
        val base = prefix.split('.').take(3).joinToString(".")
        return (1..254).map { "$base.$it" }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 16
        const val PROBE_TIMEOUT_SECONDS = 5L

        /**
         * How long the SSDP search listens, over and above the sends.
         *
         * The search asks responders to answer within `MX` seconds, so this
         * must exceed that; the margin covers a TV that is slow to answer.
         */
        const val SSDP_WINDOW_MS = 2500L

        /**
         * How long the whole descriptor-naming phase may take, in milliseconds.
         *
         * A ceiling on the phase, not on one request: `Ssdp.MAX_RESPONDERS`
         * bounds how many descriptors are fetched and this bounds how long the
         * fetching may run. On a working LAN the whole phase is under two
         * seconds, so this only ever fires on a network that is answering the
         * search but not serving descriptors — which is what a hostile or
         * half-broken one looks like. Devices whose name was not reached keep
         * their address, the same fallback an unreachable descriptor gets.
         */
        const val NAMING_BUDGET_MS = 5000L

        /**
         * The documented endpoint that distinguishes this API from any other
         * web server that happens to be listening on the port.
         */
        private const val STATUS_PATH = "/v1/FireTV/status"

        /**
         * Answers that mean "the command API is here".
         *
         * `200` is the API answering. `401` and `403` are the API refusing —
         * which still proves the path exists and is served, and is the same
         * evidence the wake path accepts.
         */
        private val COMMAND_API_STATUSES = setOf(200, 401, 403)
    }
}
