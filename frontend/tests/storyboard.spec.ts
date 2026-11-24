import { test, expect } from './studio-fixture'

test('从审核剧本提取资产、比较候选、固定旧版本并编辑分镜', async ({ page, studio, request }) => {
  const api = studio.url + '/api/v1',
    errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  async function put(path: string, data: unknown) {
    const r = await request.put(api + path, { data })
    expect(r.ok()).toBeTruthy()
    return r.json()
  }
  async function post(path: string, data: unknown) {
    const r = await request.post(api + path, { data })
    expect(r.ok()).toBeTruthy()
    return r.json()
  }
  const project = await post('/projects', { title: '分镜试片' })
  const prefix = '/projects/' + project.id
  const outline = await put(prefix + '/outline', {
    expectedRevision: 0,
    content: {
      title: '旧信',
      logline: '邮局里的来信',
      world: '山城',
      characters: [{ key: 'lin', name: '林青', description: '邮局站员' }],
      locations: [{ key: 'post', name: '邮局', description: '旧柜台' }],
      beats: ['发现信件'],
    },
  })
  await post(prefix + '/outline/' + outline.id + '/approve', { expectedRevision: 1 })
  const plan = await put(prefix + '/episodes/plan', {
    expectedRevision: 0,
    storyRevisionId: outline.id,
    content: { episodes: [{ title: '来信', summary: '拆开信封', targetDurationMs: 10000 }] },
  })
  const ep = (await (await request.get(api + prefix + '/episodes')).json()).episodes[0].id
  const script = await put('/episodes/' + ep + '/script', {
    expectedRevision: 0,
    storyRevisionId: outline.id,
    planId: plan.id,
    content: {
      title: '来信',
      scenes: [
        {
          locationKey: 'post',
          characters: ['lin'],
          durationMs: 10000,
          action: '站员打开信件。',
          dialogues: [{ speaker: 'lin', text: '来了。', emotion: '平静' }],
        },
      ],
    },
  })
  await post('/episodes/' + ep + '/script/' + script.id + '/approve', { expectedRevision: 1 })
  const assetUrl = studio.url + '/#' + prefix + '/assets',
    boardUrl = studio.url + '/#' + prefix + '/storyboard'
  await page.goto(assetUrl)
  await page.getByRole('button', { name: '从剧本提取资产' }).click()
  const actor = page.locator('.asset-strip button').filter({ hasText: '林青' })
  await actor.click()
  await page.getByRole('button', { name: '提交图片任务' }).click()
  await page.getByRole('button', { name: '审核并选用 V1' }).click()
  await expect(page.locator('.version-tile').filter({ hasText: '版本 1' })).toContainText('当前选用')
  await page.getByRole('button', { name: '提交图片任务' }).click()
  await page.getByRole('button', { name: '对比候选' }).click()
  await expect(page.locator('.version-compare img')).toHaveCount(2)
  await page.goto(boardUrl)
  await page.getByRole('button', { name: '生成分镜计划' }).click()
  await expect(page.locator('.shot-list button')).toHaveCount(2)
  await page.getByRole('button', { name: '审核当前分镜' }).click()
  await expect(page.locator('.history-select')).toContainText('已审核')
  await page.goto(assetUrl)
  await actor.click()
  await page.getByRole('button', { name: '审核并选用 V2' }).click()
  await expect(page.locator('.version-tile').filter({ hasText: '版本 2' })).toContainText('当前选用')
  await page.goto(boardUrl)
  await expect(page.locator('.reference-grid')).toContainText('林青 · V1')
  await expect(page.locator('.board-toolbar')).toContainText('上游已更新')
  await page.getByRole('textbox', { name: '画面动作' }).fill('站员将旧信放进口袋。')
  await page.getByRole('button', { name: '保存镜头' }).click()
  await expect(page.locator('.history-select')).toContainText('V2')
  await page.getByRole('button', { name: '下移' }).click()
  await expect(page.locator('.shot-list button').last()).toContainText('站员将旧信放进口袋。')
  await page.getByRole('button', { name: '重做此镜头' }).click()
  await expect(page.locator('.history-select')).toContainText('V4')
  await page.getByRole('button', { name: '生成分镜预览图' }).click()
  await expect(page.locator('.frame-preview')).toBeVisible()
  await studio.restart()
  await page.reload()
  await page.locator('.shot-list button').last().click()
  await expect(page.locator('.frame-preview')).toBeVisible()
  await expect(page.locator('.reference-grid')).toContainText('林青 · V1')
  await page.locator('.shot-list button').first().click()
  await page.getByRole('button', { name: '保留这些参考版本' }).click()
  await page.getByRole('button', { name: '审核当前分镜' }).click()
  await expect(page.locator('.history-select')).toContainText('已审核')
  await expect(page.locator('.reference-grid')).toContainText('已选定的历史版本')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
})
