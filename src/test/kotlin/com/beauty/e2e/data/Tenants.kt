package com.beauty.e2e.data

import com.beauty.e2e.config.E2eConfig
import com.beauty.e2e.support.ApiClient
import com.beauty.e2e.support.RateLimit
import com.beauty.e2e.support.Session
import com.beauty.e2e.support.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An organization created for one test class, and the account that administers it. */
data class Tenant(val orgId: String, val slug: String, val owner: Session)

/**
 * Provisions organizations through the same endpoints a real operator uses.
 *
 * Organizations are not self-service: `POST /api/organizations` needs a
 * single-use creation token issued by a SUPER_ADMIN, and SUPER_ADMIN exists only
 * for addresses in `SUPER_ADMIN_EMAILS` that were verified when the backend
 * started. `scripts/e2e-env.sh up` bootstraps that account; this class only
 * signs in as it. Same thread and lifetime as the [ApiClient] it wraps.
 */
class Tenants(private val api: ApiClient) {
    /**
     * Signed in once per test class: login is one of the rate-limited credential
     * calls, and the 15-minute access token outlives any class here.
     */
    private val superAdmin: Session by lazy(LazyThreadSafetyMode.NONE) {
        val session = api.login(E2eConfig.superAdmin)
        check(session.globalRole == "SUPER_ADMIN") {
            "${E2eConfig.superAdmin.email} signed in but is ${session.globalRole}, not SUPER_ADMIN. " +
                "Run `scripts/e2e-env.sh up` so the backend restarts with it verified and listed in SUPER_ADMIN_EMAILS."
        }
        session
    }

    fun provision(label: String): Tenant {
        val creationToken = superAdmin.post(
            "/api/admin/organization-creation-tokens",
            buildJsonObject {
                put("label", "e2e ${E2eConfig.runId} $label")
                put("maxUses", 1)
                put("expiresInHours", 1)
            },
        ).expectStatus(201).json().string("token")

        val owner = api.register(TestData.credentials("$label-owner"), TestData.personName("Owner"))
        val slug = TestData.slug(label)
        val body = buildJsonObject {
            put("name", "E2E $label ${E2eConfig.runId}")
            put("slug", slug)
            put("creationToken", creationToken)
        }
        // Creating an organization shares the backend's credential rate-limit bucket.
        val org = RateLimit.backoff { owner.post("/api/organizations", body) }.expectStatus(201).json()
        return Tenant(orgId = org.string("id"), slug = slug, owner = owner)
    }

    /**
     * Archives the organization, which hides it and its data from every member.
     * There is no hard delete; disposable databases (CI, `e2e-env.sh down -v`)
     * remove the rows themselves.
     */
    fun archive(tenant: Tenant) {
        // The endpoint demands the exact handle as confirmation, like the admin panel does.
        superAdmin.post(
            "/api/admin/organizations/${tenant.orgId}/archive",
            buildJsonObject { put("confirmationSlug", tenant.slug) },
        ).expectStatus(200)
    }
}
