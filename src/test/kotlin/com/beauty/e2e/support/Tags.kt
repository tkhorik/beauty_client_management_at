package com.beauty.e2e.support

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

/**
 * A real-HTTP API test class. Gets [PlaywrightExtension] for its `ApiClient`
 * but never launches a browser unless a test asks for a `Page`.
 *
 * `PER_CLASS` lets `@BeforeAll`/`@AfterAll` be ordinary member functions that
 * provision and archive the class's tenant.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("api")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PlaywrightExtension::class)
annotation class ApiTest

/** A browser E2E test class: a fresh `BrowserContext` and `Page` per test. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PlaywrightExtension::class)
annotation class UiTest

/** Part of the pull-request smoke subset (`./gradlew smokeTest`). */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("smoke")
annotation class Smoke
