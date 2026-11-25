import { test as base, expect } from '@playwright/test'
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'

const root = fileURLToPath(new URL('../../', import.meta.url))
async function port(): Promise<number> {
  const server = createServer()
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('TCP 地址不可用')
  await new Promise<void>((resolve, reject) => server.close((e) => (e ? reject(e) : resolve())))
  return address.port
}
function launch(module: string, main: string, env: NodeJS.ProcessEnv) {
  return spawn(
    'java',
    [
      '-XX:ActiveProcessorCount=2',
      '-cp',
      join(root, 'backend', module, 'build', 'install', module, 'lib', '*'),
      main,
    ],
    { cwd: root, env: { ...process.env, ...env }, stdio: ['ignore', 'ignore', 'inherit'] },
  )
}
async function stop(child: ChildProcess) {
  if (child.exitCode !== null || child.signalCode !== null) return
  const done = once(child, 'exit')
  child.kill()
  await done
}
async function ready(url: string) {
  await expect
    .poll(
      async () => {
        try {
          return (await fetch(url, { signal: AbortSignal.timeout(1000) })).status
        } catch {
          return 0
        }
      },
      { timeout: 15000 },
    )
    .toBe(200)
}
// Extra wire fixtures live only in the disposable browser-test directory.
async function installRenderModes(storage: string) {
  const packages = join(storage, 'mock-workflows')
  for (const mode of ['dual', 'reference']) {
    const key = 'mock-video-' + mode
    const src = join(packages, 'mock-video', '1')
    const dir = join(packages, key, '1')
    const manifest = JSON.parse(await readFile(join(src, 'manifest.json'), 'utf8'))
    const graph = JSON.parse(await readFile(join(src, 'workflow.json'), 'utf8'))
    manifest.id = key
    manifest.title = mode === 'dual' ? '模拟首尾帧' : '模拟参考图'
    if (mode === 'dual') {
      graph['3'] = structuredClone(graph['2'])
      graph['1'].inputs.last_frame = ['3', 0]
      manifest.lastFrame = { node: '3', input: 'image' }
    } else {
      manifest.capabilities = ['reference-to-video']
      manifest.referenceImage = manifest.firstFrame
      delete manifest.firstFrame
    }
    await mkdir(dir, { recursive: true })
    await writeFile(join(dir, 'manifest.json'), JSON.stringify(manifest))
    await writeFile(join(dir, 'workflow.json'), JSON.stringify(graph))
  }
}
export const test = base.extend<{
  studio: { url: string; restart: () => Promise<void>; installRenderModes: () => Promise<void> }
}>({
  studio: async ({}, use) => {
    const storage = await mkdtemp(join(tmpdir(), 'studio-browser-'))
    const mockPort = await port(),
      apiPort = await port()
    const url = `http://127.0.0.1:${apiPort}`,
      comfy = `http://127.0.0.1:${mockPort}`
    const mock = launch('mock-comfy', 'studio.mock.MockServerKt', { MOCK_PORT: String(mockPort) })
    let app: ChildProcess | undefined
    try {
      await ready(comfy + '/system_stats')
      async function start() {
        app = launch('app', 'studio.app.MainKt', {
          STUDIO_ENGINE: 'mock',
          STUDIO_LLM_MODE: 'mock',
          STUDIO_ACCEPTANCE_COMFY_ORIGIN: '',
          STUDIO_STORAGE: storage,
          STUDIO_PORT: String(apiPort),
          COMFY_URL: comfy,
          STUDIO_WORKFLOWS: '',
        })
        await ready(url + '/api/v1/projects')
      }
      await start()
      await use({
        url,
        installRenderModes: () => installRenderModes(storage),
        restart: async () => {
          await stop(app!)
          await start()
        },
      })
    } finally {
      if (app) await stop(app)
      await stop(mock)
      await rm(storage, { recursive: true, force: true })
    }
  },
})
export { expect }
