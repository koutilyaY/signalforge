import { expect, test } from "@playwright/test";

/**
 * The critical user journey, end to end through a real backend:
 *
 *   register organization → sign in → register service → see it listed
 *
 * plus the incident lifecycle when an incident exists.
 *
 * Every tenant is created fresh with a timestamped slug. Sharing a fixture
 * tenant across runs would make the rate limiter and the incident list carry
 * state between runs, which is how E2E suites become mysteriously flaky.
 */

const API = process.env.SF_API_URL || "http://localhost:8099";
const PASSWORD = "playwright-correct-horse-battery";

function freshSlug() {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
}

test.describe("first-run and service registration", () => {
  test("an operator can create an organization, sign in and register a service", async ({
    page,
  }) => {
    const slug = freshSlug();

    await page.goto("/login");
    await expect(page.getByRole("heading", { name: /SignalForge/i })).toBeVisible();

    // Switch to the organization bootstrap form.
    await page.getByRole("button", { name: /create an organization/i }).click();

    await page.getByLabel("Organization name").fill(`E2E ${slug}`);
    await page.getByLabel("Slug").fill(slug);
    await page.getByLabel("Your name").fill("E2E Operator");
    await page.getByLabel("Email").fill(`admin@${slug}.test`);
    await page.getByLabel("Password").fill(PASSWORD);

    await page.getByRole("button", { name: /create organization/i }).click();

    // Lands on the dashboard, authenticated.
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();

    // A brand new tenant has no services and the empty state must say so
    // rather than rendering an empty table.
    await expect(page.getByText(/No services registered yet/i)).toBeVisible();

    // Register a service through the API, then confirm the UI reflects it.
    const token = await page.evaluate(() => sessionStorage.getItem("sf.accessToken"));
    expect(token).toBeTruthy();

    const created = await page.request.post(`${API}/api/v1/services`, {
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      data: {
        name: "checkout-service",
        environment: "PRODUCTION",
        criticality: "CRITICAL",
        expectedP95LatencyMs: 500,
        expectedErrorRate: 0.01,
      },
    });
    expect(created.status()).toBe(201);

    await page.goto("/services");
    await expect(page.getByRole("link", { name: "checkout-service" })).toBeVisible();
    // A service with no telemetry is UNKNOWN, not HEALTHY - showing it green
    // would be a lie during an outage where it stopped reporting.
    await expect(page.getByText("UNKNOWN")).toBeVisible();
  });

  test("signing out clears the session and protects the dashboard", async ({ page }) => {
    const slug = freshSlug();

    await page.goto("/login");
    await page.getByRole("button", { name: /create an organization/i }).click();
    await page.getByLabel("Organization name").fill(`E2E ${slug}`);
    await page.getByLabel("Slug").fill(slug);
    await page.getByLabel("Your name").fill("E2E Operator");
    await page.getByLabel("Email").fill(`admin@${slug}.test`);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: /create organization/i }).click();
    await expect(page).toHaveURL(/\/dashboard/);

    await page.getByRole("button", { name: /sign out/i }).click();
    await expect(page).toHaveURL(/\/login/);

    // Navigating straight back must bounce, not render a stale shell.
    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe("incident lifecycle", () => {
  test("an engineer can acknowledge and resolve an incident from the UI", async ({ page }) => {
    const slug = freshSlug();

    // Bootstrap via the API - this test is about the incident UI, not signup.
    const registration = await page.request.post(`${API}/api/v1/auth/register-organization`, {
      data: {
        organizationName: `E2E ${slug}`,
        organizationSlug: slug,
        adminEmail: `admin@${slug}.test`,
        adminFullName: "E2E Operator",
        adminPassword: PASSWORD,
      },
    });
    expect(registration.status()).toBe(201);
    const body = await registration.json();
    const token: string = body.accessToken;
    const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

    const service = await page.request.post(`${API}/api/v1/services`, {
      headers: auth,
      data: { name: "payment-service", environment: "PRODUCTION", criticality: "HIGH" },
    });
    const serviceId = (await service.json()).id;

    // Drive a breach: 60 failing requests against a 1% SLA.
    const events = Array.from({ length: 60 }, (_, i) => ({
      eventId: crypto.randomUUID(),
      serviceId,
      occurredAt: new Date().toISOString(),
      eventType: "DATABASE_ERROR",
      errorType: "ConnectionPoolExhausted",
      errorMessage: "pool exhausted",
      traceId: `trace-${i}`,
    }));
    const ingest = await page.request.post(`${API}/api/v1/ingest/events`, {
      headers: auth,
      data: { events },
    });
    expect(ingest.status()).toBe(202);

    // Seed the session the way the app does, then open the incidents page.
    await page.goto("/login");
    await page.evaluate(
      ([t, u]) => {
        sessionStorage.setItem("sf.accessToken", t as string);
        sessionStorage.setItem("sf.refreshToken", t as string);
        sessionStorage.setItem("sf.user", u as string);
      },
      [token, JSON.stringify(body.user)],
    );

    // Detection runs on a 15s sweep, so poll rather than sleep a fixed amount.
    await expect(async () => {
      const list = await page.request.get(`${API}/api/v1/incidents`, { headers: auth });
      const incidents = await list.json();
      expect(incidents.length).toBeGreaterThan(0);
    }).toPass({ timeout: 90_000 });

    await page.goto("/incidents");
    const firstIncident = page.locator('a[href^="/incidents/"]').first();
    await expect(firstIncident).toBeVisible();
    await firstIncident.click();

    await expect(page.getByText("Detection latency")).toBeVisible();
    await expect(page.getByText(/Likely contributing factors/i)).toBeVisible();

    // Acknowledge, then resolve, asserting the status badge follows.
    await page.getByRole("button", { name: /^Acknowledge$/ }).click();
    await expect(page.getByText("ACKNOWLEDGED")).toBeVisible();

    await page.getByRole("button", { name: /Mark resolved/i }).click();
    await expect(page.getByText("RESOLVED")).toBeVisible();

    // A resolved incident is terminal - no further transitions offered.
    await expect(page.getByRole("button", { name: /Mark resolved/i })).toHaveCount(0);
  });
});
