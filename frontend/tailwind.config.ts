import type { Config } from "tailwindcss";

export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Severity palette. Defined once so a CRITICAL badge cannot drift to a
        // different red in a different component.
        sev: {
          low: "#3b82f6",
          medium: "#eab308",
          high: "#f97316",
          critical: "#dc2626",
        },
      },
    },
  },
  plugins: [],
} satisfies Config;
