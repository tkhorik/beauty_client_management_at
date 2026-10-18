package com.beauty.e2e.ui

import com.beauty.e2e.data.TestData
import com.beauty.e2e.data.Tenant
import com.beauty.e2e.data.Tenants
import com.beauty.e2e.support.ApiClient
import com.beauty.e2e.support.BrowserSession
import com.beauty.e2e.support.Smoke
import com.beauty.e2e.support.UiTest
import com.beauty.e2e.support.closeAll
import com.beauty.e2e.support.string
import com.beauty.e2e.ui.pages.ClientDirectoryPage
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

@UiTest
@Smoke
class ClientDirectoryUiTest {
    private lateinit var tenants: Tenants
    private lateinit var salon: Tenant

    @BeforeAll
    fun provision(api: ApiClient) {
        tenants = Tenants(api)
        salon = tenants.provision("ui-salon")
    }

    @AfterAll
    fun cleanUp() {
        // Archiving hides the organization and every client created through the UI.
        if (::salon.isInitialized) closeAll(AutoCloseable { tenants.archive(salon) })
    }

    @Test
    fun `an organization admin creates a client from the directory`(context: BrowserContext, page: Page) {
        val name = TestData.personName("Walk-in")
        val phone = TestData.phone()
        BrowserSession.signIn(context, salon.owner.credentials)

        val directory = ClientDirectoryPage(page).open().expectEmpty()
        directory.openNewClient().create(name, phone)

        assertThat(directory.clientCard(name)).isVisible()

        // The UI's word is not proof of persistence: the app falls back to
        // localStorage on network errors, by design. Confirm the server has it.
        val stored = salon.owner.get("/api/clients", salon.orgId).expectStatus(200)
            .jsonElement().jsonArray.map { it.jsonObject }
        assertThat(stored.map { it.string("name") to it.string("phone") }).containsExactly(name to phone)
    }
}
