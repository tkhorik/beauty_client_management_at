package com.beauty.e2e.support

import com.beauty.e2e.config.E2eConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fails the run once, with instructions, when the stack is not up.
 *
 * Deliberately a single probe rather than a wait loop: waiting for services to
 * become ready is `scripts/e2e-env.sh`'s job. By the time tests start the stack
 * is either ready or broken, and a broken stack should not cost every test its
 * own timeout.
 */
internal object Environment {
    private val result: Result<Unit> by lazy {
        runCatching {
            probe("${E2eConfig.apiUrl}/health", "backend")
            probe("${E2eConfig.webUrl}/", "web app")
            // The web app reaches the API through its own /api proxy; the browser
            // tests depend on that path, so check it rather than assume it.
            probe("${E2eConfig.webUrl}/api/auth/refresh", "web → API proxy", expected = 401, method = "POST")
        }
    }

    fun ensureReachable() = result.getOrThrow()

    // HTTP/1.1: the JDK client otherwise sends `Upgrade: h2c`, which Vite's
    // server takes for a WebSocket upgrade and never answers.
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private fun probe(url: String, what: String, expected: Int = 200, method: String = "GET") {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val status = try {
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: Exception) {
            throw IllegalStateException(
                "E2E environment not reachable: $what at $url (${e.javaClass.simpleName}). " +
                    "Start it with `scripts/e2e-env.sh up`.",
                e,
            )
        }
        check(status == expected) { "E2E environment unhealthy: $what at $url answered $status, expected $expected" }
    }
}
