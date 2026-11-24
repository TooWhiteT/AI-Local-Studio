import { computed, ref, watch, type Ref } from 'vue'
import { api, pathId, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'

export const approvalLabel = (status: string) =>
  ({ APPROVED: '已审核', REVIEW_REQUIRED: '待审核', ARCHIVED: '已弃用', DRAFT: '草稿' })[status] ?? status

export function useEpisodeScript(projectId: Ref<string>, preferred = '') {
  const store = useStudio()
  const episodes = ref<Model['Episode'][]>([]),
    episode = ref(''),
    script = ref<Model['WritingRevision']>()
  let directoryRequest = 0,
    scriptRequest = 0
  watch(
    projectId,
    () => {
      ++directoryRequest
      ++scriptRequest
      episodes.value = []
      episode.value = ''
      script.value = undefined
    },
    { flush: 'sync' },
  )
  watch(
    [projectId, () => store.assetsChanged],
    async ([id]) => {
      const token = ++directoryRequest
      try {
        const dir = value(await api.GET('/projects/{id}/episodes', { params: pathId(id) }))
        if (token !== directoryRequest) return
        episodes.value = [...dir.episodes, ...dir.retired]
        if (!episodes.value.some((e) => e.id === episode.value))
          episode.value = episodes.value.find((e) => e.id === preferred)?.id ?? episodes.value[0]?.id ?? ''
      } catch (e) {
        if (token === directoryRequest) store.report(e)
      }
    },
    { immediate: true },
  )
  watch(
    [episode, projectId, () => store.assetsChanged],
    async ([id], prev) => {
      const token = ++scriptRequest
      if (id !== prev?.[0]) script.value = undefined
      if (!id) return
      try {
        const page = value(
          await api.GET('/episodes/{id}/script', { params: { ...pathId(id), query: { limit: 1 } } }),
        )
        if (token === scriptRequest) script.value = page.items[0]
      } catch (e) {
        if (token === scriptRequest) store.report(e)
      }
    },
    { immediate: true },
  )
  const approved = computed(() => script.value?.approvalStatus === 'APPROVED' && !script.value.stale)
  const options = computed(() =>
    episodes.value.map((e) => ({ value: e.id, label: '第 ' + e.ordinal + ' 集 · ' + e.title })),
  )
  return { episode, script, approved, options }
}
export function submissionKey() {
  const keys = new Map<string, string>()
  return {
    for(identity: string) {
      if (!keys.has(identity)) keys.set(identity, crypto.randomUUID())
      return { 'Idempotency-Key': keys.get(identity)! }
    },
    accepted(identity: string) {
      keys.delete(identity)
    },
  }
}
