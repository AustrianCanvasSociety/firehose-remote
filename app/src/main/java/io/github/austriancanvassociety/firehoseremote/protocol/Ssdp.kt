package io.github.austriancanvassociety.firehoseremote.protocol

/**
 * CRLF, built from code points rather than typed as an escape sequence.
 *
 * An `\r\n` literal in Kotlin source does not survive this project's tooling
 * intact — it has silently collapsed to a single character and broken a file
 * more than once. Building the pair from `Char` keeps every file in this
 * feature free of backslashes, fixtures included, so there is nothing for the
 * tooling to mangle.
 */
internal val CRLF: String = "${Char(13)}${Char(10)}"

/**
 * SSDP discovery, as Fire TV actually performs it.
 *
 * The vendor app does not sweep addresses. It sends an SSDP `M-SEARCH` for the
 * DIAL service — six times per discovery — and follows each answer's `LOCATION`
 * header to that device's own descriptor to read the name. Captured from the
 * official app (v4.7.0) against four Fire TVs on 2026-09-17; recorded in
 * `docs/protocol.md § 5`.
 *
 * This file is the pure half: it builds the datagram and reads the answers.
 * The socket lives in `net/MulticastSsdpClient`, because `protocol/` may not
 * import `java.net.*` — and because a parser exercised only through a fake
 * socket is exercised through everything except the bytes.
 */
object Ssdp {

    /** The SSDP multicast group and port, fixed by the SSDP specification. */
    const val MULTICAST_GROUP = "239.255.255.250"
    const val MULTICAST_PORT = 1900

    /**
     * What the vendor app asks for. The DIAL service type, not `ssdp:all` —
     * asking for everything would also collect every router, printer and NAS on
     * the network, none of which are Fire TVs.
     */
    const val SERVICE_TYPE = "urn:dial-multiscreen-org:service:dial:1"

    /**
     * Maximum wait the responder may delay its answer by, in seconds. One is
     * what the vendor app sends; a larger value spreads responses further apart
     * and buys nothing at this size of network.
     */
    const val MX_SECONDS = 1

    /** How many times the vendor app repeats the search per discovery. */
    const val RETRIES = 6

    /**
     * The most responders one search will collect, and the most descriptors one
     * scan will follow.
     *
     * One constant, not two. Any host on the LAN sees the M-SEARCH, and every
     * distinct answer costs one descriptor fetch downstream, run serially on the
     * worker thread the whole UI shares — so the list a stranger can hand back
     * has to be bounded, and both halves of that bound have to agree. It lives
     * here because `protocol/` may not import `net/`, so the shared home has to
     * be the protocol side.
     */
    const val MAX_RESPONDERS = 32

    /**
     * The `M-SEARCH` datagram.
     *
     * `MAN` is quoted because the SSDP specification requires it to be, and a
     * responder that validates it will ignore an unquoted one.
     */
    fun searchRequest(): String = listOf(
        "M-SEARCH * HTTP/1.1",
        "HOST: $MULTICAST_GROUP:$MULTICAST_PORT",
        "MAN: \"ssdp:discover\"",
        "MX: $MX_SECONDS",
        "ST: $SERVICE_TYPE",
        "",
        ""
    ).joinToString(CRLF)

    /**
     * One device's answer to the search.
     *
     * [location] is the only required field: it is both the proof that the
     * responder serves the DIAL service and the address of the descriptor that
     * carries the name. [wakeupMac] is genuinely optional — the `WAKEUP` header
     * is an advertisement only newer Fire OS builds send, so requiring it would
     * make every older TV undiscoverable. [usn] identifies the responder and is
     * kept for de-duplication.
     */
    data class SsdpResponse(
        val sourceIp: String,
        val location: String,
        val usn: String?,
        val wakeupMac: String?
    )

    /**
     * Read one datagram.
     *
     * Returns null rather than throwing for anything unusable. A stray packet
     * on the multicast group is ordinary — every UPnP device on the network
     * sees this port — so a malformed or unrelated answer is a fact about the
     * network, not a fault in this process.
     */
    fun parseResponse(text: String, sourceIp: String): SsdpResponse? {
        val lines = text.replace(Char(13), Char(10)).split('\n')
        val statusLine = lines.firstOrNull()?.trim().orEmpty()
        if (!statusLine.startsWith("HTTP/1.1 200")) return null

        var location: String? = null
        var usn: String? = null
        var wakeup: String? = null
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().uppercase()
            val value = line.substring(separator + 1).trim()
            if (value.isEmpty()) continue
            when (name) {
                "LOCATION" -> location = value
                "USN" -> usn = value
                "WAKEUP" -> wakeup = value
            }
        }

        val foundLocation = location ?: return null
        // The scheme is matched case-insensitively, and https is accepted. RFC
        // 3986 makes a URI scheme case-insensitive, so a responder answering
        // `HTTP://` is still a device — and a `https://` descriptor is still a
        // descriptor, which this client can fetch because it already accepts the
        // TV's self-signed certificate. Refusing the reply loses the *device*,
        // where the doctrine above is that a missing name costs only a label.
        val separator = foundLocation.indexOf("://")
        if (separator <= 0) return null
        if (foundLocation.substring(0, separator).lowercase() !in setOf("http", "https")) return null
        // A scheme with nothing after it is not an address. One junk datagram
        // must not put a phantom entry in the picker.
        if (foundLocation.substring(separator + 3).substringBefore('/').isEmpty()) return null
        return SsdpResponse(
            sourceIp = sourceIp,
            location = foundLocation,
            usn = usn,
            wakeupMac = wakeup?.let { macFromWakeup(it) }
        )
    }

    /**
     * What makes two answers the same device: **the address it came from**.
     *
     * Not the `USN`, which is what this used to key on. A USN is
     * `uuid:<id>::<service>` and the uuid names the *device*, so keying on it
     * lists one TV once even when it answers from several interfaces — the
     * behaviour a live scan on 2026-09-17 asked for, when it returned "Exercise
     * Room" twice from two addresses. But a USN is an unauthenticated
     * assertion: any host on the LAN can read the real TV's uuid out of the
     * TV's own `ssdp:alive` multicast, claim it in a reply, and win the
     * first-wins dedupe — leaving the impostor as the only entry for that TV.
     * The user pairs with it, and the token the TV mints passes through the
     * attacker. An address cannot be claimed that way, because a forged source
     * address never receives this phone's descriptor request.
     *
     * [usn] is therefore deliberately ignored, and kept in the signature so the
     * test can state the decision in the positive: the same uuid from two
     * addresses yields two keys, and a uuid claimed from elsewhere does not
     * displace the address that really owns it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun deviceKey(usn: String?, sourceIp: String): String = sourceIp

    /**
     * The MAC out of a `WAKEUP` header, or null if it does not carry one.
     *
     * The header's value is a `MAC=<addr>` pair in the captures. Kept tolerant
     * of surrounding whitespace and case because this is an advertisement
     * shape observed on one Fire OS generation, not a specified field — and a
     * missing MAC costs only the wake-on-LAN feature, never discovery.
     */
    internal fun macFromWakeup(value: String): String? {
        val marker = value.indexOf("MAC=", ignoreCase = true)
        if (marker < 0) return null
        // Take the first `;`-delimited field, then check the shape. Taking the
        // whole tail turned a doubled header (`MAC=AA:..;MAC=11:..`) into the
        // "MAC", and `MAC=zzz` into `zzz` — neither is an address, and the field
        // exists to address a wake-on-LAN packet.
        val candidate = value.substring(marker + 4).substringBefore(';').trim()
        val isMac = candidate.length == 17 && candidate.withIndex().all { (i, c) ->
            if (i % 3 == 2) c == ':' else c.isAsciiHex()
        }
        return candidate.takeIf { isMac }
    }

    /**
     * The device's name, out of its own descriptor document.
     *
     * Read from `<friendlyName>` at the `LOCATION` URL — **not** from the DIAL
     * app-status endpoint. That endpoint answers with the *application's* name
     * (`<name>FireTVRemote</name>`), which is the same string on every Fire TV
     * and would have labelled the whole picker identically. See
     * `docs/protocol.md § 5`.
     *
     * Tag names are matched case-insensitively and namespaces ignored, because
     * the descriptor's exact envelope varies across Fire OS generations. A
     * descriptor with no usable name yields null, and the caller falls back to
     * the address — which is exactly how this app behaved before this step.
     */
    fun parseFriendlyName(descriptor: String): String? {
        val open = descriptor.indexOf("<friendlyName", ignoreCase = true)
        if (open < 0) return null
        val contentStart = descriptor.indexOf('>', open)
        if (contentStart < 0) return null
        val close = descriptor.indexOf("</friendlyName", startIndex = contentStart, ignoreCase = true)
        if (close < 0) return null
        // Decoded first, then trimmed. Trimming first let a name made entirely of
        // character references survive as non-empty — `<friendlyName>&#32;</friendlyName>`
        // rendered as a blank button — where the address was the honest label.
        return decodeXmlEntities(descriptor.substring(contentStart + 1, close)).trim().ifEmpty { null }
    }

    /**
     * Decode the XML entities that can appear in a descriptor's text content.
     *
     * This is a substring scan throughout, not an XML parse — but the text it
     * returns is shown to the user, and a device named `the test TV &amp; Room`
     * rendered as that literal markup in the picker. Handles the five predefined
     * entities plus numeric character references.
     *
     * The digit checks are ASCII-only on purpose: `toIntOrNull(16)` is
     * Unicode-aware, so an unguarded `&#x٠٠٤١;` would decode to a character the
     * descriptor never contained.
     */
    private fun decodeXmlEntities(text: String): String {
        if ('&' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = text.indexOf(';', i)
            // A well-formed reference is short; anything longer is literal text.
            if (end < 0 || end - i > 10) { sb.append(c); i++; continue }
            val body = text.substring(i + 1, end)
            val decoded = when {
                body == "lt" -> '<'
                body == "gt" -> '>'
                body == "amp" -> '&'
                body == "quot" -> '"'
                body == "apos" -> '\''
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2)
                        .takeIf { s -> s.isNotEmpty() && s.all { it.isAsciiHex() } }
                        ?.toIntOrNull(16)
                        ?.let(::charOfReference)
                body.startsWith("#") ->
                    body.substring(1)
                        .takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }
                        ?.toIntOrNull()
                        ?.let(::charOfReference)
                else -> null
            }
            if (decoded == null) { sb.append(c); i++ } else { sb.append(decoded); i = end + 1 }
        }
        return sb.toString()
    }

    /**
     * The character a numeric reference names, or null when it names none.
     *
     * `Int.toChar()` truncates rather than refusing. Unguarded, `&#x10000;` and
     * `&#0;` both became a NUL and `&#x1F600;` became `U+F600` — characters the
     * descriptor never contained, rendered to the user as a device name and
     * spaced into the picker. A reference outside the Basic Multilingual Plane,
     * or inside the surrogate block, has no single `Char` to name: it stays
     * literal text rather than inventing one.
     */
    private fun charOfReference(code: Int): Char? =
        if (code in 0x1..0xFFFF && code !in 0xD800..0xDFFF) code.toChar() else null
}

/**
 * The seam [Discovery] searches through.
 *
 * An interface here and a `MulticastSocket` in `net/`, for the same reason
 * [Transport] is an interface here and `HttpUrlTransport` is not: the protocol
 * package stays testable on the JVM with no device, and the untestable part
 * stays as small as it can be made.
 */
interface SsdpSearch {

    /** Every answer seen within [timeoutMillis]. Never throws for a network fault. */
    fun search(timeoutMillis: Long): List<Ssdp.SsdpResponse>

    /**
     * No search at all. The default, so a caller that has no multicast socket
     * — every JVM test, and any future caller that only wants the address
     * sweep — gets the sweep without having to say so.
     */
    object None : SsdpSearch {
        override fun search(timeoutMillis: Long): List<Ssdp.SsdpResponse> = emptyList()
    }
}

/**
 * True for a character that is a valid ASCII hex digit — `0`-`9`, `a`-`f`, `A`-`F`.
 * Shared by the `WAKEUP` MAC check here and the magic packet built from it.
 */
internal fun Char.isAsciiHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
