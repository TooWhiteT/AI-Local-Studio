import { test, expect } from './studio-fixture'
import { approvedScript, approvedRenderBoard, studioApi } from './production-fixture'
import type { Model, Workflow } from '../src/api/client'

test.describe.configure({ timeout: 180000 })
test('绑定角色音色并替换录音，裁剪、字幕、混音与两档成片经重启后可播放', async ({
  page,
  studio,
  request,
}) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  const { prefix, episodeId, scriptId } = await approvedScript(studio.url, request),
    { get, post, finish } = studioApi(studio.url, request)
  const flows: Workflow[] = await get('/workflows'),
    image = flows.find((w) => w.enabled && w.manifest.capabilities.includes('text-to-image'))!,
    video = flows.find((w) => w.enabled && w.manifest.firstFrame && !w.manifest.lastFrame)!
  const { board } = await approvedRenderBoard(studio.url, request, prefix, episodeId, scriptId, {
    workflowId: image.id,
    workflowVersion: image.manifest.version,
  })
  const fresh: Model['Board'] = await get('/episodes/' + episodeId + '/shots')
  for (const s of fresh.shots) {
    const url = '/shots/' + s.id,
      first = s.previews[0]
    await post(url + '/keyframes/' + first.id + '/approve', {
      shotRevisionId: s.revisionId,
      expectedRevision: s.frameReviewRevision,
    })
    await finish(
      await post(url + '/render', {
        shotRevisionId: s.revisionId,
        workflowId: video.id,
        workflowVersion: video.manifest.version,
        firstFrameId: first.id,
      }),
    )
    const v: Model['RenderVersion'] = (await get(url + '/renders')).items[0]
    await post(url + '/renders/' + v.id + '/approve', { expectedRevision: v.reviewRevision })
  }
  const url = studio.url + '/#' + prefix
  await page.goto(url + '/audio?episode=' + episodeId)
  await expect(page.getByRole('heading', { name: '角色配音' })).toBeVisible()
  await page.getByLabel('为林青选择音色').selectOption({ label: '模拟声线一' })
  await page.getByRole('button', { name: '保存林青音色' }).click()
  await expect(page.locator('.voice-binding')).toContainText('模拟声线一 · V1')
  const line = page.locator('.audio-line').first()
  await line.getByRole('button', { name: '生成配音' }).click()
  const v1 = line.locator('.audio-take').filter({ hasText: '录音 V1' })
  await v1.getByRole('button', { name: '审核并选用 V1' }).click()
  await expect(v1).toContainText('已选用 V1')
  await expect
    .poll(() => v1.locator('audio').evaluate((a: HTMLAudioElement) => a.duration))
    .toBeGreaterThan(0)
  await page.getByLabel('合成语速').fill('1.2')
  await line.getByRole('button', { name: '生成配音' }).click()
  const v2 = line.locator('.audio-take').filter({ hasText: '录音 V2' })
  await v2.getByRole('button', { name: '审核并选用 V2' }).click()
  await expect(v2).toContainText('已选用 V2')
  await v2.getByRole('link', { name: '查看配音任务' }).click()
  await expect(page.locator('audio[aria-label="任务输出音频"]')).toBeVisible()
  const audioTaskId = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get('task')!
  await page.getByRole('link', { name: '打开镜头配音' }).click()
  await page.getByRole('link', { name: '编辑本集台词' }).click()
  await expect(page.getByLabel('台词', { exact: true })).toHaveValue('来了。')
  await page.goto(url + '/edit?episode=' + episodeId)
  await page.getByRole('button', { name: '按分镜顺序加入全部镜头' }).click()
  const clips = page.locator('.timeline-clip')
  await expect(clips).toHaveCount(2)
  await clips.first().getByRole('button', { name: '下移' }).click()
  await expect(clips.last()).toContainText('镜头 1')
  await clips.last().locator('.timeline-focus').click()
  await page.getByLabel('素材入点（秒）').fill('0.1')
  await page.getByLabel('片段时长（秒）').fill('1.5')
  await page.getByLabel('原声音量（%）').fill('40')
  await page.getByLabel('加入已选用的配音').selectOption({ index: 1 })
  await page.getByLabel('配音起点（秒）').fill('0.2')
  await page.getByLabel('配音音量（%）').fill('90')
  await page.getByLabel('对白字幕').fill('信到了。')
  await page.getByRole('button', { name: '添加字幕' }).click()
  await expect(page.getByRole('button', { name: '保存剪辑版本' })).toBeDisabled()
  await page.getByLabel('字幕文字').fill('沿河邮局')
  await page.getByLabel('字幕起点（秒）').fill('0.8')
  await page.getByLabel('字幕时长（秒）').fill('0.4')
  await page.getByRole('button', { name: '保存剪辑版本' }).click()
  await expect(page.getByLabel('时间线版本')).toContainText('V1')
  const timeline: Model['TimelineVersion'] = (await get('/episodes/' + episodeId + '/timeline')).current
  expect(timeline.clips[1]).toMatchObject({
    shotId: board.shots[0].id,
    sourceInMs: 100,
    durationMs: 1500,
    originalGain: 0.4,
    dialogues: [{ startMs: 200, gain: 0.9, subtitle: '信到了。' }],
    captions: [{ text: '沿河邮局', startMs: 800, durationMs: 400 }],
  })
  for (const resolution of ['480p', '1080p']) {
    await page.getByLabel('导出规格').selectOption(resolution)
    await page.getByRole('button', { name: '导出视频' }).click()
    const movie = page.locator('video[aria-label="成片 ' + resolution + '"]')
    await expect(movie).toBeVisible({ timeout: 45000 })
    await expect
      .poll(() => movie.evaluate((v: HTMLVideoElement) => Math.min(v.videoWidth, v.videoHeight)))
      .toBe(parseInt(resolution))
    await expect
      .poll(() =>
        movie.evaluate((v: HTMLVideoElement) => Array.from(v.textTracks).some((t) => t.kind === 'subtitles')),
      )
      .toBe(true)
  }
  await page.getByLabel('字幕文字').fill('下一班渡轮')
  await page.getByRole('button', { name: '保存剪辑版本' }).click()
  await expect(page.getByLabel('时间线版本').locator('option:checked')).toContainText('V2')
  await page.getByLabel('时间线版本').selectOption(timeline.id)
  await expect(page.getByLabel('字幕文字')).toHaveValue('沿河邮局')
  await expect(page.getByLabel('字幕文字')).toBeDisabled()
  await expect(page.locator('.post-export').first()).toContainText('时间线 V1')
  // Historical exports must play with subtitles even if the global task cache has no matching row.
  await page.route(/\/api\/v1\/tasks(?:\?|$)/, async (route) => {
    const reply = await route.fetch(),
      payload = await reply.json()
    await route.fulfill({
      response: reply,
      json: { ...payload, items: payload.items.filter((t: Model['Task']) => t.kind !== 'EPISODE_EXPORT') },
    })
  })
  await studio.restart()
  await page.reload()
  await expect(page.locator('.post-export')).toHaveCount(2)
  await expect
    .poll(() =>
      page
        .locator('.post-export video')
        .first()
        .evaluate((v: HTMLVideoElement) =>
          Array.from(v.textTracks).some(
            (t) =>
              t.kind === 'subtitles' &&
              Array.from(t.cues ?? []).some((c) => (c as VTTCue).text.includes('沿河邮局')),
          ),
        ),
    )
    .toBe(true)
  await expect(page.locator('.timeline-clip').last()).toContainText('镜头 1')
  await page.getByRole('link', { name: '查看导出任务' }).first().click()
  await expect(page.locator('.detail-head')).toContainText('成片导出')
  await expect(page.locator('video[aria-label="任务输出视频"]')).toBeVisible()
  await page.getByRole('link', { name: '查看成片导出' }).click()
  await expect(page.getByLabel('时间线版本').locator('option:checked')).toContainText('V1')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  // Present an indeterminate SHOT_AUDIO response to exercise the recovery controls without touching its real receipt.
  await page.unrouteAll()
  await page.route(/\/api\/v1\/tasks(?:\?|$)/, async (route) => {
    const response = await route.fetch(),
      list = await response.json()
    await route.fulfill({
      response,
      json: {
        ...list,
        items: list.items.map((t: Model['Task']) => (t.id === audioTaskId ? { ...t, status: 'UNKNOWN' } : t)),
      },
    })
  })
  await page.goto(studio.url + '/#/tasks?task=' + audioTaskId)
  await page.reload()
  await expect(page.getByText('配音服务正在核对原尝试。', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: '请求取消' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '人工核对' })).toHaveCount(0)
  expect(errors).toEqual([])
})
