import { test as anonymous } from '@playwright/test'
import { ADMIN, expect, loginAs, test } from './session'

anonymous('pages need a login and come back after it', async ({ page }) => {
  await page.goto('/orders')
  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)orders/)

  await page.getByLabel('用户名').fill(ADMIN.username)
  await page.getByLabel('密码').fill('wrong-password')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByText('用户名或密码错误')).toBeVisible()

  await page.getByLabel('密码').fill(ADMIN.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/orders$/)
  await expect(page.getByText('新建订单')).toBeVisible()
})

anonymous('an expired or forged session sends the user back to login', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('fleetloom.session', JSON.stringify({
    token: 'forged', expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    user: { username: 'x', displayName: 'X', role: 'ADMIN', enabled: true, createdAt: '' },
  })))
  await page.goto('/orders')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByText('登录已过期')).toBeVisible()
})

test('logout ends the session', async ({ page }) => {
  await page.goto('/monitor')
  await page.locator('.user').click()
  await page.getByText('退出登录').click()
  await expect(page).toHaveURL(/\/login/)
  await page.goto('/monitor')
  await expect(page).toHaveURL(/\/login/)
})

test('the push channel connects', async ({ page }) => {
  await page.goto('/monitor')
  await expect(page.getByTestId('push-status')).toHaveText('实时')
})

test('pushed vehicle states reach the monitor', async ({ page }) => {
  const frames: string[] = []
  page.on('websocket', (ws) => ws.on('framereceived', (f) => frames.push(String(f.payload))))
  await page.goto('/monitor')
  await expect.poll(() => frames.some((f) => f.includes('"VehicleStateUpdated"')), { timeout: 10_000 }).toBe(true)
})

test('administrators manage users; viewers only look', async ({ page, browser }) => {
  const name = `e2e${Date.now() % 100000}`
  await page.goto('/users')
  await page.getByRole('button', { name: '新建用户' }).click()
  const dialog = page.getByRole('dialog', { name: '新建用户' })
  await dialog.getByPlaceholder('字母、数字、_ . -').fill(name)
  await dialog.locator('.el-form-item', { hasText: '姓名' }).locator('input').fill('E2E 只读')
  await dialog.getByPlaceholder('至少 8 位').fill('viewer-pass-1')
  await dialog.getByText('只读', { exact: true }).click()
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByRole('cell', { name, exact: true })).toBeVisible()

  const viewer = await browser.newPage()
  await loginAs(viewer, { username: name, password: 'viewer-pass-1' })
  await viewer.goto('/users')
  await expect(viewer).toHaveURL(/\/monitor$/)
  await expect(viewer.locator('.el-menu').getByText('用户')).toHaveCount(0)
  const denied = await viewer.request.post('/api/dispatch/v1/orders', {
    data: {},
    headers: { Authorization: `Bearer ${await viewer.evaluate(() => JSON.parse(localStorage.getItem('fleetloom.session')!).token)}` },
  })
  expect(denied.status()).toBe(403)
  await viewer.close()

  const row = page.getByRole('row', { name: new RegExp(name) })
  await row.getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: /确定|OK/ }).click()
  await expect(page.getByRole('cell', { name, exact: true })).toHaveCount(0)
})

test('changes are recorded in the audit log', async ({ page }) => {
  await page.goto('/audit')
  const session = await page.evaluate(() => JSON.parse(localStorage.getItem('fleetloom.session')!))
  const done = await page.request.put('/api/simulator/v1/time-scale', {
    data: { value: 1 },
    headers: { Authorization: `Bearer ${session.token}` },
  })
  expect(done.ok()).toBe(true)
  await expect.poll(async () => {
    await page.getByRole('button', { name: '刷新' }).click()
    return page.getByRole('row', { name: /\/api\/simulator\/v1\/time-scale/ }).count()
  }, { timeout: 10_000 }).toBeGreaterThan(0)
  await expect(page.getByRole('row', { name: /\/api\/simulator\/v1\/time-scale/ }).first()).toContainText(ADMIN.username)
})
