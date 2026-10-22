import { ADMIN, expect, loginAs, test } from './session'

test('a transport with the LIFT template runs to the end with lift and lower', async ({ page }) => {
  test.setTimeout(120_000)
  const token = await loginAs(page, ADMIN)
  const headers = { Authorization: `Bearer ${token}` }
  const data = async (url: string) => (await (await page.request.get(url, { headers })).json()).data

  // a carrier standing in an unlocked location, i.e. not part of another running order
  const occupied = (await data('/api/resource/v1/storage-locations?status=OCCUPIED')).map((l: { carrierCode: string }) => l.carrierCode)
  const carrier = (await data('/api/resource/v1/carriers')).find(
    (c: { code: string; status: string }) => c.status === 'IDLE' && occupied.includes(c.code),
  )
  const target = (await data('/api/resource/v1/storage-locations?status=EMPTY'))[0]
  expect(carrier, 'an idle carrier in storage (deploy/examples/setup-demo-storage.sh)').toBeTruthy()

  const created = await page.request.post('/api/dispatch/v1/orders', {
    headers,
    data: { type: 'TRANSPORT', carrierCode: carrier.code, targetLocation: target.code, template: 'LIFT' },
  })
  const order = (await created.json()).data
  expect(order.status, order.reason).not.toBe('REJECTED')
  expect(order.legs.map((l: { action: string }) => l.action)).toEqual(['lift', 'lower'])

  await page.goto('/orders')
  const row = page.getByRole('row', { name: new RegExp(order.orderNo) })
  await expect(row).toContainText('已完成', { timeout: 100_000 })
  expect((await data(`/api/resource/v1/carriers`)).find((c: { code: string }) => c.code === carrier.code).locationRef).toBe(target.code)
})

test('order templates are listed in the drawer', async ({ page }) => {
  await page.goto('/orders')
  await page.getByRole('button', { name: '订单模板' }).click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer.getByRole('cell', { name: 'STANDARD', exact: true })).toBeVisible()
  await expect(drawer.getByRole('cell', { name: 'LIFT', exact: true })).toBeVisible()
  await expect(drawer).toContainText('源库位 lift → 目标库位 lower')
})
