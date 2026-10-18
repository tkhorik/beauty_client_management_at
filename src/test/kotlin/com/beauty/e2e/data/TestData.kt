package com.beauty.e2e.data

import com.beauty.e2e.config.Credentials
import com.beauty.e2e.config.E2eConfig
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unique, valid-by-construction test data.
 *
 * Every value carries the run id plus a counter, so nothing collides across
 * tests, parallel workers or runs against a persistent local database, and any
 * leftover row can be traced to the run that made it. Accounts cannot be deleted
 * through the API, so uniqueness — not cleanup — is what keeps runs independent.
 */
object TestData {
    private val counter = AtomicInteger()
    private val random = SecureRandom()

    private fun unique() = "${E2eConfig.runId}-${counter.incrementAndGet()}"

    /** `.test` is reserved (RFC 2606), so no real mailbox can ever receive these. */
    fun credentials(role: String) = Credentials(email = "e2e-$role-${unique()}@example.test", password = password())

    /** Backend minimum is 12 characters; 24 random URL-safe characters. */
    fun password(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_"
        return (1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    fun personName(prefix: String) = "$prefix ${unique()}"

    /** Organization handle: lowercase letters, digits and single hyphens, 3–100 chars. */
    fun slug(prefix: String) = "e2e-$prefix-${unique()}".lowercase()

    fun phone() = "+1555" + (1..7).map { random.nextInt(10) }.joinToString("")
}
