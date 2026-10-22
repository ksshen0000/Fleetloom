import { test as base, type Page } from '@playwright/test'

/** The initial administrator from rcs-resource's defaults (rcs.security.initial-admin-password). */
export const ADMIN = { username: process.env.E2E_USER ?? 'admin', password: process.env.E2E_PASSWORD ?? 'admin12345' }

/** Logs in through the API and puts the session where the app looks for it, before any page script runs. */
export async function loginAs(page: Page, user: { username: string; password: string }) {
  const response = await page.request.post('/api/resource/v1/auth/login', { data: user })
  if (!response.ok()) {
    throw new Error(`login as ${user.username} failed: ${response.status()} ${await response.text()}`)
  }
  const session = (await response.json()).data
  // once per tab, so that logging out in the test sticks
  await page.addInitScript((s) => {
    if (!sessionStorage.getItem('e2e.seeded')) {
      localStorage.setItem('rcs.session', s)
      sessionStorage.setItem('e2e.seeded', '1')
    }
  }, JSON.stringify(session))
  return session.token as string
}

/** A page already logged in as the administrator. */
export const test = base.extend({
  page: async ({ page }, use) => {
    await loginAs(page, ADMIN)
    await use(page)
  },
})

export { expect } from '@playwright/test'
