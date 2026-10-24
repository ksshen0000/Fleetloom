import { expect, test } from './session'

test('the message log shows what a vehicle reported', async ({ page }) => {
  await page.goto('/vehicles')
  const row = page.getByRole('row', { name: /AGV-001/ }).first()
  await row.getByRole('button', { name: '报文' }).click()

  const drawer = page.locator('.el-drawer')
  await expect(drawer).toContainText('AGV-001 报文日志')
  const log = drawer.getByTestId('vehicle-messages')
  await expect(log.getByRole('row', { name: /上报.*状态/ }).first()).toBeVisible()

  await drawer.locator('.el-select').click()
  await page.getByRole('option', { name: '连接' }).click()
  await expect(log.getByRole('row', { name: /状态/ })).toHaveCount(0)
})

test('the history shows where a vehicle went and its battery', async ({ page }) => {
  await page.goto('/vehicles')
  await page.getByRole('row', { name: /AGV-001/ }).first().getByRole('button', { name: '历史' }).click()

  const dialog = page.getByRole('dialog', { name: /AGV-001 历史轨迹与电量/ })
  // a state is sampled at least once a minute; the first one right after the service starts
  await expect(dialog.getByTestId('history-stats')).toContainText('个采样', { timeout: 15_000 })
  await dialog.locator('svg.trajectory').hover()
  await expect(dialog).toContainText('电量 ')
  await page.screenshot({ path: 'test-results/vehicle-history.png' })
})
