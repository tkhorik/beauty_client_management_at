# beauty_client_management_at

Kotlin-based UI E2E and REST API acceptance tests for
[Beauty Client Management](https://github.com/tkhorik/beauty_client_management), using
Playwright for Java, JUnit 5 and Gradle.

The suite has no build or code dependency on the app. It talks to a running
stack over HTTP and through a real browser, so it tests what users and clients
actually get: real Postgres, the web app's `/api` proxy, httpOnly refresh cookies
and organization isolation enforced by the deployed routes.

## Requirements

- JDK 17. Gradle runs the suite on a JDK 17 toolchain, and the app's backend build needs 17.
- Docker with `docker compose` or `docker-compose` (on macOS, Colima works: `colima start`).
- Node (the version the app's CI uses), plus `jq`, `curl` and `openssl`.
- An app checkout. The default is `../beauty_client_management`; override it with `E2E_APP_DIR`.

## Quick start

```bash
scripts/e2e-env.sh up          # builds the app, starts everything, bootstraps a super admin
./gradlew installBrowsers      # once per Playwright version
./gradlew test                 # everything
scripts/e2e-env.sh down        # stop (keep DB);  `down -v` also deletes the DB
```

| Command | Runs |
|---|---|
| `./gradlew test` | all tests |
| `./gradlew apiTest` / `uiTest` / `smokeTest` | tests tagged `api` / `ui` / `smoke` |
| `./gradlew test -PincludeTags=smoke -PexcludeTags=ui` | any tag filter |
| `./gradlew test -Pe2e.headless=false -Pe2e.slowMoMs=250` | watch the browser |
| `./gradlew test -Pe2e.browser=firefox` | another engine (`installBrowsers -Pbrowsers=firefox` first) |
| `./gradlew showTrace -Ptrace=<path>/trace.zip` | open a failure trace |
| `E2E_SKIP_BUILD=1 scripts/e2e-env.sh up` | restart without rebuilding the app |

## What `scripts/e2e-env.sh up` does

1. Starts **Postgres 15** (the production version) and **Mailpit** (an SMTP catcher with an HTTP API) from `docker-compose.e2e.yml`. Both bind to localhost only.
2. Builds and starts the backend from the app checkout, using `APP_ENV=development`, real Postgres and **`DB_ALLOW_H2_FALLBACK=false`**. An unreachable database stops the backend from starting, so it can never quietly run on in-memory H2.
3. Builds the web app and serves it with `vite preview` on `:5174`. That's the dev CORS / `SITE_URL` origin, and its `/api` proxy points at the backend.
4. **Bootstraps a super admin through real endpoints only.** Organizations need a creation token from a SUPER_ADMIN, and the backend promotes an address in `SUPER_ADMIN_EMAILS` only if it is verified *at startup*. The script registers the account, reads the verification mail from Mailpit, redeems the token, and restarts the backend.
5. Writes `.e2e/e2e.env` (git-ignored, mode 600), which holds the URLs and the generated super-admin password the tests read.

Ports: `E2E_API_PORT` (8080), `E2E_WEB_PORT` (5174), `E2E_DB_PORT` (55432),
`E2E_SMTP_PORT` (1025), `E2E_MAILPIT_PORT` (8025). Stop a local dev server first,
or move the ports.

## Configuration

Each value comes from the first place it is set, in this order: system property (`-Pe2e.x=…`), then environment variable, then `.e2e/e2e.env`.

| Variable | Default |
|---|---|
| `E2E_WEB_URL` / `E2E_API_URL` / `E2E_MAILPIT_URL` | `http://127.0.0.1:5174` / `:8080` / `:8025` |
| `E2E_BROWSER` | `chromium` (`firefox`, `webkit`) |
| `E2E_HEADLESS`, `E2E_SLOW_MO_MS` | `true`, `0` |
| `E2E_SUPERADMIN_EMAIL` / `E2E_SUPERADMIN_PASSWORD` | written by the script; **no defaults in code** |

## Layout

```
src/test/kotlin/com/beauty/e2e/
  config/E2eConfig.kt          configuration lookup; Credentials never prints its password
  support/PlaywrightExtension  lifecycle + failure artifacts (the only "framework")
  support/Api.kt               ApiClient / Session over Playwright APIRequestContext
  support/BrowserSession.kt    sign a BrowserContext in via the refresh cookie
  support/Environment.kt       one fail-fast reachability probe per run
  support/Tags.kt              @ApiTest, @UiTest, @Smoke
  data/TestData.kt             unique, valid test data (run id + counter)
  data/Tenants.kt              provision / archive an organization per test class
  api/                         real-HTTP API tests
  ui/  ui/pages/               browser tests and page objects
```

## Design rules

- **Ownership:** each test class gets its own `Playwright`, `Browser` and `ApiClient`, created lazily, so an API class never launches a browser. Each test gets a fresh `BrowserContext` and `Page`. Everything is closed by `PlaywrightExtension`, including when setup fails. Playwright objects are never shared across threads and never touched from coroutines.
- **Auth:** API tests use the backend's body transport, with a bearer token per `Session`. Browser tests log in through `context.request()` with `X-Auth-Transport: cookie`, so the httpOnly refresh cookie lands in that context. **Never reuse `storageState` across contexts:** refresh tokens are single-use, and the backend revokes the whole session family when a spent one is replayed.
- **Data:** every test class provisions its own organization, and every email, slug and name is unique per run. There is no API to delete users, so independence comes from uniqueness. Cleanup deletes created clients and archives organizations. CI throws the database away with `down -v`.
- **Rate limit:** the backend allows 10 credential calls (register, login, create organization) per minute per IP, and the limit is hardcoded. The suite signs the super admin in once per class. On a 429 it waits for exactly `Retry-After`, within a budget, and never retries any other status. Back-to-back local runs may pause for up to a minute.
- **Locators:** role and label first, against the English catalog (the context locale is `en-US`). Assertions on the page use Playwright's auto-retrying `assertThat`, and API payloads use AssertJ. No sleeps, no blanket retries.
- **Failure artifacts:** `build/e2e-artifacts/<Class>/<test>-<id>/` holds `screenshot.png`, `trace.zip` and `diagnostics.log`. The log records console messages, page errors, HTTP 4xx/5xx, failed requests and any `alert()` text, with token query parameters redacted. These are written before the context closes and linked from the JUnit XML report. Traces can contain request bodies from throwaway accounts, so they are only kept for failed tests.

## Parallelism and more browsers (later)

Tests run sequentially for now. `src/test/resources/junit-platform.properties`
documents the only safe parallel shape for Playwright: classes run concurrently,
and methods stay on their class's thread (`same_thread`). Use a small fixed pool,
because the per-IP credential rate limit is the real ceiling. For more browsers,
run `installBrowsers -Pbrowsers=firefox,webkit`, then `-Pe2e.browser=…` (a CI
matrix entry per browser).

## CI

`.github/workflows/e2e.yml` checks out the app (the `main` branch, or `app_ref` on a manual
run, or the `E2E_APP_REF` repository variable when set) and runs `scripts/e2e-env.sh up`. Pull requests and pushes run the `smoke` tag,
the nightly run executes everything, and failure artifacts are uploaded for 7 days.
The app repo's own CI is unchanged.
