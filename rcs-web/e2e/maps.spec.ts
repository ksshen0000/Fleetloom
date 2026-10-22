import { readFileSync } from 'node:fs'
import { ADMIN, expect, loginAs, test } from './session'

test('a map is exported to a file and imported into another map', async ({ page }) => {
  const token = await loginAs(page, ADMIN)
  const headers = { Authorization: `Bearer ${token}` }
  const code = `E2E${Date.now() % 100000}`
  const api = '/api/resource/v1/maps'
  expect((await page.request.post(api, { headers, data: { code, name: `导入测试 ${code}` } })).ok()).toBe(true)
  const draft = (await (await page.request.post(`${api}/${code}/versions`, { headers, data: {} })).json()).data

  try {
    await page.goto('/maps')
    await page.locator('.toolbar .el-select').first().click()
    await page.getByRole('option', { name: 'Demo grid' }).click()
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出' }).click()
    const file = await (await download).path()
    const exported = JSON.parse(readFileSync(file, 'utf8'))
    expect(exported.format).toBe('rcs3000-map')
    expect(exported.content.points.length).toBeGreaterThan(9)

    await page.locator('.toolbar .el-select').first().click()
    await page.getByRole('option', { name: `导入测试 ${code}` }).click()
    await expect(page.getByRole('button', { name: '导入' })).toBeVisible()
    await page.getByTestId('map-import').setInputFiles(file)
    await page.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('已保存').first()).toBeVisible()

    const saved = (await (await page.request.get(`${api}/${code}/versions/${draft.versionNo}`, { headers })).json()).data
    expect(saved.points.length).toBe(exported.content.points.length)
    expect(saved.paths.length).toBe(exported.content.paths.length)
  } finally {
    await page.request.delete(`${api}/${code}`, { headers })
  }
})
