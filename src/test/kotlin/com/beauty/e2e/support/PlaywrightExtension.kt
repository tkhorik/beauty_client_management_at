package com.beauty.e2e.support

import com.beauty.e2e.config.E2eConfig
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.PlaywrightAssertions
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections

/**
 * Owns every Playwright object a test class touches, and nothing else.
 *
 * Playwright Java is not thread-safe: an object may only be used on the thread
 * that created its `Playwright`. Ownership therefore follows JUnit's threads:
 *
 *  - **per class** — `Playwright`, `Browser` and the `ApiClient`, created lazily
 *    on first use (an API-only class never launches a browser) and closed in
 *    `afterAll`. With the parallel settings in `junit-platform.properties`
 *    (classes concurrent, methods `same_thread`), a class's tests all run on the
 *    thread that created these.
 *  - **per test** — a fresh `BrowserContext` + `Page`, with tracing on, closed in
 *    `afterEach` *after* failure artifacts are written.
 *
 * Inject by declaring a parameter of type [Playwright], [Browser], [ApiClient]
 * (also in `@BeforeAll`), [BrowserContext] or [Page] (test methods only).
 *
 * The Playwright-supplied `@UsePlaywright` covers lifecycle but cannot keep a
 * trace or screenshot of a failed test, which is why this exists.
 */
class PlaywrightExtension : BeforeAllCallback, AfterAllCallback, AfterEachCallback, ParameterResolver {

    override fun beforeAll(context: ExtensionContext) {
        Environment.ensureReachable()
        context.getStore(NAMESPACE).put(CLASS_KEY, ClassResources())
    }

    override fun afterAll(context: ExtensionContext) {
        context.getStore(NAMESPACE).remove(CLASS_KEY, ClassResources::class.java)?.close()
    }

    override fun afterEach(context: ExtensionContext) {
        val browser = context.getStore(NAMESPACE).remove(TEST_KEY, TestBrowser::class.java) ?: return
        val saved = browser.finish(context.executionException.orElse(null), artifactDir(context))
        if (saved != null) {
            val where = saved.toAbsolutePath().toString()
            context.publishReportEntry("artifacts", where)
            System.err.println("[e2e] failure artifacts: $where")
        }
    }

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext) =
        parameter.parameter.type in SUPPORTED

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any {
        val resources = context.getStore(NAMESPACE).get(CLASS_KEY, ClassResources::class.java)
            ?: error("PlaywrightExtension is not registered on the test class")
        return when (parameter.parameter.type) {
            Playwright::class.java -> resources.playwright
            Browser::class.java -> resources.browser
            ApiClient::class.java -> resources.api
            BrowserContext::class.java -> testBrowser(context, resources).context
            Page::class.java -> testBrowser(context, resources).page
            else -> error("unsupported parameter ${parameter.parameter}")
        }
    }

    private fun testBrowser(context: ExtensionContext, resources: ClassResources): TestBrowser {
        check(context.testMethod.isPresent) {
            "BrowserContext and Page are per test; request them in a test or @BeforeEach method, not @BeforeAll"
        }
        return context.getStore(NAMESPACE)
            .getOrComputeIfAbsent(TEST_KEY, { TestBrowser.open(resources.browser) }, TestBrowser::class.java)
    }

    /** Unique per test invocation, so parallel and repeated tests never share a folder. */
    private fun artifactDir(context: ExtensionContext): Path {
        val safe = { s: String -> s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80) }
        val method = context.testMethod.map { it.name }.orElse("test")
        val id = Integer.toHexString(context.uniqueId.hashCode())
        return E2eConfig.artifactsDir
            .resolve(safe(context.requiredTestClass.simpleName))
            .resolve("${safe(method)}-$id")
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(PlaywrightExtension::class.java)
        const val CLASS_KEY = "class-resources"
        const val TEST_KEY = "test-browser"
        val SUPPORTED = setOf(
            Playwright::class.java, Browser::class.java, ApiClient::class.java,
            BrowserContext::class.java, Page::class.java,
        )

        init {
            // CI runners are slower than a laptop; 5 s (the default) flakes on cold starts.
            PlaywrightAssertions.setDefaultAssertionTimeout(10_000.0)
        }
    }
}

/** Class-scoped objects. Created on first use, closed in reverse order of creation. */
private class ClassResources : AutoCloseable {
    private var playwrightOrNull: Playwright? = null
    private var browserOrNull: Browser? = null
    private var apiOrNull: ApiClient? = null

    val playwright: Playwright
        get() = playwrightOrNull ?: Playwright.create().also { playwrightOrNull = it }

    val browser: Browser
        get() = browserOrNull ?: launch(playwright).also { browserOrNull = it }

    val api: ApiClient
        get() = apiOrNull ?: ApiClient(playwright).also { apiOrNull = it }

    override fun close() {
        closeAll(apiOrNull, browserOrNull, playwrightOrNull)
        apiOrNull = null
        browserOrNull = null
        playwrightOrNull = null
    }

    private fun launch(playwright: Playwright): Browser {
        val type = when (E2eConfig.browser) {
            "chromium" -> playwright.chromium()
            "firefox" -> playwright.firefox()
            "webkit" -> playwright.webkit()
            else -> error("E2E_BROWSER must be chromium, firefox or webkit (got '${E2eConfig.browser}')")
        }
        return type.launch(BrowserType.LaunchOptions().setHeadless(E2eConfig.headless).setSlowMo(E2eConfig.slowMoMs))
    }
}

/** One test's browser state plus the diagnostics collected while it ran. */
private class TestBrowser private constructor(val context: BrowserContext, val page: Page) {
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        page.onConsoleMessage { log("console.${it.type()}", it.text()) }
        page.onPageError { log("pageerror", it) }
        page.onRequestFailed { log("requestfailed", "${it.method()} ${redact(it.url())} ${it.failure()}") }
        page.onResponse { if (it.status() >= 400) log("http ${it.status()}", "${it.request().method()} ${redact(it.url())}") }
        // The app reports some validation through window.alert(). Record and
        // dismiss it, so the test fails on its own assertion with the message
        // in diagnostics.log instead of hanging on a dialog.
        page.onDialog { dialog ->
            log("dialog.${dialog.type()}", dialog.message())
            dialog.dismiss()
        }
    }

    private fun log(kind: String, message: String) {
        log += "${Instant.now()} [$kind] $message"
    }

    /** Saves artifacts if the test failed, then always closes the context. Returns the artifact folder, if any. */
    fun finish(failure: Throwable?, dir: Path): Path? {
        try {
            if (failure == null) {
                // Discards the in-memory trace; nothing is written for a passing test.
                runCatching { context.tracing().stop() }
                return null
            }
            Files.createDirectories(dir)
            val problems = mutableListOf<String>()
            if (!page.isClosed) {
                runCatching { page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("screenshot.png")).setFullPage(true)) }
                    .onFailure { problems += "screenshot failed: $it" }
            }
            runCatching { context.tracing().stop(Tracing.StopOptions().setPath(dir.resolve("trace.zip"))) }
                .onFailure { problems += "trace failed: $it" }
            val header = listOf(
                "failure: $failure",
                "url: ${if (page.isClosed) "(page closed)" else redact(page.url())}",
                "open the trace with: ./gradlew showTrace -Ptrace=${dir.resolve("trace.zip")}",
            )
            Files.write(dir.resolve("diagnostics.log"), header + problems + "" + log.toList())
            return dir
        } finally {
            context.close()
        }
    }

    companion object {
        fun open(browser: Browser): TestBrowser {
            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setBaseURL(E2eConfig.webUrl)
                    // The UI is translated and follows the browser language;
                    // locators below are written against the English catalog.
                    .setLocale("en-US")
                    .setViewportSize(1280, 800),
            )
            try {
                // Traces hold request bodies (passwords, tokens). They are only
                // ever written for failed tests, under build/, from throwaway accounts.
                context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
                return TestBrowser(context, context.newPage())
            } catch (e: Throwable) {
                context.close()
                throw e
            }
        }

        private val TOKEN_PARAM = Regex("""([?&](?:token|orgToken|invite)=)[^&#]+""")

        fun redact(url: String) = url.replace(TOKEN_PARAM, "$1***")
    }
}

/** Closes each resource even if an earlier one throws; rethrows the first failure. */
internal fun closeAll(vararg resources: AutoCloseable?) {
    var first: Throwable? = null
    for (resource in resources) {
        try {
            resource?.close()
        } catch (e: Throwable) {
            first?.addSuppressed(e) ?: run { first = e }
        }
    }
    first?.let { throw it }
}
