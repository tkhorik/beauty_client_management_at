package com.beauty.e2e.support

import com.beauty.e2e.config.Credentials
import com.beauty.e2e.config.E2eConfig
import com.microsoft.playwright.APIRequest
import com.microsoft.playwright.APIRequestContext
import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.RequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Header the backend reads to resolve the organization for every scoped request. */
const val ORG_HEADER = "X-Org-Id"

/**
 * Thin client over Playwright's [APIRequestContext], talking to the backend directly.
 *
 * Owned by [PlaywrightExtension] (one per test class, same thread as its
 * `Playwright`). It signs in with the backend's *body* transport — no
 * `X-Auth-Transport` header — so the refresh token comes back in JSON and no
 * cookie lands in this context's jar; each [Session] carries its own bearer token.
 */
class ApiClient(playwright: Playwright) : AutoCloseable {
    private val request: APIRequestContext =
        playwright.request().newContext(APIRequest.NewContextOptions().setBaseURL(E2eConfig.apiUrl))

    fun register(credentials: Credentials, fullName: String): Session {
        val body = buildJsonObject {
            put("email", credentials.email)
            put("password", credentials.password)
            put("fullName", fullName)
            // Explicit, so the account's language never depends on a header default.
            put("languagePreference", "en")
        }
        val response = RateLimit.backoff { send("POST", "/api/auth/register", body = body) }.expectStatus(201)
        return Session.from(this, credentials, response.json())
    }

    fun login(credentials: Credentials): Session {
        val body = buildJsonObject {
            put("email", credentials.email)
            put("password", credentials.password)
        }
        val response = RateLimit.backoff { send("POST", "/api/auth/login", body = body) }.expectStatus(200)
        return Session.from(this, credentials, response.json())
    }

    /** Raw request, for tests that assert on refusals. Never throws on a non-2xx status. */
    fun send(method: String, path: String, bearer: String? = null, orgId: String? = null, body: JsonElement? = null): ApiResponse {
        val options = RequestOptions.create().setMethod(method).setMaxRedirects(0)
        bearer?.let { options.setHeader("Authorization", "Bearer $it") }
        orgId?.let { options.setHeader(ORG_HEADER, it) }
        body?.let { options.setHeader("Content-Type", "application/json").setData(it.toString()) }
        return request.fetch(path, options).toApiResponse(method, path)
    }

    override fun close() = request.dispose()
}

/** A signed-in account. The access token lives 15 minutes, far longer than any test class here. */
class Session private constructor(
    private val client: ApiClient,
    val credentials: Credentials,
    val userId: String,
    val globalRole: String,
    private val accessToken: String,
) {
    fun get(path: String, orgId: String? = null) = client.send("GET", path, accessToken, orgId)
    fun post(path: String, body: JsonElement? = null, orgId: String? = null) = client.send("POST", path, accessToken, orgId, body)
    fun put(path: String, body: JsonElement, orgId: String? = null) = client.send("PUT", path, accessToken, orgId, body)
    fun delete(path: String, orgId: String? = null) = client.send("DELETE", path, accessToken, orgId)

    override fun toString() = "Session(${credentials.email})"

    companion object {
        fun from(client: ApiClient, credentials: Credentials, auth: JsonObject): Session {
            val user = auth.getValue("user").jsonObject
            return Session(
                client = client,
                credentials = credentials,
                userId = user.string("id"),
                globalRole = user.string("globalRole"),
                accessToken = auth.string("token"),
            )
        }
    }
}

class ApiResponse(
    val method: String,
    val path: String,
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    fun jsonElement(): JsonElement = Json.parseToJsonElement(body)

    /** The `code` field of the backend's `{"error", "code"}` error shape. */
    fun errorCode(): String? = runCatching { json()["code"]?.jsonPrimitive?.content }.getOrNull()

    fun expectStatus(expected: Int): ApiResponse {
        if (status != expected) throw AssertionError("expected HTTP $expected but got: $this")
        return this
    }

    /** Status and a redacted, truncated body: safe to print in a CI log. */
    override fun toString() = "$method $path → $status ${redactSecrets(body).take(500)}"
}

fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.content ?: throw AssertionError("missing '$key' in $this")

private val SECRET_FIELDS = Regex(""""(token|refreshToken|password|creationToken)"\s*:\s*"[^"]*"""")

internal fun redactSecrets(text: String) = text.replace(SECRET_FIELDS, "\"$1\":\"***\"")

/** Reads everything needed from a Playwright response and releases its body. */
internal fun APIResponse.toApiResponse(method: String, path: String): ApiResponse =
    try {
        ApiResponse(method, path, status(), text(), headers())
    } finally {
        dispose()
    }

/**
 * The backend allows 10 credential calls (register, login, create organization)
 * per minute per client IP, and the limit is not configurable. A whole suite run
 * from one machine can exceed it, so a 429 on those endpoints — and only those;
 * callers opt in per call site — is waited out for
 * as long as the server's `Retry-After` says (plus a second: it is rounded down),
 * within a fixed overall budget. This is not a generic retry: any other status is
 * returned to the caller on the first attempt.
 */
internal object RateLimit {
    private const val MAX_TOTAL_WAIT_SECONDS = 120L

    fun backoff(call: () -> ApiResponse): ApiResponse {
        var waited = 0L
        while (true) {
            val response = call()
            if (response.status != 429 || waited >= MAX_TOTAL_WAIT_SECONDS) return response
            val wait = ((response.headers["retry-after"]?.toLongOrNull() ?: 60L) + 1)
                .coerceAtMost(MAX_TOTAL_WAIT_SECONDS - waited).coerceAtLeast(1)
            System.err.println("[e2e] credential rate limit hit on ${response.path}; waiting ${wait}s (Retry-After)")
            Thread.sleep(wait * 1000)
            waited += wait
        }
    }
}
