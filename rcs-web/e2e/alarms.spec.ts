import { ADMIN, expect, loginAs, test } from './session'

/**
 * The whole alarm chain: a fault injected into a simulated vehicle reaches fleet over VDA 5050, becomes
 * an alarm in rcs-resource and is pushed through the gateway to the open page.
 */
test('a vehicle fault shows up as an alarm and clears with the fault', async ({ page }) => {
  const token = await loginAs(page, ADMIN)
  const auth = { Authorization: `Bearer ${token}` }
  const errorType = `e2eFault${Date.now() % 100000}`

  await page.goto('/alarms')
  await expect(page.getByTestId('push-status')).toHaveText('实时')

  const injected = await page.request.post('/api/simulator/v1/vehicles/AGV-001/faults/error', {
    headers: auth,
    data: { errorType, level: 'FATAL', description: 'injected by e2e' },
  })
  expect(injected.ok()).toBe(true)
  try {
    const faultRow = new RegExp(`车辆故障.*${errorType}`)
    const row = page.getByRole('row', { name: faultRow })
    await expect(row).toBeVisible({ timeout: 10_000 })
    await expect(row).toContainText('严重')
    await expect(row).toContainText('AGV-001')
    await expect(page.getByTestId('alarm-badge')).toBeVisible()
    await expect(page.locator('.el-notification').filter({ hasText: errorType })).toBeVisible()

    await row.getByRole('button', { name: '确认' }).click()
    await expect(row).toContainText('已确认')
    await expect(row).toContainText(`${ADMIN.username} 确认于`)
  } finally {
    await page.request.delete('/api/simulator/v1/vehicles/AGV-001/faults', { headers: auth })
    // a FATAL error suspends the order the vehicle was working on; let it carry on
    const orders = (await (await page.request.get('/api/dispatch/v1/orders?status=SUSPENDED', { headers: auth })).json()).data
    for (const o of orders.filter((o: { reason: string | null }) => o.reason?.includes(errorType))) {
      await page.request.post(`/api/dispatch/v1/orders/${o.orderNo}/resume`, { headers: auth })
    }
  }
  // the vehicle stops reporting the error, so the alarm closes by itself
  const faultRow = new RegExp(`车辆故障.*${errorType}`)
  await expect(page.getByRole('row', { name: faultRow })).toHaveCount(0, { timeout: 10_000 })
  await page.locator('.el-radio-button', { hasText: '已关闭' }).click()
  await expect(page.getByRole('row', { name: faultRow })).toContainText('自动恢复于')
})
