import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

export const api = createClient<paths>({ baseUrl: '/api/v1' })
export type Model = components['schemas']
export type Project = Model['Project']
export type Task = Model['Task']
export type Asset = Model['Asset']
export type Workflow = Model['Workflow']
export type Binding = Model['Binding']
export type Scalar = Binding['default']

export function value<T>(reply: { data?: T; error?: Model['ApiError']; response: Response }): T {
  if (!reply.response.ok || reply.data === undefined) {
    throw new Error(reply.error?.message ?? `服务请求失败（${reply.response.status}）`)
  }
  return reply.data
}
export const pathId = (id: string) => ({ path: { id } })
export const mediaUrl = (id: string) => `/api/v1/media/${encodeURIComponent(id)}`
export const gib = (bytes: number) => (bytes / 1024 ** 3).toFixed(1) + ' GiB'
export const statusText: Record<Model['Status'], string> = {
  PENDING: '等待依赖',
  READY: '排队中',
  SUBMITTING: '提交中',
  SUBMITTED: '已提交',
  RUNNING: '生成中',
  UNKNOWN: '待核对',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
}
export const assetTypes = { CHARACTER: '角色', SCENE: '场景', PROP: '道具', COSTUME: '服装', VOICE: '声音' } as const
export const lastAttempt = (t: Task) => t.attempts[t.attempts.length - 1]
export const terminal = (t: Task) => ['SUCCEEDED', 'FAILED', 'CANCELED'].includes(t.status)
export const dateText = (time: number) =>
  new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(time)
export async function allPages<T>(
  fetchPage: (cursor?: string) => Promise<{ items: T[]; nextCursor: string | null }>,
): Promise<T[]> {
  const rows: T[] = []
  let cursor: string | undefined
  do {
    const page = await fetchPage(cursor)
    rows.push(...page.items)
    cursor = page.nextCursor ?? undefined
  } while (cursor)
  return rows
}
