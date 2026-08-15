import { defineConfig, devices } from "@playwright/test";

/**
 * E2E configuration.
 *
 * Assumes the backend is already running on 8099 (docker compose up, or
 * `mvn spring-boot:run`). Playwright starts the frontend itself so a developer
 * does not have to remember to.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false, // these tests create real tenants; keep the ordering deterministic
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : [["list"]],
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env.SF_UI_URL || "http://localhost:3010",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },

  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],

  webServer: {
    command: "npm run dev",
    url: "http://localhost:3010",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
