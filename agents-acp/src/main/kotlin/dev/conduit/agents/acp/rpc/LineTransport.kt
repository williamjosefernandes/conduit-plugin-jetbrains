package dev.conduit.agents.acp.rpc

/**
 * A bidirectional stream of newline-delimited messages. ACP frames JSON-RPC as one JSON value
 * per line over the agent's stdio; tests and replays substitute in-memory implementations.
 */
interface LineTransport {
    /** Returns the next line from the other side, or null once the stream has ended. */
    suspend fun receive(): String?

    /**
     * Sends one line (without the terminator).
     *
     * @throws java.io.IOException if the other side is gone
     */
    suspend fun send(line: String)

    /** Releases the transport and whatever backs it. Idempotent. */
    fun close()
}
