package com.beauty.e2e.ui.pages

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole

/** The signed-in home screen: header actions plus the client card grid. */
class ClientDirectoryPage(private val page: Page) {
    private val newClientButton: Locator =
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("New Client Profile"))

    /**
     * Opens the app and waits until it is signed in *and* inside an
     * organization — the header's "New Client Profile" action only renders then.
     */
    fun open(): ClientDirectoryPage {
        page.navigate("/")
        assertThat(newClientButton).isVisible()
        return this
    }

    fun expectEmpty(): ClientDirectoryPage {
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("No Client Profiles Found"))).isVisible()
        return this
    }

    fun openNewClient(): NewClientDialog {
        newClientButton.click()
        return NewClientDialog(page).also { it.expectOpen() }
    }

    /** Each card exposes one labelled button covering the card. */
    fun clientCard(name: String): Locator =
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open client: $name").setExact(true))
}

/** "Create New Client Profile" modal. */
class NewClientDialog(page: Page) {
    private val root: Locator =
        page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Create New Client Profile"))

    fun expectOpen() = assertThat(root).isVisible()

    /** Fills the required fields, submits, and waits for the modal to close on success. */
    fun create(name: String, phone: String) {
        root.getByLabel("Full Name").fill(name)
        root.getByLabel("Phone Number").fill(phone)
        root.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Create Client Profile").setExact(true)).click()
        // A refused save leaves the modal open (and raises an alert, logged in
        // diagnostics.log), so this is also the failure signal.
        assertThat(root).isHidden()
    }
}
