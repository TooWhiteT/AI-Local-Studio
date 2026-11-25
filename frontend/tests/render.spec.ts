import { test, expect } from './studio-fixture'
import { approvedScript, approvedRenderBoard, studioApi } from './production-fixture'
import type { Model, Workflow } from '../src/api/client'

test.describe.configure({ timeout: 120000 })

test('审核输入帧、单镜与批量渲染，刷新后查看视频历史与任务', async ({ page, studio, request }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const { prefix, episodeId, scriptId } = await approvedScript(studio.url, request)
  const { get, post, finish } = studioApi(studio.url, request)
  await studio.installRenderModes()
  const workflows: Workflow[] = await post('/workflows/refresh', {})
  const image = workflows.find((w) => w.enabled && w.manifest.capabilities.includes('text-to-image'))!
  const video = workflows.find((w) => w.enabled && w.manifest.firstFrame && !w.manifest.lastFrame)!
  expect(image).toBeDefined()
  expect(video, JSON.stringify(workflows)).toBeDefined()
  expect(
    workflows.filter((w) => w.enabled && (w.manifest.lastFrame || w.manifest.referenceImage)),
  ).toHaveLength(2)
  const imageParams = { workflowId: image.id, workflowVersion: image.manifest.version }
  const { station, envelope, board } = await approvedRenderBoard(
    studio.url,
    request,
    prefix,
    episodeId,
    scriptId,
    imageParams,
  )
  const url = studio.url + '/#' + prefix + '/render?episode=' + episodeId
  await page.goto(url)
  const render = page.getByRole('button', { name: '渲染此镜头' })
  const first = page.getByRole('button', { name: '审核并设为首帧' })
  await expect(page.getByRole('heading', { name: '视频渲染' })).toBeVisible()
  await expect(first).toHaveCount(1)
  await expect(render).toBeDisabled()
  await expect(page.getByRole('button', { name: '审核并设为尾帧' })).toHaveCount(0)
  await first.click()
  await render.click()
  const v1 = page.locator('.video-version').filter({ hasText: '视频 V1' })
  await expect(v1.getByRole('button', { name: '审核通过 V1' })).toBeVisible({ timeout: 15000 })
  await expect
    .poll(() => v1.locator('video').evaluate((el: HTMLVideoElement) => el.videoWidth))
    .toBeGreaterThan(0)
  await v1.getByRole('button', { name: '审核通过 V1' }).click()
  await expect(v1).toContainText('已审核')
  await page.getByRole('checkbox', { name: '批量选择镜头 1' }).check()
  await page.locator('.render-shot button').nth(1).click()
  await first.click()
  await page.getByRole('checkbox', { name: '批量选择镜头 2' }).check()
  const batchReply = page.waitForResponse(
    (r) => r.url().endsWith('/episodes/' + episodeId + '/render') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '批量渲染（2）' }).click()
  const response = await batchReply
  expect(response.status()).toBe(202)
  expect(response.request().postDataJSON().parameters).not.toHaveProperty('durationSeconds')
  const batch: Model['BatchAccepted'] = await response.json()
  expect(new Set(batch.taskIds).size).toBe(2)
  for (const id of batch.taskIds) await finish({ taskId: id })
  await page.locator('.render-shot button').first().click()
  const v2 = page.locator('.video-version').filter({ hasText: '视频 V2' })
  await v2.getByRole('button', { name: '驳回 V2' }).click()
  await expect(v2).toContainText('已驳回')
  await v2.getByRole('link', { name: '查看渲染任务' }).click()
  await expect(page.locator('.detail-head')).toContainText('镜头视频渲染')
  await expect(page.locator('video[aria-label="任务输出视频"]')).toBeVisible()
  await page.getByRole('link', { name: '查看镜头视频' }).click()
  await expect(page.locator('.video-version')).toHaveCount(2)
  const replacement = await post('/shots/' + board.shots[0].id + '/keyframes/generate', {
    ...imageParams,
    shotRevisionId: board.shots[0].revisionId,
    parameters: { seed: 314 },
  })
  await finish(replacement)
  await first.click()
  await expect(v1).toContainText('上游已更新')
  await studio.restart()
  await page.reload()
  await expect(v1).toContainText('已审核')
  await expect(v1).toContainText('上游已更新')
  await expect(v2).toContainText('已驳回')
  const chooseMode = async (title: string) => {
    await page.locator('.render-toolbar .n-select').last().click()
    await page.getByText(title, { exact: false }).last().click()
  }
  await chooseMode('模拟首尾帧')
  await expect(render).toBeDisabled()
  await page.getByRole('button', { name: '审核并设为尾帧' }).first().click()
  await expect(render).toBeEnabled()
  await page.getByRole('checkbox', { name: '自定义渲染时长' }).check()
  const seconds = page.locator('.render-duration input:not([type="checkbox"])')
  await seconds.fill('4')
  await seconds.press('Tab')
  const customDuration = page.waitForResponse(
    (r) => r.url().endsWith('/shots/' + board.shots[0].id + '/render') && r.request().method() === 'POST',
  )
  await render.click()
  expect((await customDuration).request().postDataJSON().parameters.durationSeconds).toBe(4)
  const v3 = page.locator('.video-version').filter({ hasText: '视频 V3' })
  await expect(v3.getByRole('link', { name: '输入尾帧' })).toBeVisible({ timeout: 15000 })
  await chooseMode('模拟参考图')
  await expect(page.getByRole('heading', { name: '镜头参考图' })).toBeVisible()
  await expect(page.getByRole('radio')).toHaveCount(2)
  await expect(page.getByRole('radio').first()).toBeChecked()
  await page.getByRole('radio', { name: '信封 · V1' }).check()
  await expect(page.getByRole('heading', { name: '首帧审核' })).toHaveCount(0)
  const referenceReply = page.waitForResponse(
    (r) => r.url().endsWith('/shots/' + board.shots[0].id + '/render') && r.request().method() === 'POST',
  )
  await render.click()
  const reference = await referenceReply
  expect(reference.request().postDataJSON()).toMatchObject({ referenceVersionId: envelope })
  expect(reference.request().postDataJSON()).not.toHaveProperty('firstFrameId')
  await expect(page.locator('.video-version').filter({ hasText: '视频 V4' })).toContainText('参考资产 信封', {
    timeout: 15000,
  })
  await page.getByRole('checkbox', { name: '批量选择镜头 1' }).check()
  await page.locator('.render-shot button').nth(1).click()
  await page.getByRole('radio', { name: '邮局 · V1' }).check()
  await page.getByRole('checkbox', { name: '批量选择镜头 2' }).check()
  const referenceBatch = page.waitForResponse(
    (r) => r.url().endsWith('/episodes/' + episodeId + '/render') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '批量渲染（2）' }).click()
  const batchResponse = await referenceBatch
  expect(batchResponse.status()).toBe(202)
  expect(batchResponse.request().postDataJSON().shots).toEqual([
    { shotRevisionId: board.shots[0].revisionId, referenceVersionId: envelope },
    { shotRevisionId: board.shots[1].revisionId, referenceVersionId: station },
  ])
  for (const id of (await batchResponse.json()).taskIds) await finish({ taskId: id })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
})
