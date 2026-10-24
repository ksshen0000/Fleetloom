import { defineConfig } from '@playwright/test'

/**
 * End-to-end smoke tests against a running stack behind fleetloom-gateway (deploy/dev-stack.sh start).
 * The dev server is started if it is not already running.
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  use: { baseURL: 'http://127.0.0.1:5173', viewport: { width: 1440, height: 860 } },
  webServer: {
    command: 'corepack pnpm dev --host 127.0.0.1',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
  },
})
