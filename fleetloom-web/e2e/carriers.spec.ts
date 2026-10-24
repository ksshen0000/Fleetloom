import { ADMIN, expect, loginAs, test } from './session'

test('goods are recorded on a carrier and found by SKU', async ({ page }) => {
  const token = await loginAs(page, ADMIN)
  const headers = { Authorization: `Bearer ${token}` }
  const sku = `E2E-${Date.now() % 100000}`

  await page.goto('/carriers')
  await page.getByRole('row', { name: /^S1/ }).getByRole('button', { name: '货物' }).click()
  const drawer = page.locator('.el-drawer')
  await drawer.getByRole('button', { name: '添加一行' }).click()
  const line = drawer.getByTestId('goods-lines').locator('.el-table__row').last()
  await line.locator('input').nth(0).fill(sku)
  await line.locator('input').nth(1).fill('螺栓 M8')
  await drawer.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('已保存')).toBeVisible()
  await page.keyboard.press('Escape')

  try {
    await page.getByPlaceholder('按 SKU 查找货物').fill(sku)
    await page.getByRole('button', { name: '查找' }).click()
    const found = page.getByTestId('goods-found')
    await expect(found.getByRole('row', { name: new RegExp(sku) })).toContainText('S1')
    await expect(found.getByRole('row', { name: new RegExp(sku) })).toContainText('螺栓 M8')
  } finally {
    const goods = (await (await page.request.get('/api/resource/v1/carriers/S1/goods', { headers })).json()).data
    await page.request.put('/api/resource/v1/carriers/S1/goods', { headers, data: goods.filter((g: { sku: string }) => g.sku !== sku) })
  }
})
