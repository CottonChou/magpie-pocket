package com.magpie.pocket.core.network

import com.magpie.pocket.core.model.Capabilities

/**
 * One place for the session and capability rules used by both the connection sheet and
 * runtime startup. A restored connection must repeat the Magpie sign-in exchange on the
 * exact client that will perform subsequent requests; cookies are never persisted.
 */
object MagpieSession {

    data class ProbeResult(
        val capabilities: Capabilities,
        val state: UpstreamStateResponse,
        val groupsSupported: Boolean,
        val traceSupported: Boolean
    )

    /**
     * Establishes a Magpie session when a Web Key is configured. A blank key is allowed for
     * servers that do not require one, but the protected probe still decides whether access
     * is actually available.
     */
    suspend fun initialize(client: MagpieApiClient, baseUrl: String, webKey: String?) {
        val key = webKey?.trim().orEmpty()
        if (key.isEmpty()) return

        val signedIn = client.signIn(baseUrl, key)
        if (!signedIn) {
            throw AuthenticationRequiredException(
                "需要有效的 Magpie Web Key。请在连接设置中填写服务端 Web Key（不是 Cloudflare Access 凭证）。"
            )
        }
    }

    /** Sign in (if needed) and verify the protected management API. */
    suspend fun connect(
        client: MagpieApiClient,
        baseUrl: String,
        webKey: String?
    ): ProbeResult {
        initialize(client, baseUrl, webKey)
        return try {
            probe(client, baseUrl)
        } catch (e: AuthenticationRequiredException) {
            if (webKey.isNullOrBlank()) {
                throw AuthenticationRequiredException(
                    "需要有效的 Magpie Web Key。请在连接设置中填写服务端 Web Key（不是 Cloudflare Access 凭证）。"
                )
            }
            throw e
        }
    }

    /**
     * `/api/state` is required. Optional endpoints may only degrade on a genuine 404
     * (unsupported route); auth, transport and parsing failures always propagate.
     */
    suspend fun probe(client: MagpieApiClient, baseUrl: String): ProbeResult {
        val state = client.getState(baseUrl)
        val groups = optional(client) { it.getGroups(baseUrl) }
        val trace = optional(client) { it.getTraceSnapshot(baseUrl, 0L) }
        val usage = optional(client) { it.getUsage(baseUrl) }
        val ledger = optional(client) { it.getLedgerRequests(baseUrl, limit = 1) }
        val quotas = optional(client) { it.getQuotas(baseUrl) }

        val capabilities = Capabilities(
            trace = trace?.mine == true,
            groups = groups != null,
            // Only endpoints this probe actually reached are claimed. History and
            // request-detail routes need an id/day, so they are not guessed here.
            history = false,
            requestDetail = false,
            usage = usage != null,
            ledger = ledger != null,
            quotas = quotas != null,
            statisticsTimeZone = state.timezone ?: "Asia/Shanghai",
            verifiedDeploymentVersion = state.version ?: "Magpie"
        )

        return ProbeResult(
            capabilities = capabilities,
            state = state,
            groupsSupported = groups != null,
            traceSupported = trace != null && trace.mine
        )
    }

    private suspend fun <T> optional(
        client: MagpieApiClient,
        block: suspend (MagpieApiClient) -> T
    ): T? {
        return try {
            block(client)
        } catch (e: ResourceNotFoundException) {
            null
        }
    }
}
