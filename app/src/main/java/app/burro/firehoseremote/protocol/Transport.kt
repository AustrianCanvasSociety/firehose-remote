package app.burro.firehoseremote.protocol

import java.io.IOException

interface Transport {
    /**
     * Send one request.
     *
     * [timeoutMs], when given, replaces both the connect and the read limit for
     * this request alone; null keeps the transport's own. The wake is the caller
     * that needs it: every command wants the short limit, because a command that
     * goes quiet is how a sleeping TV is noticed at all, while the wake exists to
     * reach a TV in exactly that state.
     */
    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null
    ): TransportResponse
}

data class TransportResponse(
    val status: Int,
    val body: String
)

/** Whether a response's status is a 2xx success — shared by every caller that only needs a yes/no. */
internal val TransportResponse.ok: Boolean get() = status in 200..299

class TransportTimeout(message: String, cause: Throwable? = null) : IOException(message, cause)

class TransportConnectionRefused(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The host answered, but with a status this client will not proceed on.
 *
 * Kept apart from [TransportTimeout] and [TransportConnectionRefused] because
 * the host is demonstrably there and replying. Folding it in with them reports
 * a TV that just spoke as one that never answered — which sends the user to
 * check a power cable on a device that is plainly on.
 */
class TransportStatus(val status: Int, message: String) : IOException(message)

interface Clock {
    fun nowMillis(): Long
    fun sleep(millis: Long)
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun sleep(millis: Long) { Thread.sleep(millis) }
}
