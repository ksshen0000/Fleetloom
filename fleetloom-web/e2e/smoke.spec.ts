import { expect, test } from './session'

const pages = [
  { path: '/monitor', marker: /车辆（\d+）/ },
  { path: '/orders', marker: '新建订单' },
  { path: '/vehicles', marker: '实时状态' },
  { path: '/maps', marker: '新建地图' },
  { path: '/storage', marker: '新建库区' },
  { path: '/carriers', marker: '新建载具' },
  { path: '/users', marker: '新建用户' },
  { path: '/alarms', marker: '未关闭' },
  { path: '/dashboard', marker: '车辆利用率' },
  { path: '/audit', marker: '只看失败' },
  { path: '/settings', marker: '保存交通参数' },
  { path: '/simulator', marker: '时间倍率' },
]

for (const { path, marker } of pages) {
  test(`${path} loads without errors`, async ({ page }) => {
    const problems: string[] = []
    page.on('pageerror', (e) => problems.push(`page error: ${e.message}`))
    page.on('console', (m) => m.type() === 'error' && problems.push(`console: ${m.text()}`))
    page.on('response', (r) => r.status() >= 400 && problems.push(`HTTP ${r.status()} ${r.url()}`))

    await page.goto(path)
    await expect(page.getByText(marker).first()).toBeVisible()
    await page.waitForTimeout(1500)

    expect(problems).toEqual([])
  })
}

test('monitor draws the map and the vehicles', async ({ page }) => {
  await page.goto('/monitor')
  await expect(page.locator('.map-canvas canvas').first()).toBeVisible()
  await expect(page.locator('.vehicle-card').first()).toBeVisible()
})
