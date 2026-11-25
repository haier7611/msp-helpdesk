/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In dev, Vite proxies the API like Caddy does in production (see web/Caddyfile).
const apiTarget = process.env.TICKETSYS_API_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": apiTarget,
      "/actuator/health": apiTarget,
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    // Whole-app render tests (route tree + axe) take several seconds when the CI host runs parallel
    // builds; 5 s timed out under load average 12+.
    testTimeout: 15000,
  },
});
