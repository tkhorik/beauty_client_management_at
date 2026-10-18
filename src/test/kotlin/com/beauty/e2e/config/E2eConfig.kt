package com.beauty.e2e.config

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Login details. `toString` never prints the password: these end up in assertion messages. */
data class Credentials(val email: String, val password: String) {
    override fun toString() = "Credentials(email=$email, password=***)"
}

/**
 * Where the environment is and how to drive it.
 *
 * Every value is looked up as: JVM system property (`-Pe2e.webUrl=…` via Gradle),
 * then environment variable (`E2E_WEB_URL`), then the git-ignored `.e2e/e2e.env`
 * written by `scripts/e2e-env.sh up`. URLs default to the local stack; credentials
 * have no default and fail fast when missing.
 */
object E2eConfig {
    private val envFile: Path = Path.of(".e2e", "e2e.env")

    private val fileValues: Map<String, String> by lazy {
        if (!Files.exists(envFile)) return@lazy emptyMap()
        Files.readAllLines(envFile)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    private fun value(property: String, env: String): String? =
        System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: fileValues[env]?.takeIf { it.isNotBlank() }

    private fun required(property: String, env: String): String =
        value(property, env) ?: error(
            "$env is not set. Run `scripts/e2e-env.sh up` (writes .e2e/e2e.env) or export $env."
        )

    val webUrl: String = (value("e2e.webUrl", "E2E_WEB_URL") ?: "http://127.0.0.1:5174").trimEnd('/')
    val apiUrl: String = (value("e2e.apiUrl", "E2E_API_URL") ?: "http://127.0.0.1:8080").trimEnd('/')
    val mailpitUrl: String = (value("e2e.mailpitUrl", "E2E_MAILPIT_URL") ?: "http://127.0.0.1:8025").trimEnd('/')

    /** `chromium`, `firefox` or `webkit`. */
    val browser: String = value("e2e.browser", "E2E_BROWSER") ?: "chromium"
    val headless: Boolean = value("e2e.headless", "E2E_HEADLESS")?.toBooleanStrict() ?: true
    val slowMoMs: Double = value("e2e.slowMoMs", "E2E_SLOW_MO_MS")?.toDouble() ?: 0.0

    val superAdmin: Credentials by lazy {
        Credentials(
            email = required("e2e.superAdminEmail", "E2E_SUPERADMIN_EMAIL"),
            password = required("e2e.superAdminPassword", "E2E_SUPERADMIN_PASSWORD"),
        )
    }

    /** Tags every account and organization this JVM creates, so leftovers are traceable to a run. */
    val runId: String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss")) + Random.nextInt(0x1000, 0xFFFF).toString(16)

    val artifactsDir: Path = Path.of("build", "e2e-artifacts")
}
