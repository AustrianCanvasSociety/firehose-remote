package app.burro.firehoseremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared address parser's own suite.
 *
 * Written for two callers, not one. Step 2 needs it to reject a mistyped
 * address before anything reaches the network; Step 3 needs the identical
 * per-octet check to reject a malformed CIDR before enumerating 254 nonsense
 * hosts. The rejection set here is therefore deliberately wider than either
 * caller alone requires.
 *
 * The `protocol/` package may import neither `android.*` nor `java.net.*`
 * (`ProtocolPackageIsolationTest` enforces it), which is why this parser is
 * hand-rolled rather than delegating to `InetAddress` — that class accepts
 * hostnames, so validating `abc.def.ghi.jkl` through it would be a network call.
 */
class Ipv4Test {

    private fun accepted(text: String): String {
        val result = Ipv4.parse(text)
        assertTrue("expected <$text> to be accepted, got $result", result is AddressParse.Valid)
        return (result as AddressParse.Valid).normalized
    }

    private fun defect(text: String): AddressDefect {
        val result = Ipv4.parse(text)
        assertTrue("expected <$text> to be rejected, got $result", result is AddressParse.Rejected)
        return (result as AddressParse.Rejected).defect
    }

    @Test
    fun acceptsTheAddressesThatHaveToWork() {
        assertEquals("0.0.0.0", accepted("0.0.0.0"))
        assertEquals("255.255.255.255", accepted("255.255.255.255"))
        assertEquals("192.0.2.22", accepted("192.0.2.22"))
    }

    @Test
    fun trimsSurroundingWhitespaceBeforeValidating() {
        assertEquals("192.0.2.22", accepted("  192.0.2.22  "))
        assertEquals("192.0.2.22", accepted("\t192.0.2.22\n"))
    }

    @Test
    fun rejectsAnOctetAboveTwoFiftyFive() {
        assertEquals(AddressDefect.OctetOutOfRange, defect("256.1.1.1"))
        assertEquals(AddressDefect.OctetOutOfRange, defect("192.0.2.256"))
        assertEquals(AddressDefect.OctetOutOfRange, defect("999.999.999.999"))
    }

    @Test
    fun rejectsASignedOctet() {
        assertEquals(AddressDefect.OctetOutOfRange, defect("-1.-1.-1.-1"))
        assertEquals(AddressDefect.OctetOutOfRange, defect("+8.1.1.1"))
    }

    @Test
    fun rejectsANonNumericOctet() {
        assertEquals(AddressDefect.NonNumericOctet, defect("abc.def.ghi.jkl"))
        assertEquals(AddressDefect.NonNumericOctet, defect("192.0.junk.1"))
        assertEquals(AddressDefect.NonNumericOctet, defect("192.0.2.2a"))
    }

    @Test
    fun rejectsAnEmptyOctet() {
        assertEquals(AddressDefect.EmptyOctet, defect("192.0..1"))
        assertEquals(AddressDefect.EmptyOctet, defect(".192.0.4"))
    }

    @Test
    fun rejectsTheWrongNumberOfOctets() {
        assertEquals(AddressDefect.WrongOctetCount, defect("192.0.2"))
        assertEquals(AddressDefect.WrongOctetCount, defect("192.0.2.22.1"))
        assertEquals(AddressDefect.WrongOctetCount, defect("192"))
    }

    @Test
    fun rejectsAPortOrCidrSuffix() {
        assertEquals(AddressDefect.PortOrCidrSuffix, defect("192.0.2.22:8080"))
        assertEquals(AddressDefect.PortOrCidrSuffix, defect("192.0.2.0/24"))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnly() {
        assertEquals(AddressDefect.Empty, defect(""))
        assertEquals(AddressDefect.Empty, defect("   "))
    }

    @Test
    fun rejectsEmbeddedWhitespace() {
        assertEquals(AddressDefect.EmbeddedWhitespace, defect("192.0. 4.22"))
        assertEquals(AddressDefect.EmbeddedWhitespace, defect("192.0 .4.22"))
        assertEquals(AddressDefect.EmbeddedWhitespace, defect("192.0.4 .22"))
    }

    /**
     * Every rejection class must be distinguishable from every other, so a
     * debugger reading a failure knows which thing went wrong. This asserts
     * distinctness rather than wording, so the messages stay free to change.
     */
    @Test
    fun everyRejectionClassIsDistinct() {
        val defects = listOf(
            defect(""),
            defect("   "),          // same class as above: Empty
            defect("192.0. 4.22"),
            defect("192.0.2"),
            defect("192.0.junk.1"),
            defect("192.0..1"),
            defect("256.1.1.1"),
            defect("192.0.2.22:8080")
        )
        assertEquals(
            "the four named classes in the plan must be tellable apart",
            4,
            setOf(defects[0], defects[2], defects[3], defects[4]).size
        )
        assertEquals(
            "every distinct input class maps to its own defect",
            7,
            defects.toSet().size // "" and "   " are both Empty; every other input is its own defect
        )
    }

    @Test
    fun aValidAddressIsNotReportedAsRejected() {
        // Asserted on `parse` directly: the `isValid` convenience wrapper had no
        // caller outside this test, so it was removed rather than kept alive by
        // its own assertion.
        assertTrue(Ipv4.parse("192.0.2.22") is AddressParse.Valid)
        assertTrue(Ipv4.parse("192.0.2.999") !is AddressParse.Valid)
    }

    @Test
    fun aLeadingZeroOctetIsRefusedRatherThanGuessedAt() {
        // Not a range problem: `004` is 4. The problem is that `inet_aton` reads
        // a leading zero as *octal*, so `192.0.2.022` is 192.0.2.18 to one
        // resolver and 192.0.2.22 to another — the same string, two hosts. This
        // client does not get to pick which one the request goes to, so it
        // refuses the form instead.
        for (bad in listOf("192.0.2.022", "192.0.2.01", "010.1.1.1")) {
            val result = Ipv4.parse(bad)
            assertTrue(
                "<$bad> must be refused as a leading-zero octet, got $result",
                result is AddressParse.Rejected && result.defect == AddressDefect.LeadingZeroOctet
            )
        }
        // A single `0` is not a leading zero, and must still parse.
        assertTrue(Ipv4.parse("192.0.2.1") is AddressParse.Valid)
    }

    @Test
    fun nonAsciiDigitsAreNotOctets() {
        // `Char.isDigit()` is Unicode-aware, so an Arabic-Indic or fullwidth
        // octet passed the numeric check, came back Valid, and was handed to the
        // transport as a host — which then resolved it over DNS. That lookup is
        // the exact network call this parser exists to make impossible. Built
        // from code points so the characters under test are unambiguous here.
        val arabic = listOf(0x0661, 0x0662, 0x0663).map { Char(it) }.joinToString("")
        val fullwidth = listOf(0xFF11, 0xFF12, 0xFF13).map { Char(it) }.joinToString("")
        for (bad in listOf("$arabic.1.1.1", "$fullwidth.1.1.1", "192.0.2.$arabic")) {
            assertTrue(
                "a non-ASCII digit is not a digit for an IPv4 octet",
                Ipv4.parse(bad) is AddressParse.Rejected
            )
        }
    }
}
