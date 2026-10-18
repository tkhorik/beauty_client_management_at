plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "com.beauty"
version = "0.1.0"

repositories {
    mavenCentral()
}

// One place for every pinned version. Playwright's version also decides which
// browser builds `installBrowsers` downloads, so CI caches them keyed on it.
val playwrightVersion = "1.63.0"
val junitVersion = "5.14.4"
val assertjVersion = "3.27.7"
val serializationVersion = "1.11.0"

kotlin {
    // Same baseline as the backend and Android builds.
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("com.microsoft.playwright:playwright:$playwrightVersion")
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
}

/** Comma-separated `-P` value as a list, e.g. `-PincludeTags=smoke,api`. */
fun listProperty(name: String): List<String> =
    (findProperty(name) as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeTags(*listProperty("includeTags").toTypedArray())
        excludeTags(*listProperty("excludeTags").toTypedArray())
    }
    // Playwright otherwise downloads every browser on first use, silently and
    // mid-test. Browsers are installed explicitly (`installBrowsers`), so a
    // missing one fails fast instead.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    // The suite exercises an external environment, so a green result from an
    // earlier run says nothing about the app as it is now.
    outputs.upToDateWhen { false }

    // `-Pe2e.browser=firefox` etc. become system properties; environment
    // variables (E2E_*) are inherited by the test JVM as they are.
    listOf("e2e.webUrl", "e2e.apiUrl", "e2e.mailpitUrl", "e2e.browser", "e2e.headless", "e2e.slowMoMs")
        .forEach { key -> findProperty(key)?.let { systemProperty(key, it) } }

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

val testSourceSet = sourceSets.test.get()

fun registerTaggedTest(name: String, tag: String, summary: String) =
    tasks.register<Test>(name) {
        description = summary
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags(tag) }
    }

registerTaggedTest("apiTest", "api", "Runs the real-HTTP API tests.")
registerTaggedTest("uiTest", "ui", "Runs the browser E2E tests.")
registerTaggedTest("smokeTest", "smoke", "Runs the pull-request smoke subset.")

// Downloads the browser build that matches the pinned Playwright version.
//   ./gradlew installBrowsers                       # chromium
//   ./gradlew installBrowsers -Pbrowsers=chromium,firefox -PwithDeps   # CI (Linux)
tasks.register<JavaExec>("installBrowsers") {
    description = "Installs Playwright browsers (and, with -PwithDeps, their OS packages)."
    group = "e2e"
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    val browsers = listProperty("browsers").ifEmpty { listOf("chromium") }
    args(listOf("install") + (if (hasProperty("withDeps")) listOf("--with-deps") else emptyList()) + browsers)
}

// Opens a saved failure trace in Playwright's trace viewer:
//   ./gradlew showTrace -Ptrace=build/e2e-artifacts/<Class>/<test>/trace.zip
tasks.register<JavaExec>("showTrace") {
    description = "Opens a Playwright trace (-Ptrace=path/to/trace.zip)."
    group = "e2e"
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("show-trace", (findProperty("trace") as String?) ?: "")
}
