package com.beauty.e2e.api

import com.beauty.e2e.data.TestData
import com.beauty.e2e.data.Tenant
import com.beauty.e2e.data.Tenants
import com.beauty.e2e.support.ApiClient
import com.beauty.e2e.support.ApiTest
import com.beauty.e2e.support.Smoke
import com.beauty.e2e.support.closeAll
import com.beauty.e2e.support.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Client records over real HTTP, against real Postgres, in two real organizations.
 *
 * The full cross-tenant matrix lives in the backend's in-process
 * `OrganizationIsolationTest` (H2, `testApplication`). This class keeps the
 * smallest set that proves the deployed stack enforces the same contract:
 * an owner's data round-trips, and another organization gets 404 for the
 * record, 403 `NOT_A_MEMBER` for the organization, and cannot delete it.
 */
@ApiTest
@Smoke
class ClientApiSmokeTest {
    private lateinit var tenants: Tenants
    private lateinit var salon: Tenant
    private lateinit var rival: Tenant
    private val created = mutableListOf<String>()

    @BeforeAll
    fun provision(api: ApiClient) {
        tenants = Tenants(api)
        salon = tenants.provision("salon")
        rival = tenants.provision("rival")
    }

    @AfterAll
    fun cleanUp() {
        // Runs even when provisioning failed halfway; each step is attempted.
        val steps = mutableListOf<AutoCloseable>()
        if (::salon.isInitialized) {
            created.forEach { id -> steps += AutoCloseable { salon.owner.delete("/api/clients/$id", salon.orgId).expectStatus(200) } }
            steps += AutoCloseable { tenants.archive(salon) }
        }
        if (::rival.isInitialized) steps += AutoCloseable { tenants.archive(rival) }
        closeAll(*steps.toTypedArray())
    }

    @Test
    fun `an organization admin creates a client and reads it back`() {
        val name = TestData.personName("Client")
        val phone = TestData.phone()

        val client = createClient(name, phone)

        assertThat(client.string("name")).isEqualTo(name)
        assertThat(client.string("phone")).isEqualTo(phone)
        assertThat(client.getValue("tags").jsonArray.map { it.jsonPrimitive.content }).containsExactly("e2e")
        assertThat(client.getValue("totalVisits").jsonPrimitive.int).isZero()

        val id = client.string("id")
        val readBack = salon.owner.get("/api/clients/$id", salon.orgId).expectStatus(200).json()
        assertThat(readBack.string("name")).isEqualTo(name)
        assertThat(clientIds(salon)).contains(id)
    }

    @Test
    fun `another organization can neither see, read nor delete that client`() {
        val id = createClient(TestData.personName("Private"), TestData.phone()).string("id")
        val outsider = rival.owner

        assertThat(clientIds(rival)).doesNotContain(id)

        // A record id from another tenant answers 404, never 403: a 403 would
        // confirm the id exists.
        val read = outsider.get("/api/clients/$id", rival.orgId)
        assertThat(read.status).describedAs(read.toString()).isEqualTo(404)
        assertThat(read.errorCode()).isEqualTo("CLIENT_NOT_FOUND")

        // Naming an organization you are not a member of is refused outright.
        val foreignOrg = outsider.get("/api/clients", salon.orgId)
        assertThat(foreignOrg.status).describedAs(foreignOrg.toString()).isEqualTo(403)
        assertThat(foreignOrg.errorCode()).isEqualTo("NOT_A_MEMBER")

        val delete = outsider.delete("/api/clients/$id", rival.orgId)
        assertThat(delete.status).describedAs(delete.toString()).isEqualTo(404)
        salon.owner.get("/api/clients/$id", salon.orgId).expectStatus(200)

        // Omitting the header is a distinct, client-actionable error.
        val noOrg = salon.owner.get("/api/clients")
        assertThat(noOrg.status).describedAs(noOrg.toString()).isEqualTo(400)
        assertThat(noOrg.errorCode()).isEqualTo("MISSING_ORGANIZATION")
    }

    private fun createClient(name: String, phone: String): JsonObject {
        val body = buildJsonObject {
            put("name", name)
            put("phone", phone)
            putJsonArray("tags") { add("e2e") }
        }
        return salon.owner.post("/api/clients", body, salon.orgId).expectStatus(201).json()
            .also { created += it.string("id") }
    }

    private fun clientIds(tenant: Tenant): List<String> =
        tenant.owner.get("/api/clients", tenant.orgId).expectStatus(200)
            .jsonElement().jsonArray.map { it.jsonObject.string("id") }
}
