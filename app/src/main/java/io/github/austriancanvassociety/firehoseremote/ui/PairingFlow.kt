package io.github.austriancanvassociety.firehoseremote.ui

import io.github.austriancanvassociety.firehoseremote.R
import io.github.austriancanvassociety.firehoseremote.protocol.AddressDefect
import io.github.austriancanvassociety.firehoseremote.protocol.AddressParse
import io.github.austriancanvassociety.firehoseremote.protocol.Capabilities
import io.github.austriancanvassociety.firehoseremote.protocol.Clock
import io.github.austriancanvassociety.firehoseremote.protocol.Device
import io.github.austriancanvassociety.firehoseremote.protocol.Discovery
import io.github.austriancanvassociety.firehoseremote.protocol.FireTvClient
import io.github.austriancanvassociety.firehoseremote.protocol.Ipv4
import io.github.austriancanvassociety.firehoseremote.protocol.PairedDevice
import io.github.austriancanvassociety.firehoseremote.protocol.PinOutcome
import io.github.austriancanvassociety.firehoseremote.protocol.SsdpSearch
import io.github.austriancanvassociety.firehoseremote.protocol.SystemClock
import io.github.austriancanvassociety.firehoseremote.protocol.TokenStore
import io.github.austriancanvassociety.firehoseremote.protocol.Transport
import io.github.austriancanvassociety.firehoseremote.protocol.TransportConnectionRefused
import io.github.austriancanvassociety.firehoseremote.protocol.TransportStatus
import io.github.austriancanvassociety.firehoseremote.protocol.WakeOnLan
import java.io.IOException

/**
 * Scan → select → pair, as a state machine over the [Transport] and [TokenStore]
 * seams. Deliberately free of `android.*` so the whole flow is covered by JVM
 * unit tests without a device — the screens hold the Android types, this holds
 * the decisions.
 *
 * [cidrProvider] is how the flow learns which network to scan without knowing
 * anything about Android: the activity reads the phone's own /24 off
 * `ConnectivityManager` and hands back a CIDR string, or null when the phone is
 * not on Wi-Fi or Ethernet — the only networks a TV on the home LAN can be on.
 * A press reads it too: null there means no press can reach the TV.
 */
class PairingFlow(
    transport: Transport,
    private val tokenStore: TokenStore,
    private val cidrProvider: () -> String?,
    clock: Clock = SystemClock,
    private val ssdp: SsdpSearch = SsdpSearch.None,
    wakeOnLan: WakeOnLan = WakeOnLan.None,
    /**
     * Whether a VPN is on. A VPN that tunnels everything keeps the phone off
     * the home network, and every request then fails the way a TV that is off
     * does — so a failure that reached nothing names the VPN, the likelier cause.
     */
    private val vpnOn: () -> Boolean = { false },
    /**
     * Whether the first-run screen has been shown, and how to record that it
     * has. Injected rather than read here so the flow keeps its distance from
     * `android.*` and the decision stays testable without a device — the same
     * reason [tokenStore] is a seam.
     *
     * Both default to "already seen", so every construction site from before
     * the screen, and every test that is not about it, behaves as it did.
     */
    private val introSeen: () -> Boolean = { true },
    private val markIntroSeen: () -> Unit = {}
) {

    private val discovery = Discovery(transport, ssdp)
    private val client = FireTvClient(transport, clock, wakeOnLan = wakeOnLan, wakeupMacFor = ::wakeupMacFor)

    /**
     * What the last scan found, kept so Back can return to it.
     *
     * Without this the list lived only in the rendered screen, so leaving the
     * PIN entry threw it away and the user had to scan again — which is the
     * thing that discards a slow scan's result. The flow holds it, not the
     * screen, because the screen is rebuilt on every state change and a
     * rotation would empty it.
     */
    private var lastDiscovered: List<Device> = emptyList()

    sealed class State {
        /** Nothing paired yet — the screen offers the Scan button. */
        object Scanning : State()

        /**
         * Nothing paired, and the first-run screen has not been shown.
         *
         * The one screen that says what the app needs before it asks for
         * anything: the phone on the same Wi-Fi as the TV, and a PIN read off
         * the TV once, one time.
         *
         * A state rather than an overlay on [Scanning], so "shown once" is a
         * decision the flow makes and a JVM test can drive — and so it cannot
         * reappear behind a scan the user has already started.
         */
        object FirstRun : State()

        /** A scan finished; these are the TVs it found. */
        data class Discovered(val devices: List<Device>) : State()

        /**
         * A scan found nothing, and at least one host took the connection and
         * then went quiet. Something is out there and is not answering — which
         * is **not** the same fact as an empty network.
         *
         * It also does not say *why* it is not answering: a sleeping TV and a
         * control API that has stopped servicing requests while the TV is awake
         * and playing are indistinguishable on the wire (`docs/protocol.md § 3`).
         * Never tell the user it is asleep.
         */
        object HostDidNotAnswer : State()

        /**
         * A scan found nothing, and at least one host refused the connection.
         *
         * Distinct from [HostDidNotAnswer], which is a host that accepted the
         * connection and then went quiet. A refusal is a host that would not
         * accept it at all — nothing listening on the control port — which is
         * the shape an idle Fire TV presents (`docs/protocol.md § 3`). Like
         * [HostDidNotAnswer] it says what to do and never says the TV is asleep.
         */
        object HostRefused : State()

        /** A TV was picked and asked to show a PIN; the screen collects it. */
        data class AwaitingPin(val device: Device) : State()

        /** Paired and persisted — this is the TV the app now drives. */
        data class Paired(val device: PairedDevice) : State()

        /**
         * Something went wrong. [message] is written for a stranger to read —
         * never an exception's own words. [cause] says which kind of failure it
         * was, so the screen can respond to the kind without parsing prose.
         *
         * [attemptedHost] is the raw text the user typed on the typed-address
         * route, carried on failure so the retry screen can present it in the
         * address field. Never set on failures reached through the scanned-list
         * route, since the user never typed anything there. Null on every path
         * that is not [selectByAddress].
         */
        data class Failed(
            val message: String,
            val cause: Cause,
            val attemptedHost: String? = null,
        ) : State()

        enum class Cause {
            /** The phone is on no network, so there is nothing to scan. */
            NoNetwork,

            /** The network could not be scanned at all. */
            ScanFailed,

            /** A TV was picked but never answered. */
            TvNotReachable,

            /** The TV answered, but not with anything this app calls a token. */
            PairingRefused,

            /**
             * Something failed that this app has no name for — an unexpected
             * `RuntimeException` on the worker thread. Carried as a state rather
             * than left to the executor, which would swallow it and leave the
             * screen on the busy message with nothing to act on.
             */
            Unexpected,

            /** The TV answered all three attempts without ever minting a token. */
            PairingIncomplete,

            /**
             * A typed address was refused here, before anything was contacted.
             *
             * Deliberately not [TvNotReachable]. Nothing was sent, so telling
             * the user their TV did not answer would be a confidently wrong
             * explanation for their own typo — the same failure shape the
             * discovery work exists to stop making
             * (`Discovery.ProbeOutcome`).
             */
            MalformedAddress,

            /**
             * The PIN field was submitted empty, so nothing was sent.
             *
             * Its own cause for the same reason as [MalformedAddress]: this is
             * not the TV refusing anything. An empty PIN consumes one of the
             * TV's pairing attempts if it is allowed through, so the honest
             * report is that nothing was tried — not that the TV said no.
             */
            EmptyPin
        }
    }

    /**
     * What the screen should show on launch. A stored pairing short-circuits
     * straight to the paired TV; otherwise we start at the Scan button rather
     * than scanning unprompted.
     */
    fun start(): State {
        val stored = tokenStore.load()
        return when {
            stored != null -> State.Paired(stored)
            // Once, and only before anything is paired. Someone who has paired
            // before does not need telling what the app needs.
            !introSeen() -> State.FirstRun
            else -> State.Scanning
        }
    }

    /**
     * Leave the first-run screen for the Scan button.
     *
     * The only transition that records it as seen — the flag and the state move
     * together here rather than the screen writing one and returning the other.
     */
    fun dismissIntro(): State {
        markIntroSeen()
        return State.Scanning
    }

    /**
     * Scan the local network. Blocking — call it off the main thread.
     *
     * Reachable from every state, including [State.Paired]: scanning again is
     * this app's own primary action, not a recovery step, so nothing has to go
     * wrong first.
     */
    fun scan(): State {
        val cidr = cidrProvider()
            ?: return State.Failed(
                "This phone isn't on Wi-Fi. Join the same Wi-Fi as your TV, then scan again.",
                State.Cause.NoNetwork
            )
        return try {
            val result = discovery.scan(cidr)
            // A scan is the only time a TV's `WAKEUP` MAC is heard, and a TV
            // asleep deeply enough to need it answers nothing — so an answer
            // that carries one fills in a stored pairing's missing MAC now,
            // while it can. It never replaces one, the same rule as
            // [learnWakeupMac]: an answer is an unauthenticated UDP datagram
            // whose source address the sender writes, so a replace would let
            // anyone on the LAN point the wake at other hardware. Answers that
            // disagree about the MAC never get this far: [Discovery] gives the
            // address no MAC at all.
            val missing = tokenStore.all().filter { it.wakeupMac == null }.map { it.host }.toSet()
            for (device in result.devices) {
                if (device.ip in missing) device.wakeupMac?.let { tokenStore.rememberWakeupMac(device.ip, it) }
            }
            // The stored TV joins the list *before* the branch below, not inside
            // its `Discovered` arm. An off or asleep TV answers nothing, so a
            // scan that heard from no one is precisely the case a stored device
            // has to survive — and the `sawSilentHost` arm would otherwise
            // replace the list with a message, hiding the one device the user
            // can still act on.
            val devices = withStoredDevices(result.devices)
            // Recorded before the branch, so Back from the PIN screen returns
            // the list whichever way this scan came out.
            lastDiscovered = devices
            when {
                devices.isNotEmpty() -> State.Discovered(devices)
                result.sawSilentHost -> State.HostDidNotAnswer
                result.sawRefusingHost -> State.HostRefused
                else -> State.Discovered(emptyList())
            }
        } catch (e: IllegalArgumentException) {
            State.Failed(
                "This network couldn't be scanned. Check the phone's Wi-Fi and try again.",
                State.Cause.ScanFailed
            )
        }
    }

    /**
     * The scan result plus every TV this app has already paired with, when the
     * scan did not hear from them.
     *
     * A paired TV that is off or asleep answers nothing, so a list built purely
     * from what the network said drops the devices the user can still act on —
     * while the app already holds their names, addresses and tokens. The cost
     * is the mirror of the one `Discovery` already pays by keying on the
     * address: a TV that moved to a new address since it was paired is listed
     * twice, at its old address and its new one. Both entries are ones the
     * user can act on, which is the trade `Discovery`'s own comment already
     * argues for.
     *
     * Appended rather than woven in: the devices the network confirmed come
     * first, and the ones it did not answer for sit under them, in the store's
     * insertion order.
     */
    private fun withStoredDevices(found: List<Device>): List<Device> {
        val stored = tokenStore.all()
        val foundHosts = found.map { it.ip }.toSet()
        val missing = stored.filterNot { it.host in foundHosts }
        if (missing.isEmpty()) return found
        return found + missing.map {
            Device(name = it.name, ip = it.host, answering = false, wakeupMac = it.wakeupMac)
        }
    }

    /**
     * The names in [devices] that more than one row carries.
     *
     * A name is what the user picks a TV by, and it is not unique — two TVs can
     * announce the same one. Then the name alone does not say which is which,
     * and the row shows its address too, the only thing that tells them apart.
     * Every other row shows the name alone: an address is the fallback for a
     * device that has no name at all, not an answer to "which TV is this".
     *
     * **The two cases behind a shared name cannot be separated here.** A TV
     * answering from two addresses and two TVs genuinely sharing one name
     * produce the same two rows, because a row carries only a name and an
     * address. This marks them distinguishable; it does not attribute them.
     */
    fun namesNeedingAddress(devices: List<Device>): Set<String> =
        devices.groupBy { it.name }.filterValues { it.size > 1 }.keys

    /**
     * How one row of the scan list reads.
     *
     * A TV the scan did not hear from keeps its own name and gains the reason it
     * is not like the others — dropping it from the list is what this step exists
     * to stop, and showing it unmarked would read as a TV that answered. A stored
     * TV, whether or not it answered, gains its own suffix so the user can tell
     * at a glance which entries route without a PIN.
     *
     * **The order is the whole content of this function.** The address goes
     * *inside* the name, because the name is what it disambiguates, and the two
     * suffixes wrap the result: a row that is shared, silent and stored reads
     * `name (address) (not answering) (paired)`, not the address trailing after
     * the state.
     *
     * [format] stands in for `getString` so that order is decided and asserted on
     * the JVM instead of only being visible to someone reading this function —
     * the collision case cannot be produced on a device without two TVs sharing a
     * name. It is a seam for one purpose; the wording itself stays in
     * `strings.xml`.
     */
    fun scanRowLabel(
        device: Device,
        storedHosts: Set<String>,
        shared: Set<String>,
        format: (Int, List<Any>) -> String
    ): String {
        val named = if (device.name in shared) {
            format(R.string.scan_list_name_with_address, listOf(device.name, device.ip))
        } else {
            device.name
        }
        val base = if (device.answering) {
            named
        } else {
            format(R.string.device_not_answering, listOf(named))
        }
        return if (device.ip in storedHosts) {
            format(R.string.scan_list_paired_suffix, listOf(base))
        } else {
            base
        }
    }

    /**
     * The MAC to wake [host] with: the stored pairing's, else what the last scan
     * heard — which covers a TV being paired now, before it is stored.
     */
    private fun wakeupMacFor(host: String): String? =
        tokenStore.all().firstOrNull { it.host == host }?.wakeupMac
            ?: lastDiscovered.firstOrNull { it.ip == host }?.wakeupMac

    /**
     * Stored TVs [learnWakeupMac] has already searched for since this flow was
     * built — one Activity instance. Touched only on the shared worker thread.
     */
    private val macSearched = mutableSetOf<String>()

    /**
     * Give [host], a stored TV with no wake-up MAC, one SSDP search to announce
     * it in, without the user scanning. Blocking — call it off the main thread.
     *
     * A scan was the only place the MAC was learned, so a pairing made before
     * the MAC was kept — or on a phone that never scanned again — had none, and
     * a press against a deeply asleep TV sent DIAL alone, which does not wake it
     * (measured 2026-09-26, `docs/protocol.md § 3`). Call it only for a TV that
     * has just proved it is awake — a press landed on it — because a TV asleep
     * deeply enough to need the MAC answers nothing.
     *
     * One search per TV per flow, because a TV that announces no `WAKEUP` would
     * otherwise hold the shared worker for [Discovery.SSDP_WINDOW_MS] after every
     * press. A search that throws is a fault, not an answer, so it does not use
     * that search up. Other stored TVs with no MAC that answer the same search
     * are filled in too, without spending their own search.
     *
     * Only fills a missing MAC, never replaces one, and only when every answer
     * from that address names the same MAC: an answer is an unauthenticated UDP
     * datagram, so answers that disagree store nothing rather than letting the
     * last one win.
     */
    fun learnWakeupMac(host: String) {
        if (host in macSearched) return
        val missing = tokenStore.all().filter { it.wakeupMac == null }.map { it.host }.toSet()
        if (host !in missing) return
        macSearched += host
        val responses = try {
            ssdp.search(Discovery.SSDP_WINDOW_MS)
        } catch (e: RuntimeException) {
            macSearched -= host
            return
        }
        responses
            .filter { it.sourceIp in missing }
            .groupBy { it.sourceIp }
            .forEach { (ip, answers) ->
                val macs = answers.mapNotNull { it.wakeupMac }.toSet()
                macs.singleOrNull()?.let { tokenStore.rememberWakeupMac(ip, it) }
            }
    }

    /**
     * Drop one stored pairing and return to whatever this app should drive next.
     *
     * With no argument, forgets the currently-selected TV — the shape the paired
     * screen's overflow control uses. With a host, forgets that specific TV,
     * which is what a per-TV forget affordance would call. In either shape, if
     * another stored TV remains, the selection advances to it and the return
     * is [State.Paired] for that TV; otherwise the return is [State.Scanning]
     * and the next launch scans rather than short-circuiting.
     *
     * This is what makes re-pointing the app at a different TV possible without
     * clearing its data. Re-pairing alone would overwrite one stored token
     * while leaving the pairing side-effects (name, selected pointer) in place,
     * so removing the pairing outright is the honest operation.
     */
    fun forget(host: String? = null): State {
        val target = host ?: tokenStore.load()?.host ?: return State.Scanning
        tokenStore.remove(target)
        val next = tokenStore.load()
        return if (next != null) State.Paired(next) else State.Scanning
    }

    /**
     * Switch to a different stored TV without asking for a PIN.
     *
     * The overflow menu's per-TV entries route here: the token for [host] is
     * already stored, so pairing is not the ask — this is the "change which
     * TV I am driving" affordance. Returns [State.Paired] for the newly-
     * selected TV, or null when [host] is not in the store (which the caller
     * should treat as a menu that has fallen out of step with the store —
     * re-rendering from `start()` recovers).
     */
    fun switchTo(host: String): State? {
        val target = tokenStore.select(host) ?: return null
        return State.Paired(target)
    }

    /**
     * Every TV this app has stored a pairing for, in insertion order.
     *
     * A read-only view onto the store, exposed here so the screen can render
     * the paired-marker on scan-list entries and the switch entries on the
     * overflow menu without importing [TokenStore]. Empty when nothing has
     * been paired.
     */
    fun storedDevices(): List<PairedDevice> = tokenStore.all()

    /**
     * Press one control on the paired TV.
     *
     * [onDone] carries `null` on success and a message written to be read by the
     * user on failure. It never throws: a dropped packet must not take the
     * paired screen down with it, so a failed press is a line of text under the
     * remote rather than a teardown into [State.Failed].
     *
     * The return value carries an optional state transition the caller should
     * render after handling [onDone]. Non-null only when the stored token was
     * rejected by the TV (401/403): the flow has already dropped that host's
     * token and started pairing it again through [select], which asks the TV
     * to show a PIN. That yields a [State.AwaitingPin] for the host — the
     * correct affordance for an expired token on a still-paired-worthy TV —
     * or a [State.Failed] when the TV will not start pairing. Every other path
     * returns null.
     *
     * The token comes from the store, never from the caller — the screen has no
     * business holding one.
     *
     * [onWaking] carries a line to show while the press has to wake the TV —
     * which can take many seconds — and is not called when the press lands
     * first time. It says a wake is being sent, which is true, and not that
     * the TV is asleep, which this side cannot know.
     */
    fun press(
        action: String,
        keyed: Boolean,
        onWaking: (String) -> Unit = {},
        onDone: (String?) -> Unit
    ): State? {
        val paired = tokenStore.load()
        if (paired == null) {
            onDone("This phone is no longer paired with a TV.")
            return null
        }
        // Off Wi-Fi, every request would fail and the wake would spend its
        // whole 40 s budget before saying so. Nothing is sent.
        if (cidrProvider() == null) {
            onDone("Connect to the same Wi-Fi as ${paired.name}.")
            return null
        }
        val waking = { onWaking("Waking ${paired.name}…") }
        return try {
            // Routed by the control's own action string, so a control cannot be
            // silently sent to the wrong endpoint: the media actions are named
            // here and everything else is a key endpoint action. Rewind and
            // fast-forward share the `scan` action name on the wire but differ
            // in the request body, so they route by the app-side alias the
            // control descriptor carries rather than by wire-vocabulary alone.
            when (action) {
                ACTION_PLAY -> client.playPause(paired.host, paired.token, waking)
                ACTION_SCAN_BACK -> client.scanBackward(paired.host, paired.token, waking)
                ACTION_SCAN_FORWARD -> client.scanForward(paired.host, paired.token, waking)
                else -> client.pressKey(paired.host, paired.token, action, keyed, waking)
            }
            onDone(null)
            null
        } catch (e: TransportConnectionRefused) {
            // Both of these arrive only after the wake has already been tried —
            // see `FireTvClient.withWakeRecovery`. So the honest report is that
            // waking it did not work, not a guess at why — unless a VPN is on,
            // which produces this same failure with the TV awake and fine.
            onDone(unreachable(paired.name) ?: "${paired.name} isn't listening, and it did not come up after a wake.")
            null
        } catch (e: TransportStatus) {
            if (e.status == 401 || e.status == 403) {
                // The TV answered but refused the stored token — the pairing
                // this app has for this host is no longer accepted. Drop that
                // host's token only (the other stored TVs are untouched) and
                // start pairing this host again without losing the rest.
                // `select` is what asks the TV to show a PIN — a PIN screen
                // with nothing on the TV to read is a dead end. The MAC rides
                // along so the re-pair stores it again: the scan that heard it
                // may never come back for a TV that sleeps deeply.
                tokenStore.remove(paired.host)
                onDone("${paired.name} rejected the stored pairing. Pair it again to keep using it.")
                select(Device(name = paired.name, ip = paired.host, wakeupMac = paired.wakeupMac))
            } else {
                // The status code is diagnostic, not an explanation — a bare
                // status in front of a non-technical user is this class's own
                // rule broken (see the sibling messages: they say what happened
                // and what it means). It stays in the exception, where a log
                // or a debugger reads it.
                onDone("The TV refused that press. It is on and answering, but it will not take commands right now.")
                null
            }
        } catch (e: IOException) {
            onDone(unreachable(paired.name) ?: "${paired.name} didn't answer, even after a wake.")
            null
        }
    }

    /**
     * The message for a press that reached nothing while a VPN is on, or null
     * with no VPN. Only for failures where nothing answered: a TV that answered
     * with a refusal was reached, so the VPN is not the cause there.
     */
    private fun unreachable(name: String): String? =
        if (vpnOn()) {
            "Couldn't reach $name. A VPN is on, and it can keep this phone off your home network — " +
                "turn it off, or allow local network access in the VPN app, then try again."
        } else {
            null
        }

    /**
     * Ask the paired TV what it can do, or null when there is nothing to ask.
     *
     * The device's own answer decides whether volume, mute and power are drawn
     * at all (`docs/protocol.md § 2`, "Volume, mute and power"). Null means the
     * question could not be put — no pairing stored — and never "it can do
     * nothing": [FireTvClient.capabilities] answers optimistically for anything
     * it could not read, so an unanswered question draws the controls rather than
     * hiding them.
     */
    fun capabilities(): Capabilities? {
        val paired = tokenStore.load() ?: return null
        return client.capabilities(paired.host, paired.token)
    }

    /**
     * Pick a TV by an address the user typed, rather than one a scan found.
     *
     * This is the whole of the manual route. A well-formed address becomes the
     * same [Device] the scanner would have produced, so [select] and [pair] run
     * against it untouched and the two ways in converge on one pairing machine
     * instead of forking it.
     *
     * The address is checked here, before anything reaches the network — which
     * is why the check lives in this class and not on the screen. A mistyped
     * address costs nothing: no probe is issued, no PIN request is sent, and
     * nothing on the TV changes. A screen-side check could not promise that,
     * because by the time the screen saw it the flow would already have been
     * called.
     *
     * The device is named after its own address, since that is the only name
     * this route has — the TV's real name lives in an mDNS record nothing here
     * queries, which is Step 4's work. [Device.name] is interpolated into the
     * failure prose below, where the bare address reads far better than an
     * empty string.
     */
    fun selectByAddress(text: String): State {
        val attempted = text.trim()
        return when (val parsed = Ipv4.parse(text)) {
            is AddressParse.Rejected -> State.Failed(
                malformedAddressMessage(attempted, parsed.defect),
                State.Cause.MalformedAddress,
                attemptedHost = attempted,
            )
            is AddressParse.Valid ->
                // Wrap select's return so a downstream failure (unreachable,
                // pairing refused) still carries the user's typed text back
                // to the retry screen. select() serves both routes — scanned
                // and typed — and cannot know which one called it, so the
                // typed-route wrapping happens here.
                select(Device(name = parsed.normalized, ip = parsed.normalized))
                    .withAttemptedHost(attempted)
        }
    }

    /**
     * Attach the raw typed text to a [State.Failed] returned from [select].
     *
     * Applies to [State.Failed] only; other states pass through unchanged. The
     * typed text is the retry screen's input on the address-entry route, so
     * the state that would otherwise drop it needs it carried across the
     * transition.
     */
    private fun State.withAttemptedHost(host: String): State =
        if (this is State.Failed) copy(attemptedHost = host) else this

    /**
     * Say which way a typed address was wrong, and quote it back.
     *
     * One cause covers every class here. Giving each its own [State.Cause] would
     * be modelling nobody asked for — no screen reacts differently to "has
     * letters in it" than to "that number is too big" — so the clarity has to
     * come from the sentence. Each class therefore gets its own wording, and
     * every one of them quotes the offending text back.
     *
     * That quoting is the difference between a report someone can act on and
     * one they cannot: `"192.0. 4.22" has a space inside it` names the fault,
     * where "invalid address" leaves them comparing two near-identical strings
     * by eye. An address is not a secret — only the pairing token is, and that
     * never comes near this path (`docs/protocol.md § 4`).
     */
    private fun malformedAddressMessage(shown: String, defect: AddressDefect): String {
        val fourNumbers = "An address is four numbers separated by dots, like 192.0.2.22."
        val everyPartIsAnOctet = "Every part must be a number from 0 to 255."
        return when (defect) {
            AddressDefect.Empty ->
                "Type the TV's IP address first. $fourNumbers"

            AddressDefect.EmbeddedWhitespace ->
                "\"$shown\" has a space inside it. $fourNumbers"

            AddressDefect.WrongOctetCount ->
                "\"$shown\" isn't four numbers. $fourNumbers"

            AddressDefect.NonNumericOctet ->
                "\"$shown\" has something in it that isn't a number. $everyPartIsAnOctet"

            AddressDefect.EmptyOctet ->
                "\"$shown\" has two dots in a row, so a part is missing. $fourNumbers"

            AddressDefect.OctetOutOfRange ->
                "\"$shown\" has a number outside 0 to 255. $everyPartIsAnOctet"

            AddressDefect.LeadingZeroOctet ->
                "\"$shown\" has a part starting with 0. Write it without the leading zero — 192.0.2.22, not 192.0.2.022 — or your phone may read it as a different address than the one you typed."

            AddressDefect.PortOrCidrSuffix ->
                "\"$shown\" has a port or a /prefix on the end. Type just the address, like 192.0.2.22."
        }
    }

    /**
     * Pick a TV. Asks it to put a PIN on screen — the user reads it off the TV,
     * so nothing can proceed until that call lands.
     */
    fun select(device: Device): State {
        // A device the scan did not hear from is one this app has already paired
        // with — that is the only way it knows the name at all. Asking for a PIN
        // again would be the exact regression this path exists to prevent, so
        // the stored token is used and the remote opens directly. The wake path
        // runs on the first press, which is what `withWakeRecovery` is for.
        //
        // The lookup goes over every stored TV, not only the currently-selected
        // one, because a paired-but-not-selected TV is exactly the case this
        // arm exists to serve — the user is picking one from the scan list to
        // return to, and the selection follows the pick.
        if (!device.answering) {
            val stored = tokenStore.all().firstOrNull { it.host == device.ip }
            if (stored != null) {
                tokenStore.select(stored.host)
                return State.Paired(stored)
            }
        }
        return try {
            client.requestPin(device.ip, CLIENT_NAME)
            State.AwaitingPin(device)
        } catch (e: TransportStatus) {
            State.Failed(
                "${device.name} answered, but would not start pairing. Check the TV's screen, then try again.",
                State.Cause.PairingRefused
            )
        } catch (e: TransportConnectionRefused) {
            // Both of these arrive only after the wake has already been tried —
            // `FireTvClient.requestPin` wraps its call in `withWakeRecovery`, the
            // same as the press path. So neither message may read as an address
            // problem: for a TV that just answered the discovery search, the
            // address was demonstrably right, and pointing at it would send the
            // user to check something that is not wrong.
            State.Failed(
                "Nothing is listening at ${device.ip}, and it did not come up after a wake.",
                State.Cause.TvNotReachable
            )
        } catch (e: IOException) {
            State.Failed(
                "${device.name} didn't answer, even after a wake.",
                State.Cause.TvNotReachable
            )
        }
    }

    /**
     * Send the PIN the user read off the TV. On success the token is persisted
     * through [TokenStore] before the paired state is returned, so a relaunch
     * finds it.
     *
     * An answer this client will not call a token does **not** get stored — see
     * [FireTvClient.verifyPin]. Nothing reaches the user in an exception's own
     * words; the messages here are written to be read, and the cause travels
     * alongside them as a value rather than as prose to be re-parsed.
     *
     * The token is never logged — see `docs/protocol.md § 4`.
     */
    fun pair(device: Device, pin: String): State {
        // Guarded here as well as on the screen. The screen disables its own
        // submit control, but a check that lives only there is one programmatic
        // caller away from being bypassed — and a blank PIN that reaches the TV
        // consumes one of its pairing attempts, which is the user's to spend on
        // a real try. Nothing is sent on this path.
        if (pin.isBlank()) {
            return State.Failed(
                "Type the PIN shown on the TV first. An empty one would use up a pairing attempt.",
                State.Cause.EmptyPin
            )
        }
        return try {
            when (val outcome = client.verifyPin(device.ip, pin)) {
                is PinOutcome.Paired -> {
                    val paired = PairedDevice(
                        host = device.ip,
                        name = device.name,
                        token = outcome.token,
                        // Re-pairing a stored TV keeps the MAC the store already
                        // has: the found device's MAC came from an SSDP answer,
                        // which anyone on the LAN can forge, so it only fills a
                        // missing one — the rule the scan fill follows. A typed
                        // address never heard SSDP and brings no MAC at all.
                        wakeupMac = tokenStore.all().firstOrNull { it.host == device.ip }?.wakeupMac
                            ?: device.wakeupMac
                    )
                    tokenStore.save(paired)
                    State.Paired(paired)
                }
                is PinOutcome.Unrecognized -> State.Failed(
                    "${device.name} answered, but not with a pairing token. Check the PIN shown on the TV and try again.",
                    State.Cause.PairingRefused
                )
            }
        } catch (e: TransportStatus) {
            State.Failed(
                "${device.name} answered, but would not complete pairing. Check the PIN shown on the TV, then try again.",
                State.Cause.PairingRefused
            )
        } catch (e: IOException) {
            State.Failed(
                "Pairing with ${device.name} didn't finish. Check that the TV is on and try again.",
                State.Cause.PairingIncomplete
            )
        }
    }

    /**
     * Step back from the PIN screen to the list the user picked from.
     *
     * Returns the remembered list rather than scanning again. Re-scanning here
     * was the defect: Back is how someone changes their mind about which TV to
     * pair with, and answering it with a fresh scan throws away the result they
     * were looking at and makes them wait for the same answer twice. If the
     * list is genuinely stale, the Scan button is one press away on the screen
     * this returns.
     */
    fun goBackFromPin(): State = State.Discovered(lastDiscovered)

    /**
     * Return to the last scan-result screen from a [State.Failed] on the typed-
     * address route. The typed text is carried on [State.Failed.attemptedHost];
     * this method's only concern is which screen to land on. The scan-result
     * shape is the one that shows the address entry, so a corrected retry can
     * proceed without a re-scan — even when `lastDiscovered` is empty, the
     * `Discovered` state's own screen mounts the address entry, which is what
     * the user is coming back to use.
     */
    fun goBackFromFailure(): State = State.Discovered(lastDiscovered)

    /**
     * Where Back goes from [from], or null when it should leave the app.
     *
     * The remote and the start screen are where the app begins, so Back there
     * has nowhere in the app to go and leaves — which is what Back does in
     * every other app, and what a stranger expects. The PIN screen and a
     * typed-address failure step back to the list they came from; anything
     * else returns to the start. Kept here, beside the two steps it uses, so
     * the rule is one answer the Activity asks rather than a branch it owns.
     */
    fun back(from: State): State? = when {
        // FirstRun belongs with Scanning: it is the app's own opening screen, so
        // Back leaves the app. Without this it falls to `start()`, which returns
        // FirstRun again, and Back becomes a no-op that never exits.
        from is State.FirstRun || from is State.Paired || from is State.Scanning -> null
        from is State.AwaitingPin -> goBackFromPin()
        from is State.Failed && from.attemptedHost != null -> goBackFromFailure()
        else -> start()
    }

    companion object {
        /** The name this app shows on the TV's pairing screen. */
        const val CLIENT_NAME = "Firehose Remote"

        /**
         * The controls that do not ride `POST /v1/FireTV?action=`.
         *
         * [ACTION_PLAY] is the action string the vendor app was captured sending
         * for play/pause, so the routing in [press] matches on the wire
         * vocabulary rather than on a private alias. The two shuttle controls
         * share the wire-side `scan` action but differ in body direction — one
         * captured (`back`), one inferred (`forward`) — so the app-side alias
         * names each half explicitly and [press] routes on the alias. Routing
         * on wire-vocabulary alone would collapse the pair and lose the
         * direction the caller intended.
         */
        const val ACTION_PLAY = "play"
        const val ACTION_SCAN_BACK = "scan_back"
        const val ACTION_SCAN_FORWARD = "scan_forward"
    }
}
