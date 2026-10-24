import { expect, test } from './session'

test('dispatch parameters are changed and kept', async ({ page }) => {
  await page.goto('/settings')
  const form = page.getByTestId('dispatch-settings')
  const priority = form.getByRole('spinbutton').first()
  await expect(priority).not.toHaveValue('')
  const before = await priority.inputValue()

  await priority.fill('6')
  await form.getByRole('button', { name: '保存调度参数' }).click()
  await expect(page.getByText('调度参数已保存')).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('dispatch-settings').getByRole('spinbutton').first()).toHaveValue('6')

  // put it back for the other tests
  await page.getByTestId('dispatch-settings').getByRole('spinbutton').first().fill(before)
  await page.getByRole('button', { name: '保存调度参数' }).click()
  await expect(page.getByText('调度参数已保存')).toBeVisible()
})
