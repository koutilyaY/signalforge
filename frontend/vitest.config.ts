import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // jsdom, because the code under test reaches for sessionStorage and
    // ReadableStream. Neither is worth abstracting behind an interface purely
    // to keep tests in node.
    environment: "jsdom",
    globals: true,
    include: ["lib/**/*.test.ts", "components/**/*.test.ts"],
    // The Playwright specs in e2e/ are driven by `npm run test:e2e`. Vitest
    // would otherwise collect them and fail on the @playwright/test import.
    exclude: ["e2e/**", "node_modules/**", ".next/**"],
  },
});
