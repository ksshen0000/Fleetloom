import { ADMIN, expect, loginAs, test } from './session'

test('virtual vehicles are created, come online and are removed again', async ({ page }) => {
  const token = await loginAs(page, ADMIN)
  const headers = { Authorization: `Bearer ${token}` }
  const prefix = `E2E${Date.now() % 100000}`
  const serial = `${prefix}-001`

  await page.goto('/simulator')
  await page.getByRole('button', { name: '新增虚拟车' }).click()
  const dialog = page.getByRole('dialog', { name: '新增虚拟车' })
  await dialog.getByLabel('编号前缀').fill(prefix)
  await dialog.getByLabel('数量').fill('1')
  await expect(dialog.getByTestId('sim-plan')).toContainText(`${serial} @ P`)
  await dialog.getByRole('button', { name: '创建' }).click()

  await expect(page.getByRole('row', { name: new RegExp(serial) })).toBeVisible()
  // fleet control sees it, and the registry knows it
  await page.goto('/vehicles')
  const row = page.getByRole('row', { name: new RegExp(serial) }).first()
  await expect(row).toContainText('ONLINE')
  await expect(row).not.toContainText('未登记')

  await page.goto('/simulator')
  await page.getByRole('row', { name: new RegExp(serial) }).getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByRole('row', { name: new RegExp(serial) })).toHaveCount(0)

  await expect.poll(async () => {
    const live = (await (await page.request.get('/api/fleet/v1/vehicles', { headers })).json()).data
    return live.some((v: { code: string }) => v.code === serial)
  }, { timeout: 10_000 }).toBe(false)
  expect((await page.request.delete(`/api/resource/v1/vehicles/${serial}`, { headers })).ok()).toBe(true)
})

test('a scenario runs from the simulator page and reports its result', async ({ page }) => {
  await page.goto('/simulator')
  await page.getByTestId('scenario-yaml').fill('name: e2e-scenario\norders:\n  - {type: MOVE, targetPoints: [P11]}\n')
  await page.getByRole('button', { name: '开始' }).click()

  const report = page.getByTestId('scenario-report')
  await expect(report).toContainText('e2e-scenario')
  await expect(report).toContainText('已完成', { timeout: 60_000 })
  await expect(report).toContainText('1 / 1')
})

test('a warehouse scenario takes goods in and ships them again', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/simulator')
  await page.getByTestId('scenario-preset').click()
  await page.getByRole('option', { name: '仓库作业：入库 / 拣选 / 出库 / 移库' }).click()
  await expect(page.getByTestId('scenario-yaml')).toHaveValue(/name: warehouse-shift/)

  // a shelf of a SKU nobody else has arrives at a dock and is stored; then it is shipped
  const sku = `SKU-E2E-${Date.now()}`
  await page.getByTestId('scenario-yaml').fill(`name: e2e-warehouse
timeScale: 4
orders:
  - {name: inbound, type: TRANSPORT, targetZone: STORE, arrive: {zone: IN, carrierType: SHELF, prefix: E2E, goods: [${sku}]}}
  - {name: outbound, start: 1s, type: TRANSPORT, fromZone: STORE, sku: ${sku}, targetZone: OUT, depart: 1s, maxWait: 3m}
`)
  await page.getByRole('button', { name: '开始' }).click()

  const report = page.getByTestId('scenario-report')
  await expect(report).toContainText('e2e-warehouse')
  await expect(report).toContainText('已完成', { timeout: 220_000 })
  const streams = page.getByTestId('scenario-streams')
  await expect(streams.getByRole('row', { name: /inbound/ })).toContainText('1 / 1')
  await expect(streams.getByRole('row', { name: /outbound/ })).toContainText('1 / 1')
  await expect(report).not.toContainText('rejected')
})
