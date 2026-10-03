package app.burro.firehoseremote.protocol

/**
 * Why a typed address was refused.
 *
 * One enum rather than a message string, for the same reason
 * [ProbeOutcome] is four cases rather than a nullable: the caller
 * needs to know which *kind* of thing went wrong without parsing prose. The
 * classes are kept distinct so a person reading a failure can tell "you typed
 * letters" from "that number is too big" — the difference is the whole message.
 *
 * The rare cases (`EmptyOctet`, `PortOrCidrSuffix`) exist because they are the
 * two a user hits by pasting rather than typing: `192.0..1` from a truncated
 * copy, and `192.0.2.22:8080` from a browser address bar.
 */
enum class AddressDefect {
    /** Nothing at all, or only whitespace. */
    Empty,

    /** Whitespace *inside* the address — `192.0. 4.22`. Surrounding whitespace is trimmed, not rejected. */
    EmbeddedWhitespace,

    /** Not four dot-separated parts — three, five, or one. */
    WrongOctetCount,

    /** A part holding something that is not a decimal digit by digit — `junk`, `2a`. */
    NonNumericOctet,

    /** A part holding no characters at all — the `..` in `192.0..1`. */
    EmptyOctet,

    /**
     * A part with a leading zero — `192.0.2.022`.
     *
     * Not out of range and not a typo the parser can read past: `inet_aton`
     * takes a leading zero as **octal**, so that string is 192.0.2.18 to one
     * resolver and 192.0.2.22 to another. Refusing the form fixes the
     * interpretation rather than leaving it to whichever resolver the platform
     * picked — and the request that follows carries the API key and then the
     * user's PIN, so it must go to the host the user meant.
     */
    LeadingZeroOctet,

    /** A part that is a number but not one an octet can hold — above 255, or carrying a sign. */
    OctetOutOfRange,

    /** A `:port` or `/prefix` stuck to the end, as pasted from a browser or a CIDR. */
    PortOrCidrSuffix,
}

/**
 * The result of reading a typed address.
 *
 * A rejection is a value, not an exception: a mistyped address is an ordinary
 * thing for a person to do, and the caller renders it the same way it renders
 * every other failure — as state the screen can show.
 */
sealed class AddressParse {
    /** A well-formed dotted-quad. [normalized] is the input with surrounding whitespace removed. */
    data class Valid(val normalized: String) : AddressParse()

    /** Not usable as an address. [defect] says which way it failed. */
    data class Rejected(val defect: AddressDefect) : AddressParse()
}

/**
 * Dotted-quad validation with no `java.net.*` and no `android.*`.
 *
 * Hand-rolled rather than delegating to `InetAddress`, which is the whole point:
 * `InetAddress.getByName` accepts hostnames by *resolving* them, so asking it
 * whether `abc.def.ghi.jkl` is an address makes a network call. This parser
 * answers without touching the network, which is what lets a malformed address
 * be refused before any transport call is made — and what lets it be tested on
 * the JVM with no device.
 *
 * Two callers share it. `PairingFlow` uses it to vet a typed address before
 * contacting anything, and `Discovery` uses the same per-octet check to reject
 * a malformed CIDR before enumerating 254 nonsense hosts. One parser, so a
 * validation gap has one place to be fixed.
 *
 * Deliberately **not** a general address library: no IPv6, no hostnames, no
 * CIDR arithmetic, no port parsing. It answers one question — "is this four
 * decimal octets in 0..255?" — and nothing else.
 */
object Ipv4 {

    /**
     * Read [text] as a dotted-quad.
     *
     * Surrounding whitespace is trimmed first, because a paste picks it up and
     * it is never what the user meant. Whitespace *inside* is a rejection
     * rather than something to strip: silently closing up `192.0. 4.22` would
     * guess at where a fifth octet was meant to go.
     *
     * The accepted form is exactly four decimal octets. A leading zero is a
     * rejection — `192.0.2.022` reports [AddressDefect.LeadingZeroOctet]
     * rather than being read as `192.0.2.22`, because resolvers disagree on
     * whether that zero means octal.
     */
    fun parse(text: String): AddressParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return AddressParse.Rejected(AddressDefect.Empty)

        // Checked before splitting: "192.0. 4.22" would otherwise read as a
        // four-part address whose third part is " 4".
        if (trimmed.any { it.isWhitespace() }) {
            return AddressParse.Rejected(AddressDefect.EmbeddedWhitespace)
        }

        // A port or a prefix length is a different kind of thing pasted into
        // this field, and saying so is more use than "not four parts".
        if (trimmed.contains(':') || trimmed.contains('/')) {
            return AddressParse.Rejected(AddressDefect.PortOrCidrSuffix)
        }

        val octets = trimmed.split('.')
        if (octets.size != 4) return AddressParse.Rejected(AddressDefect.WrongOctetCount)

        for (octet in octets) {
            if (octet.isEmpty()) return AddressParse.Rejected(AddressDefect.EmptyOctet)

            // A sign makes it out of range rather than non-numeric: `-1` and
            // `+8` are numbers, just not octets, and the message that says
            // "must be 0 to 255" fits them better than "must be digits".
            if (octet[0] == '-' || octet[0] == '+') {
                return AddressParse.Rejected(AddressDefect.OctetOutOfRange)
            }

            // ASCII digits only. `Char.isDigit()` is Unicode-aware, so a pasted
            // Arabic-Indic or Devanagari octet (`١٢٣`) passed this check and was
            // handed to the transport as a valid address, where it resolved over
            // DNS — the network call this parser exists to make impossible.
            if (!octet.all { it in '0'..'9' }) {
                return AddressParse.Rejected(AddressDefect.NonNumericOctet)
            }

            // No leading zeros — see [AddressDefect.LeadingZeroOctet]. Checked
            // before the range test so `004` reports its own shape rather than
            // "out of range", which it is not.
            if (octet.length > 1 && octet[0] == '0') {
                return AddressParse.Rejected(AddressDefect.LeadingZeroOctet)
            }

            // Null here means the digits overflow Int — a 20-digit octet is
            // emphatically out of range, not a parse failure to report as one.
            val value = octet.toIntOrNull() ?: return AddressParse.Rejected(AddressDefect.OctetOutOfRange)
            if (value !in 0..255) return AddressParse.Rejected(AddressDefect.OctetOutOfRange)
        }

        return AddressParse.Valid(trimmed)
    }
}
