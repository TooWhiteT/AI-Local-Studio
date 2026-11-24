import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',
  timeout: 60000,
  workers: 1,
  globalSetup: './tests/build-backend.mjs',
  use: {
    browserName: 'chromium',
    channel: process.env.STUDIO_BROWSER_CHANNEL,
    viewport: { width: 1280, height: 800 },
  },
})
