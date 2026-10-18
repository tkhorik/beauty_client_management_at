package com.beauty.e2e.support

import com.beauty.e2e.config.Credentials
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.options.RequestOptions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat

/**
 * Signs a browser context in the way the web app itself does, minus the form.
 *
 * `context.request()` shares the context's cookie jar, so logging in through the
 * web origin's `/api` proxy with `X-Auth-Transport: cookie` stores the httpOnly
 * `beauty_refresh` cookie exactly where the app expects it. On the next page load
 * the app exchanges that cookie for an in-memory access token.
 *
 * Each context gets its own login, and therefore its own refresh-token family.
 * Never copy `storageState` between contexts: refresh tokens are single-use, and
 * the backend treats a replayed one as theft and revokes the whole family.
 */
object BrowserSession {
    fun signIn(context: BrowserContext, credentials: Credentials) {
        val body = buildJsonObject {
            put("email", credentials.email)
            put("password", credentials.password)
        }
        // Relative to the context's baseURL, i.e. the web origin, not the API.
        val response = RateLimit.backoff {
            context.request().post(
                "/api/auth/login",
                RequestOptions.create()
                    .setHeader("X-Auth-Transport", "cookie")
                    .setHeader("Content-Type", "application/json")
                    .setData(body.toString()),
            ).toApiResponse("POST", "/api/auth/login (browser)")
        }
        response.expectStatus(200)
        assertThat(context.cookies().map { it.name })
            .describedAs("refresh cookie after browser login")
            .contains("beauty_refresh")
    }
}
