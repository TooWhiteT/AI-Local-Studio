import { randomUUID } from 'node:crypto'
import type { APIRequestContext, APIResponse } from '@playwright/test'
import type { Model, Task } from '../src/api/client'
import { expect } from './studio-fixture'

export function studioApi(url: string, request: APIRequestContext) {
  const base = url + '/api/v1'
  async function read(reply: Promise<APIResponse>) {
    const response = await reply
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  const post = (path: string, data: unknown) =>
    read(
      request.post(base + path, {
        headers: { 'Idempotency-Key': randomUUID() },
        data,
      }),
    )
  const put = (path: string, data: unknown) => read(request.put(base + path, { data }))
  const get = (path: string) => read(request.get(base + path))
  async function finish(job: { taskId: string }) {
    await expect
      .poll(
        async () => {
          const task: Task = await get('/tasks/' + job.taskId)
          return task.status === 'FAILED' ? task.attempts.at(-1)?.error : task.status
        },
        { timeout: 15000 },
      )
      .toBe('SUCCEEDED')
  }
  return { post, put, get, finish }
}

export async function approvedScript(url: string, request: APIRequestContext) {
  const { post, put, get } = studioApi(url, request)
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
  const ep = (await get(prefix + '/episodes')).episodes[0].id
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
  return { project: project as Model['Project'], prefix, episodeId: String(ep), scriptId: String(script.id) }
}

export async function approvedRenderBoard(
  url: string,
  request: APIRequestContext,
  prefix: string,
  episodeId: string,
  scriptId: string,
  imageParams: { workflowId: string; workflowVersion: string },
) {
  const { post, get, finish } = studioApi(url, request)
  async function approvedAsset(name: string, type: string) {
    const asset = await post(prefix + '/assets', { name, type })
    await finish(await post('/assets/' + asset.id + '/generate', { ...imageParams, parameters: {} }))
    const generated = await get('/assets/' + asset.id)
    await post('/assets/' + asset.id + '/versions/' + generated.versions[0].id + '/approve', {
      expectedRevision: generated.reviewRevision,
    })
    return String(generated.versions[0].id)
  }
  const station = await approvedAsset('邮局', 'SCENE'),
    envelope = await approvedAsset('信封', 'PROP')
  const boardPath = '/episodes/' + episodeId + '/shots'
  await finish(
    await post(boardPath + '/plan', {
      scriptRevisionId: scriptId,
      expectedRevision: 0,
      assetVersionIds: [station, envelope],
      shotsPerScene: 2,
      brief: '',
    }),
  )
  const board: Model['Board'] = await get(boardPath)
  await post(boardPath + '/' + board.version!.id + '/approve', { expectedRevision: board.version!.revision })
  for (const shot of board.shots)
    await finish(
      await post('/shots/' + shot.id + '/keyframes/generate', {
        ...imageParams,
        shotRevisionId: shot.revisionId,
      }),
    )
  return { board, station, envelope }
}
