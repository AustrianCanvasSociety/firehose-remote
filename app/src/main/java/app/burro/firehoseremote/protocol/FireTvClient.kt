package app.burro.firehoseremote.protocol

import java.io.IOException
import java.io.InterruptedIOException

/**
 * What `pin/verify` said, once its body has been read.
 *
 * A closed pair rather than a nullable string, because the two answers lead to
 * genuinely different places: a token is stored and the app is paired, an
 * unrecognized description is shown to the user and nothing is written down.
 */
sealed class PinOutcome {
    /** The TV minted a durable client token. Safe to persist. */
    data class Paired(val token: String) : PinOutcome()

    /**
     * The TV answered, and the answer is not shaped like a credential. It is
     * shown to the user and never persisted — see [FireTvClient.looksLikeToken]
     * for why this client will not guess further than that.
     */
    data class Unrecognized(val description: String) : PinOutcome()
}

/**
 * What the device says it can do, read from `GET /v1/FireTV`.
 *
 * `true` means "draw it", which includes every case this client could not read —
 * see [FireTvClient.capabilities] for why an unreadable field is not a "no".
 * There is no `mute` member because the device's answer has no separate mute
 * flag; mute rides on [volume].
 */
data class Capabilities(val volume: Boolean, val power: Boolean)

/**
 * The result of looking for a top-level `description` in a response body.
 *
 * [Missing] and [Malformed] are kept apart from a readable-but-empty value so
 * each can name itself in the error text. Collapsing them is what produced the
 * false "TV returned OK for 3 attempts" message.
 */
private sealed class Description {
    data class Found(val value: String) : Description()
    object Missing : Description()
    object NotAString : Description()
    object Unterminated : Description()

    /**
     * More than one top-level `description`. JSON leaves duplicate names
     * unspecified, and a credential is not something to guess at, so the body
     * is refused rather than read either first or last.
     */
    object Duplicate : Description()

    /**
     * A `\u` escape this client cannot decode.
     *
     * Degrading to the escape's own letters — `\u004` becoming the text `u004` —
     * produced a string that passed the token shape check and was **persisted as
     * the pairing credential**, a token the TV never issued. Refusing the body is
     * the only honest answer: a body this client cannot read is not one it can
     * pair from.
     */
    object MalformedEscape : Description()
}

/**
 * Fire TV command client — talks to a single TV given its IP.
 *
 * All timing constants and retry shapes are load-bearing per docs/protocol.md.
 * The measurements were earned by things
 * that failed first; do not "clean them up."
 */
class FireTvClient(
    private val transport: Transport,
    private val clock: Clock = SystemClock,
    private val apiKey: String = API_KEY,
    private val wakeOnLan: WakeOnLan = WakeOnLan.None,
    /** The MAC a host announced in SSDP `WAKEUP`, or null — read when a wake starts. */
    private val wakeupMacFor: (host: String) -> String? = { null }
) {

    /**
     * Ask the TV to display a pairing PIN. Caller renders instructions to the
     * user; the PIN is user-observed and read back into [verifyPin].
     *
     * Wrapped in [withWakeRecovery], because the TV this meets is very often an
     * idle one. Discovery lists a Fire TV that answers SSDP while its control
     * API has stopped listening — that is what an idle device does — so tapping
     * a TV in the picker is the likeliest way to meet a refused port anywhere in
     * this app. Without the wrap the flow reported "nothing is listening"
     * against a TV that had been on the whole time.
     */
    fun requestPin(host: String, friendlyName: String) {
        withWakeRecovery(host) { postPinDisplay(host, friendlyName) }
    }

    /**
     * The single POST, split out so [withWakeRecovery] has something it can run
     * a second time. A rejected status stays uncaught by the wrapper's callers:
     * the TV answered, so it is up and the failure is already final.
     */
    private fun postPinDisplay(host: String, friendlyName: String) {
        val body = """{"friendlyName":"${jsonEscape(friendlyName)}"}"""
        val res = transport.request(
            method = "POST",
            url = "https://$host:$PORT_COMMAND/v1/FireTV/pin/display",
            body = body,
            headers = pairingHeaders()
        )
        if (!res.ok) {
            throw TransportStatus(res.status, "firehose-remote: pin/display: TV returned status ${res.status}")
        }
    }

    /**
     * Send the PIN and receive the durable client token.
     *
     * The TV emits `{"description":"OK"}` when it has accepted the PIN but not
     * yet minted the token. That means "not yet" — retry up to 3 attempts, 1 s
     * apart. **Only the exact string `OK` retries**; `OKAY` is a valid token.
     *
     * Anything else is judged by [looksLikeToken]. A credential comes back as
     * [PinOutcome.Paired]. A description that is not shaped like one comes back
     * as [PinOutcome.Unrecognized] and is **never persisted** — see that
     * function for why this is a judgement rather than a protocol rule.
     *
     * A body this client cannot read at all does not get to masquerade as the
     * retry sentinel: each malformed shape is named in the exception, because
     * "the TV returned OK three times" is a confident false statement about a
     * reply that never said OK. The closing message counts what actually came
     * back across the attempts: a run that saw the sentinel names its real
     * count, and only a run that never saw it may call the replies unreadable.
     */
    fun verifyPin(host: String, pin: String): PinOutcome {
        val body = """{"pin":"${jsonEscape(pin)}"}"""
        val headers = pairingHeaders()
        val url = "https://$host:$PORT_COMMAND/v1/FireTV/pin/verify"

        var okCount = 0
        var unreadable: String? = null
        repeat(PIN_RETRY_ATTEMPTS) { attempt ->
            // Per attempt, not around the loop. Wrapping the loop would let a
            // wake restart the three-attempt budget, and each attempt spends one
            // of the TV's own pairing attempts — so a wake would cost the user
            // attempts it was never granted. Here a wake retries the request
            // that failed and leaves the budget exactly where it was.
            //
            // This path needs it as much as `requestPin` does: the user reads a
            // PIN off the television, which is long enough for an idle TV to let
            // its control API go quiet.
            val res = withWakeRecovery(host) { transport.request("POST", url, body, headers) }
            if (!res.ok) {
                throw TransportStatus(res.status, "firehose-remote: pin/verify: TV returned status ${res.status}")
            }
            when (val read = readDescription(res.body)) {
                is Description.Found -> when {
                    read.value == OK_SENTINEL -> okCount++ // "not yet" — fall through and retry
                    read.value.isEmpty() -> unreadable = "an empty description"
                    looksLikeToken(read.value) -> return PinOutcome.Paired(read.value)
                    else -> return PinOutcome.Unrecognized(read.value)
                }
                Description.Missing -> unreadable = "no top-level \"description\" field"
                Description.NotAString -> unreadable = "a \"description\" that is not a string"
                Description.Unterminated -> unreadable = "a \"description\" string that never ends"
                Description.Duplicate -> unreadable = "more than one top-level description field"
                Description.MalformedEscape -> unreadable = "a \"description\" carrying an escape that cannot be decoded"
            }
            if (attempt < PIN_RETRY_ATTEMPTS - 1) clock.sleep(PIN_RETRY_INTERVAL_MS)
        }
        throw IOException(
            if (okCount == 0) {
                "firehose-remote: pin/verify: $PIN_RETRY_ATTEMPTS attempts, none carrying a readable description — the TV's replies carried $unreadable"
            } else {
                "firehose-remote: pin/verify: TV returned \"$OK_SENTINEL\" on $okCount of $PIN_RETRY_ATTEMPTS attempts — pairing did not complete"
            }
        )
    }

    /**
     * Whether [description] is shaped like a credential rather than a message.
     *
     * **This is a judgement, not a protocol rule.** `docs/protocol.md § 1`
     * documents exactly two `description` values — the sentinel `"OK"`, and the
     * token itself (e.g. `zBMBFhY`). It documents **no failure shape**, and `§ 6`
     * does not list one among the known-unknowns, so nothing in the spec
     * separates a refused PIN from a minted token by content alone. The question
     * is open; the probe that would settle it needs a TV whose
     * control API answers.
     *
     * Until then this refuses to persist anything that does not look like an
     * opaque credential: non-empty, within [MAX_TOKEN_LENGTH], no whitespace and
     * no control characters. Every documented example survives it — `zBMBFhY`
     * passes, `OKAY` passes, and a human-readable refusal such as
     * `"Invalid PIN"` does not. A description that fails the check becomes
     * [PinOutcome.Unrecognized]: the app says it does not understand the answer
     * rather than claiming a pairing it cannot support.
     */
    private fun looksLikeToken(description: String): Boolean =
        description.isNotEmpty() &&
            description.length <= MAX_TOKEN_LENGTH &&
            description.none { it.isWhitespace() || isControl(it) }

    /**
     * Percent-encode a value on its way into a query string.
     *
     * `action` arrives here through [PairingFlow.press], which is a public seam,
     * so it is not necessarily one of the literals [RemoteScreen]'s controls
     * pass. An `&` or `#` in it would add to the query instead of naming the
     * value, and a space would end the URL at the parser. No caller does that
     * today — this is a latent fix, not a live one.
     *
     * Written out rather than delegated to `java.net.URLEncoder`, because
     * `protocol/` may not import `java.net.*` — the rule that keeps this layer
     * testable on the JVM without a device.
     */
    private fun encodeQueryValue(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(value.length)
        for (c in value) {
            if (c in unreserved) {
                sb.append(c)
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    val i = b.toInt() and 0xFF
                    sb.append('%').append(hex[i shr 4]).append(hex[i and 0xF])
                }
            }
        }
        return sb.toString()
    }

    /**
     * Send a discrete key press to the TV. A directional press is TWO requests
     * — `keyDown` starts the key-repeat loop, `keyUp` stops it — separated by
     * exactly [KEY_PRESS_GAP_MS] measured **after the keyDown response**.
     *
     * If the TV is asleep, `keyDown` fails — by timing out, or by a refused
     * port, which is what an idle Fire TV does and is **not** evidence of a
     * wrong address. The wake path fires on both, and only around `keyDown` —
     * `keyUp` runs against an already-awake TV. [onWaking] runs once, as the
     * wake starts, and never when `keyDown` lands first time.
     */
    fun sendKey(host: String, token: String, action: String, onWaking: () -> Unit = {}) {
        val url = "https://$host:$PORT_COMMAND/v1/FireTV?action=${encodeQueryValue(action)}"
        val headers = commandHeaders(token)

        val keyDownBody = """{"keyActionType":"keyDown"}"""
        withWakeRecovery(host, onWaking) {
            val res = try {
                transport.request("POST", url, keyDownBody, headers)
            } catch (e: InterruptedIOException) {
                // Stopped while keyDown was on the wire: the platform HTTP stack
                // fails the request on the interrupt (observed on a Redmi Note 11,
                // 119 ms in). Whether the TV took the keyDown is unknown, so the
                // release goes out anyway — a keyUp for a key that is not down is
                // a no-op. The flag is cleared first so the release is not failed
                // by the same interrupt. Not a timeout, so no wake either.
                Thread.interrupted()
                val stopped = InterruptedException("firehose-remote: sendKey($action): interrupted during keyDown")
                stopped.initCause(e)
                throw releaseBeforeStopping(url, headers, action, stopped)
            }
            if (!res.ok) {
                throw TransportStatus(res.status, "firehose-remote: sendKey($action): keyDown status ${res.status}")
            }
        }
        // The gap is measured from the keyDown *response*, which is why this sits
        // after the call rather than before it: on a sleeping TV keyDown can take
        // ~4 s, and a gap measured from the send would already have elapsed by the
        // time it returned. Nothing happens between that response and this
        // statement, so the sleep is the whole gap.
        //
        // From here the TV holds the key, and it repeats a held key until keyUp
        // arrives — 54 s observed, with no timeout of its own. So a worker
        // stopped mid-gap (`shutdownNow()` when the screen is torn down) still
        // sends keyUp before the interrupt goes on.
        try {
            clock.sleep(KEY_PRESS_GAP_MS)
        } catch (e: InterruptedException) {
            throw releaseBeforeStopping(url, headers, action, e)
        }
        releaseKey(url, headers, action)
    }

    /**
     * Send the keyUp a stopped press owes, and hand back [stopped] for the
     * caller to throw. A release that fails rides along as suppressed: the
     * caller is going away, and what it needs to hear is "stopped".
     */
    private fun releaseBeforeStopping(
        url: String,
        headers: Map<String, String>,
        action: String,
        stopped: InterruptedException
    ): InterruptedException {
        try {
            releaseKey(url, headers, action)
        } catch (releaseFailed: IOException) {
            stopped.addSuppressed(releaseFailed)
        }
        return stopped
    }

    /**
     * `keyUp` is what stops the TV's key-repeat loop, so losing it is not a
     * missing press — it is a TV that keeps moving. That buys it one retry.
     * A duplicate is harmless: stopping an already-stopped loop is a no-op.
     * Only a timeout is retried; a status code is a real answer from the TV.
     */
    private fun releaseKey(url: String, headers: Map<String, String>, action: String) {
        val keyUpRes = try {
            transport.request("POST", url, """{"keyActionType":"keyUp"}""", headers)
        } catch (e: TransportTimeout) {
            transport.request("POST", url, """{"keyActionType":"keyUp"}""", headers)
        }
        if (!keyUpRes.ok) {
            throw TransportStatus(keyUpRes.status, "firehose-remote: sendKey($action): keyUp status ${keyUpRes.status}")
        }
    }

    /**
     * Press a control on the paired TV.
     *
     * Two shapes, matching what the official app sends. A **keyed** control gets
     * [sendKey]'s two requests and the [KEY_PRESS_GAP_MS] gap; everything else is
     * one body-less POST. The gap exists to give a key-repeat loop a duration,
     * and a discrete press has no loop to stop.
     *
     * `keyed` is the caller's to state rather than this class's to infer. It used
     * to be a set held here and matched against `action` — a second place a
     * control was written down, and one whose drift failed *silently*: a keyed
     * action sent body-less produces a request that looks fine on the wire and a
     * TV that does nothing, which reads as a device limitation. The control list
     * in `ui/RemoteScreen` is the single source now and passes the flag in.
     */
    fun pressKey(host: String, token: String, action: String, keyed: Boolean, onWaking: () -> Unit = {}) {
        if (keyed) {
            sendKey(host, token, action, onWaking)
            return
        }
        val url = "https://$host:$PORT_COMMAND/v1/FireTV?action=${encodeQueryValue(action)}"
        postCommand(host, url, commandHeaders(token), "pressKey($action)", onWaking)
    }

    /**
     * Toggle playback.
     *
     * One request, not two. The vendor app sends this identical body-less call
     * for both play and pause — the device infers the direction from its own
     * state — so there is no separate pause action to send.
     */
    fun playPause(host: String, token: String, onWaking: () -> Unit = {}) {
        val url = "https://$host:$PORT_COMMAND/v1/media?action=play"
        postCommand(host, url, commandHeaders(token), "playPause", onWaking)
    }

    /**
     * POST to [url] with no body, and translate a non-2xx into a [TransportStatus]
     * naming [label] in its message. Shared by [pressKey]'s discrete branch and
     * [playPause].
     */
    private fun postCommand(
        host: String,
        url: String,
        headers: Map<String, String>,
        label: String,
        onWaking: () -> Unit
    ) {
        withWakeRecovery(host, onWaking) {
            val res = transport.request("POST", url, null, headers)
            if (!res.ok) {
                throw TransportStatus(res.status, "firehose-remote: $label: status ${res.status}")
            }
        }
    }

    /**
     * Shuttle the current media backward — the rewind control.
     *
     * A different endpoint from every other control, and not body-less: the
     * vendor app sent `POST /v1/media?action=scan` with [SCAN_BACK_BODY], twice,
     * 20 s apart, both times `direction: "back"` (`docs/protocol.md § 2`, Media).
     */
    fun scanBackward(host: String, token: String, onWaking: () -> Unit = {}) {
        val url = "https://$host:$PORT_COMMAND/v1/media?action=scan"
        val headers = commandHeaders(token)
        withWakeRecovery(host, onWaking) {
            val res = transport.request("POST", url, SCAN_BACK_BODY, headers)
            if (!res.ok) {
                throw TransportStatus(res.status, "firehose-remote: scanBackward: status ${res.status}")
            }
        }
    }

    /**
     * Shuttle the current media forward — the fast-forward control.
     *
     * Same endpoint and body shape as [scanBackward] with `direction: "forward"`
     * ([SCAN_FORWARD_BODY]). The forward direction was never captured from the
     * vendor app — the field's other value is inferred rather than observed. It
     * ships as a matched pair with rewind because shipping only one half makes
     * an overshoot uncorrectable, which is the UX trap that surfaced at Step 3's
     * hand-test: the rewind button jumped ~10 minutes (not the 10 s the field
     * name implies) with no way forward. Live verification of the forward press
     * against real hardware closes the inference at `docs/protocol.md § 2`,
     * Media.
     */
    fun scanForward(host: String, token: String, onWaking: () -> Unit = {}) {
        val url = "https://$host:$PORT_COMMAND/v1/media?action=scan"
        val headers = commandHeaders(token)
        withWakeRecovery(host, onWaking) {
            val res = transport.request("POST", url, SCAN_FORWARD_BODY, headers)
            if (!res.ok) {
                throw TransportStatus(res.status, "firehose-remote: scanForward: status ${res.status}")
            }
        }
    }

    /**
     * Ask the device what it can do — the read that gates volume, mute and power
     * (`docs/protocol.md § 2`, "Volume, mute and power", and the control-coverage
     * table).
     *
     * **A field this cannot read is not a "no".** Lock 1's rule is that only an
     * explicit `false` / `NotSupported` suppresses a control, so anything absent
     * or unparseable answers `true` and the control is drawn. A body that says
     * nothing readable therefore draws everything, which is the same outcome as
     * a failed read — a network hiccup must not be mistaken for a capability
     * verdict, and this device's own advertisement is the only thing entitled to
     * hide a button.
     *
     * There is no separate `mute` flag in this object, so mute rides on the
     * volume answer.
     */
    fun capabilities(host: String, token: String): Capabilities {
        val url = "https://$host:$PORT_COMMAND/v1/FireTV"
        val headers = commandHeaders(token)
        val body = try {
            val res = transport.request("GET", url, null, headers)
            if (res.ok) res.body else null
        } catch (e: IOException) {
            null
        }
        val volume = body
            ?.let { topLevelLiteral(it, "isVolumeControlsSupported") }
            ?.toBooleanStrictOrNull()
            ?: true
        // The level fields are the verbose half of the same answer, and they are
        // what a device that omits the boolean still reports.
        val power = body
            ?.let { topLevelLiteral(it, "powerCapabilityLevelOfSupport") }
            ?.let { it != NOT_SUPPORTED }
            ?: true
        return Capabilities(volume = volume, power = power)
    }

    /**
     * One top-level field's literal out of a JSON object body, or null if it is
     * not there.
     *
     * Kept separate from [readDescription] on purpose. That one exists to protect
     * a *credential* and distinguishes four ways a token can fail; this one backs
     * a question whose worst wrong answer draws a button that does nothing, so it
     * collapses every unreadable case to null and lets the caller fall back to
     * drawing the control. Folding them together would put this function's laxity
     * on the token path.
     */
    private fun topLevelLiteral(json: String, field: String): String? {
        val needle = "\"$field\""
        var i = 0
        var depth = 0
        var expectKey = false
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    val end = endOfString(json, i) ?: return null
                    val isKey = depth == 1 && expectKey
                    // The next string at this level is this key's value, not a
                    // key — `readDescription` below keeps the same rule.
                    if (isKey) expectKey = false
                    if (isKey && json.substring(i, end + 1) == needle) {
                        var j = end + 1
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j >= json.length || json[j] != ':') return null
                        j++
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j >= json.length) return null
                        return if (json[j] == '"') {
                            val valueEnd = endOfString(json, j) ?: return null
                            unescape(json.substring(j + 1, valueEnd))
                        } else {
                            val literalEnd = json.indexOfFirst(j) { it == ',' || it == '}' }
                            if (literalEnd < 0) null else json.substring(j, literalEnd).trim()
                        }
                    }
                    i = end + 1
                }
                '{' -> { depth++; if (depth == 1) expectKey = true; i++ }
                '[' -> { depth++; i++ }
                '}', ']' -> { depth--; if (depth == 1) expectKey = false; i++ }
                ',' -> { if (depth == 1) expectKey = true; i++ }
                else -> i++
            }
        }
        return null
    }

    /** Index of the first character at or after [from] satisfying [match], or -1. */
    private fun String.indexOfFirst(from: Int, match: (Char) -> Boolean): Int {
        for (i in from until length) if (match(this[i])) return i
        return -1
    }

    /**
     * Run [attempt], and if the TV's command API is not listening, wake the TV
     * and run it again.
     *
     * Both failure shapes land here and mean the same thing from this side: the
     * API is not there. A refusal says the port is closed, which is what an
     * **idle** TV does — the common case, not the exceptional one. A timeout
     * says the port is open and nothing answered. The vendor app draws no
     * distinction: its captured traffic (2026-09-17) fires the DIAL wake on a
     * failed command whatever the failure was, then polls the status endpoint
     * until the API answers.
     *
     * [TransportStatus] is deliberately not caught. The TV answered, so it is
     * up; waking it would spend seconds on a failure that is already final.
     *
     * [onWaking] runs as the wake starts, so a caller can say so while it lasts.
     */
    private fun <T> withWakeRecovery(host: String, onWaking: () -> Unit = {}, attempt: () -> T): T = try {
        attempt()
    } catch (e: TransportTimeout) {
        wakeAndRetry(host, onWaking, attempt)
    } catch (e: TransportConnectionRefused) {
        wakeAndRetry(host, onWaking, attempt)
    }

    private fun <T> wakeAndRetry(host: String, onWaking: () -> Unit, attempt: () -> T): T {
        onWaking()
        wake(host)
        awaitCommandApi(host)
        stopIfInterrupted("wake")
        return attempt()
    }

    /**
     * A wake can be left: Cancel interrupts the worker, and so does the screen
     * going away. An interrupt that lands between requests throws nothing by
     * itself, so the wake checks before each thing it sends — no magic packet,
     * no wake request, no poll and no retried press after the user has left.
     */
    private fun stopIfInterrupted(what: String) {
        if (Thread.interrupted()) throw InterruptedException("firehose-remote: $what: interrupted")
    }

    /**
     * An interrupt that lands mid-request fails it with a bare
     * [InterruptedIOException] (observed 2026-09-28). A timeout never arrives
     * that way here — the transport reports it as [TransportTimeout] — so this
     * is always a stop, never a TV that failed to answer.
     */
    private fun stopped(what: String, cause: InterruptedIOException): InterruptedException =
        InterruptedException("firehose-remote: $what: interrupted mid-request").apply { initCause(cause) }

    /**
     * Wait for the command API to start listening after a wake.
     *
     * The wake returns before the API is up. Any answer counts as up —
     * including a rejected one, because a `4xx` means the port is open and the
     * process is serving, which is all this needs to know. Only a refusal or
     * silence means wait and look again.
     */
    private fun awaitCommandApi(host: String) {
        val deadline = clock.nowMillis() + WAKE_SETTLE_MS
        while (clock.nowMillis() < deadline) {
            stopIfInterrupted("wake poll")
            try {
                transport.request(
                    method = "GET",
                    url = "https://$host:$PORT_COMMAND/v1/FireTV/status",
                    body = null,
                    headers = emptyMap()
                )
                return
            } catch (e: TransportTimeout) {
                clock.sleep(WAKE_POLL_INTERVAL_MS)
            } catch (e: TransportConnectionRefused) {
                clock.sleep(WAKE_POLL_INTERVAL_MS)
            } catch (e: InterruptedIOException) {
                // Not an answer: without this, the catch below would read the
                // interrupt as "the API is up" and send the press.
                throw stopped("wake poll", e)
            } catch (e: IOException) {
                return
            }
        }
    }

    /**
     * Fire the DIAL wake call on the TV. Plain HTTP on port 8009 — required
     * because a sleeping TV accepts TCP + TLS on the command port but never
     * replies.
     *
     * Reached through [withWakeRecovery] from every call that talks to the
     * command API — `requestPin`, `verifyPin`, `sendKey`, `pressKey` and
     * `playPause` — on a timeout **or** a refused connection. A refusal is an
     * idle device, not a wrong address (`docs/protocol.md § 3`).
     *
     * The request alone does not wake a **deeply** asleep TV: that takes a
     * Wake-on-LAN magic packet ([WakeOnLan]) for the MAC the TV announced in
     * SSDP `WAKEUP` (`docs/protocol.md § 3`). So each attempt sends the packet
     * as it starts, when the MAC is known, and the request then tells us
     * whether the TV is up. A TV that announced no MAC gets the request alone.
     *
     * Attempts of [WAKE_ATTEMPT_TIMEOUT_MS] each start [WAKE_ATTEMPT_INTERVAL_MS]
     * apart until one is answered or [WAKE_BUDGET_MS] is spent — the vendor
     * app's shape. The retries matter because a stick takes several seconds to
     * come up after the packet. Only a timeout or a refusal is retried; any
     * other answer from the TV is final.
     */
    private fun wake(host: String) {
        val mac = wakeupMacFor(host)
        val firstAt = clock.nowMillis()
        val attempts = (WAKE_BUDGET_MS / WAKE_ATTEMPT_INTERVAL_MS).toInt()
        var last: IOException? = null
        for (i in 0 until attempts) {
            val due = firstAt + i * WAKE_ATTEMPT_INTERVAL_MS
            val now = clock.nowMillis()
            // The budget is time, not a count: an attempt's limit covers the
            // connect and then the read, so one slow attempt can outlast the
            // interval, and counting alone would carry the wake past the budget.
            if (i > 0 && now - firstAt >= WAKE_BUDGET_MS) break
            if (now < due) clock.sleep(due - now)
            stopIfInterrupted("wake")
            if (mac != null) wakeOnLan.send(mac)
            try {
                val res = transport.request(
                    method = "POST",
                    url = "http://$host:$PORT_WAKE/apps/FireTVRemote",
                    body = null,
                    headers = emptyMap(),
                    timeoutMs = WAKE_ATTEMPT_TIMEOUT_MS
                )
                if (!res.ok) {
                    throw IOException("firehose-remote: wake: TV returned status ${res.status}")
                }
                return
            } catch (e: TransportTimeout) {
                last = e
            } catch (e: TransportConnectionRefused) {
                last = e
            } catch (e: InterruptedIOException) {
                throw stopped("wake", e)
            }
        }
        // The last attempt's own failure is what the caller reports. The first
        // attempt always runs, so there is one.
        throw checkNotNull(last)
    }

    private fun pairingHeaders() = mapOf(
        "X-Api-Key" to apiKey,
        "Content-Type" to "application/json"
    )

    private fun commandHeaders(token: String) = mapOf(
        "X-Api-Key" to apiKey,
        "X-Client-Token" to token,
        "Content-Type" to "application/json"
    )

    /**
     * Escape a value for embedding in a JSON string literal.
     *
     * Control characters must be escaped or the request body is not JSON at all
     * — a raw newline inside a string literal is a parse error, so an
     * unescaped one turns a key press into a malformed request rather than a
     * refused one.
     */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '' -> sb.append("\\f")
                isControl(c) -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isControl(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F

    /**
     * Find the **top-level** `description` in a response body.
     *
     * Tracked by depth rather than by string search: `indexOf` finds the first
     * occurrence anywhere, so a `description` nested inside some other object
     * would be read as though it were the TV's own answer. A token is then
     * whatever that nested field happened to hold.
     *
     * Tracked by **key position** as well as depth, because depth alone is not
     * enough to tell a field from a quote. Inside an object the two alternate —
     * key, value, key, value — and only a key can name the field, so
     * `{"x":"description","description":"zBMBFhY"}` must find one field and not
     * report the word appearing as a *value* as a second one. Reading position
     * as identity here threw away a valid token and reported the TV as
     * unreadable, which is the false-diagnosis shape this type exists to
     * prevent.
     */
    private fun readDescription(json: String): Description {
        var i = 0
        var depth = 0
        var found: String? = null
        var expectKey = false
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    val end = endOfString(json, i) ?: return Description.Unterminated
                    if (depth == 1 && expectKey) {
                        var j = end + 1
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j >= json.length || json[j] != ':') return Description.NotAString
                        j++
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (json.substring(i, end + 1) == "\"description\"") {
                            if (j >= json.length || json[j] != '"') return Description.NotAString
                            val valueEnd = endOfString(json, j) ?: return Description.Unterminated
                            val decoded = unescape(json.substring(j + 1, valueEnd))
                                ?: return Description.MalformedEscape
                            if (found != null) return Description.Duplicate
                            found = decoded
                            i = valueEnd + 1
                        } else {
                            // A different field. Step past the key and its colon;
                            // the value is left to the loop, which now knows the
                            // next string it meets is a value and not a name.
                            i = j
                        }
                        expectKey = false
                    } else {
                        i = end + 1
                    }
                }
                '{' -> { depth++; if (depth == 1) expectKey = true; i++ }
                '[' -> { depth++; i++ }
                '}', ']' -> { depth--; if (depth == 1) expectKey = false; i++ }
                ',' -> { if (depth == 1) expectKey = true; i++ }
                else -> i++
            }
        }
        return if (found != null) Description.Found(found) else Description.Missing
    }

    /** Index of the quote closing the string that opens at [start], or null if unterminated. */
    private fun endOfString(json: String, start: Int): Int? {
        var i = start + 1
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    /**
     * Decode a JSON string literal's body.
     *
     * `\uXXXX` is handled explicitly. Falling through to the raw next character
     * — which is what a `when (esc) { ... else -> esc }` does — silently
     * rewrites an escape sequence into its own letters: `A` (an `A`)
     * arrives as the four characters `u0041`.
     *
     * A null return is the point, not a failure to handle. Degrading an
     * undecodable escape to its own letters produced the token `u004` out of
     * `{"description":"\u004"}` — a string that passed the token shape check and
     * was written to `SharedPreferences` as a credential the TV never issued.
     */
    private fun unescape(raw: String): String? {
        if ('\\' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (raw[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append(''); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    // All four must be ASCII hex digits, and there are two
                    // separate traps here. `toIntOrNull(16)` alone accepts a
                    // leading sign, so `\u+041` decoded to 'A'. And
                    // `digitToIntOrNull(16)` is Unicode-aware: it accepts
                    // Arabic-Indic and Devanagari digits, so `\u٠٠٤١` decoded to
                    // 'A' as well — and that fabricated character was then stored
                    // as the pairing token. Check the class explicitly.
                    val code = if (hex.length == 4 && hex.all { h ->
                            h in '0'..'9' || h in 'a'..'f' || h in 'A'..'F'
                        }) {
                        hex.toInt(16)
                    } else null
                    if (code == null) return null
                    sb.append(code.toChar())
                    i += 2 + hex.length
                }
                // JSON defines no other escape. Keeping the letter and dropping
                // its backslash is the same fabrication by a shorter route.
                else -> return null
            }
        }
        return sb.toString()
    }

    companion object {
        const val API_KEY = "0987654321"
        const val PORT_COMMAND = 8080
        const val PORT_WAKE = 8009
        const val KEY_PRESS_GAP_MS = 220L

        /**
         * How long to keep polling for the command API after a wake, before
         * letting the retry speak for itself.
         *
         * **Measured 2026-09-18, not guessed.** Ten wake cycles, each taken from
         * a TV whose control port was refused at the first poll — so each sample
         * is one genuine wake, self-verified rather than assumed. In ms:
         * 530, 533, 536, 809, 823, 1660, 1818, 1821, 1822, 1835.
         *
         * The figure is the observed **maximum** (1835) plus 50%, rounded up to a
         * whole [WAKE_POLL_INTERVAL_MS]: 2752.5 → 3000. The maximum rather than
         * the mean because this is a deadline the loop waits out, and a
         * mean-shaped deadline would fail roughly half the time.
         *
         * The spread is wide — 1835 / 530 = 3.5x — so the 50% margin stands
         * rather than shrinking. What sets the tail is not the poll interval but
         * the ~1000 ms read timeout: a poll landing on an accepted-but-silent
         * connection costs a whole timeout, and each of the four slowest samples
         * carries exactly one. Two would land near 2835 ms, inside this ceiling.
         *
         * This is a ceiling, not an expectation — the loop returns the instant the
         * API answers, so the ordinary case never pays it. Method, and what the
         * figure does *not* cover: `docs/protocol.md § 3`.
         */
        const val WAKE_SETTLE_MS = 3000L

        /**
         * Granularity of the poll, not a ceiling — deliberately left at 250 ms by
         * the 2026-09-18 measurement, which turned up no evidence against it. The
         * samples say the cost of a wake is set by how many polls land on a hung
         * connection (~1000 ms each), not by how often we look, so re-tuning this
         * would be the same guess-replacing-guess failure that [WAKE_SETTLE_MS]'s
         * measurement exists to end. [WAKE_SETTLE_MS] is kept a whole multiple of
         * this so the final poll is never a partial interval.
         */
        const val WAKE_POLL_INTERVAL_MS = 250L

        /**
         * The limit on one wake attempt — to connect, and again to answer.
         *
         * **Measured 2026-09-25**, from the vendor app's own log (tag
         * `DeviceConnection`) on a Home press against `the test TV`, put to sleep and
         * left eleven minutes: `Setting request timeouts to 2 seconds` on every
         * wake attempt, each paired with a Wake-on-LAN packet. Two failed to
         * connect; the third was answered `201` 6.5 s after the first packet,
         * and the TV woke.
         *
         * The length of an attempt is not what wakes the TV — the packet is.
         * This client's wake attempts without it, one of 10 s and later sixteen
         * of this length, never connected (`docs/protocol.md § 3`; captured
         * 2026-09-25).
         */
        const val WAKE_ATTEMPT_TIMEOUT_MS = 2000

        /**
         * How far apart wake attempts start. **Measured**: the vendor app's
         * attempts started 2.53 s and 2.58 s apart on 2026-09-25, and 2.53 s and
         * 2.59 s apart in the 2026-09-18 capture — a 2 s attempt, then about half
         * a second's pause. Longer than [WAKE_ATTEMPT_TIMEOUT_MS], so a connect
         * that times out has ended before the next attempt is due.
         */
        const val WAKE_ATTEMPT_INTERVAL_MS = 2500L

        /**
         * How long to keep trying before the press fails: 16 attempts.
         *
         * The longest wake on record is the vendor app's ~35 s of attempts
         * against `the test TV` on 2026-09-17 (`docs/protocol.md § 3`), and this sits
         * above it. The house rule for a deadline — observed maximum plus 50% —
         * would give 52.5 s; this stops short of it on purpose, decided
         * 2026-09-25: a remote greyed out for most of a minute is a
         * failure of its own, however honestly it says "Waking".
         */
        const val WAKE_BUDGET_MS = 40_000L

        /**
         * The shuttle body the vendor app sent for a backward scan, captured
         * 2026-09-17 with both presses byte-identical (`docs/protocol.md § 2`,
         * Media). `durationInSeconds` is passed per request there rather than
         * left to the device.
         *
         * The field name reads as a step size in seconds, but 2026-09-18's live
         * test on `.22` shuttled media *roughly ten minutes*, not ten. What the
         * device actually does with the field is tracked at `docs/protocol.md § 6`
         * — the value stays at `"10"` because it is what was captured, not
         * because it names a step in any known unit.
         */
        const val SCAN_BACK_BODY = """{"direction":"back","durationInSeconds":"10","speed":"1"}"""

        /**
         * The shuttle body for a forward scan. Same shape as [SCAN_BACK_BODY]
         * with `direction: "forward"` — the field's other legal value, inferred
         * rather than captured. Shipped as a matched pair with rewind so an
         * overshoot on rewind is correctable.
         */
        const val SCAN_FORWARD_BODY = """{"direction":"forward","durationInSeconds":"10","speed":"1"}"""

        /**
         * The level-field spelling that means the device cannot do it. Sent to
         * `docs/protocol.md § 2` as a quoted value rather than inferred: it is
         * what the measured read returned for both volume and power.
         */
        const val NOT_SUPPORTED = "NotSupported"

        const val PIN_RETRY_ATTEMPTS = 3
        const val PIN_RETRY_INTERVAL_MS = 1000L

        /** The one `description` value that means "not yet" rather than a result. */
        const val OK_SENTINEL = "OK"

        /**
         * Upper bound on a persisted token. The only token shape documented
         * (`docs/protocol.md § 1`) is seven characters; this is generous by two
         * orders of magnitude and exists to keep an unbounded response body from
         * becoming a stored credential.
         */
        const val MAX_TOKEN_LENGTH = 512
    }
}
