package app.burro.firehoseremote.net

import android.annotation.SuppressLint
import app.burro.firehoseremote.protocol.Transport
import app.burro.firehoseremote.protocol.TransportConnectionRefused
import app.burro.firehoseremote.protocol.TransportResponse
import app.burro.firehoseremote.protocol.TransportTimeout
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * `HttpURLConnection`-backed [Transport]. TLS verification is deliberately
 * disabled because the TV presents a self-signed cert issued to a hostname
 * while we connect to an IP literal — there is nothing valid to verify against
 * on a LAN device. Read and connect timeouts are set explicitly: the platform
 * default read timeout is 0 (infinite), which silently defeats the wake-on-
 * timeout recovery path.
 *
 * [log] receives one line per request — method, URL, outcome, elapsed
 * milliseconds — so a wake can be timed from the phone's log. Never headers or
 * body: the pairing token travels in a header and the PIN in a body.
 */
class HttpUrlTransport(
    val configuredReadTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val configuredConnectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val log: (String) -> Unit = {}
) : Transport {

    override fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int?
    ): TransportResponse {
        val startedAt = System.nanoTime()
        var outcome = "failed"
        try {
            return send(method, url, body, headers, timeoutMs).also { outcome = it.status.toString() }
        } catch (e: IOException) {
            outcome = when (e) {
                is TransportTimeout -> "timed out"
                is TransportConnectionRefused -> "refused"
                else -> "failed (${e.javaClass.simpleName})"
            }
            throw e
        } finally {
            log("$method $url -> $outcome in ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }
    }

    private fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int?
    ): TransportResponse {
        val readTimeoutMs = timeoutMs ?: configuredReadTimeoutMs
        val connectTimeoutMs = timeoutMs ?: configuredConnectTimeoutMs
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: ConnectException) {
            throw TransportConnectionRefused("firehose-remote: transport: connection refused: ${e.message}", e)
        }
        try {
            conn.readTimeout = readTimeoutMs
            conn.connectTimeout = connectTimeoutMs
            // Redirects follow by default, which makes any check a caller makes
            // on a URL worth nothing: a `LOCATION` header the discovery path has
            // already accepted as `http://` can answer 302 and send this request
            // anywhere — another host, another scheme, the phone's own loopback.
            // A redirect is now just a 3xx status the caller sees.
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = insecureSocketFactory
                conn.hostnameVerifier = insecureHostnameVerifier
            }
            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { readBounded(it) } ?: ""
            return TransportResponse(status = status, body = bodyText)
        } catch (e: SocketTimeoutException) {
            // Both limits: a connect timeout and a read timeout raise the same
            // exception type, and naming only the read limit reported a 2000 ms
            // connect timeout as a 1000 ms one.
            throw TransportTimeout(
                "firehose-remote: transport: timed out (connect limit ${connectTimeoutMs}ms, " +
                    "read limit ${readTimeoutMs}ms): ${e.message}",
                e
            )
        } catch (e: ConnectException) {
            throw TransportConnectionRefused("firehose-remote: transport: connection refused: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Read at most [MAX_RESPONSE_CHARS] characters.
     *
     * `readText()` reads to the end of the stream whatever that costs. A host
     * answering with an unbounded body would be read into memory in full, and
     * the resulting `OutOfMemoryError` is an `Error` — not an `IOException`, so
     * it escapes every catch on the pairing path and takes the process with it.
     *
     * Truncation is silent, and that is a known limit rather than an oversight:
     * [TransportResponse] carries a status and a body and nothing else, so a
     * caller cannot tell a short body from a cut-off one. Reporting it would mean
     * a shape change on the protocol seam to describe a case only a hostile host
     * produces. What matters is that the read stops.
     */
    private fun readBounded(reader: Reader): String {
        val sb = StringBuilder(minOf(MAX_RESPONSE_CHARS, 8192))
        val buf = CharArray(4096)
        var total = 0
        val deadline = System.currentTimeMillis() + MAX_TRANSFER_MS
        while (total < MAX_RESPONSE_CHARS) {
            // `readTimeout` is per-read *inactivity*, not a deadline on the
            // transfer. A sender trickling one byte every 900 ms never trips it,
            // and against the ceiling below that is hours on one connection.
            // This is the wall-clock bound the timeout is often mistaken for.
            if (System.currentTimeMillis() >= deadline) break
            val n = reader.read(buf, 0, minOf(buf.size, MAX_RESPONSE_CHARS - total))
            if (n < 0) break
            sb.append(buf, 0, n)
            total += n
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 1000
        const val DEFAULT_CONNECT_TIMEOUT_MS = 2000

        /**
         * Ceiling on one response transfer, in milliseconds.
         *
         * Sits above [DEFAULT_CONNECT_TIMEOUT_MS] plus a read timeout so an
         * ordinary slow reply is never cut short, and far below the hours a
         * drip-feeding sender would otherwise hold the connection for.
         */
        const val MAX_TRANSFER_MS = 4000L

        /**
         * Ceiling on a response body. Every documented reply on this protocol is
         * tens of bytes; this is generous and exists only to bound the damage.
         */
        const val MAX_RESPONSE_CHARS = 64 * 1024

        // Accepts any certificate. The TV presents a self-signed certificate
        // issued to a hostname while the app connects to an IP literal, so there
        // is nothing valid to verify against; this is deliberate and documented
        // for reviewers (README § Notes for reviewers) rather than "fixed".
        // @SuppressLint: the two lint checks below cannot see that reasoning, so
        // they are silenced at the site rather than repo-wide.
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val trustAll: TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        internal val insecureSocketFactory by lazy {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), SecureRandom())
            ctx.socketFactory
        }

        internal val insecureHostnameVerifier: HostnameVerifier =
            HostnameVerifier { _: String?, _: SSLSession? -> true }
    }
}
