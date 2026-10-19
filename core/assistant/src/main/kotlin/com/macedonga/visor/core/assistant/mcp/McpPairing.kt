package com.macedonga.visor.core.assistant.mcp

import okhttp3.HttpUrl

/**
 * A server that hands Visor its token through a pairing on the device, rather than one typed in. Each scheme is one
 * implementation, listed in [McpHub.pairings]; servers remember theirs by [id], so they can pair again later.
 */
interface McpPairing {
    /** Stored on the server; the protocol's own name. */
    val id: String

    /** Shown to the person: the app they approve the pairing in. */
    val name: String

    /** Where to pair for [address] (any address of the server); null when nothing there speaks this pairing. */
    suspend fun locate(address: String): HttpUrl?

    /** Pairs as [client] at [base]; [code] gets the code both sides show, to compare before approving. */
    suspend fun pair(base: HttpUrl, client: String, code: (String) -> Unit): Paired
}

/** What a pairing gives: the token, sent as a Bearer, and the MCP address. */
data class Paired(val token: String, val url: String)

/** A starting point on the Add sheet: a name, what to type into the address, and the pairing it uses, if any. */
data class McpPreset(val name: String, val url: String = "", val hint: String = "https://…", val pairing: String = "")
