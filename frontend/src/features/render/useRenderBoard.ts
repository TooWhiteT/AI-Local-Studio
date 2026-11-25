import { computed, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, allPages, pathId, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { useEpisodeScript } from '../storyboard/production'

export function useRenderBoard(projectId: Ref<string>) {
  const route = useRoute(),
    store = useStudio()
  const {
    episode,
    script,
    options: episodes,
    approved: scriptApproved,
  } = useEpisodeScript(projectId, String(route.query.episode ?? ''))
  const board = ref<Model['Board']>(),
    versions = ref<Model['BoardSummary'][]>([])
  const historyId = ref(''),
    focused = ref(''),
    loading = ref(false)
  let boardRequest = 0,
    linkRequest = 0
  const shot = computed(() => board.value?.shots.find((s) => s.id === focused.value))
  const historical = computed(
    () => !!board.value?.version && board.value.version.id !== versions.value[0]?.id,
  )
  const active = computed(() => store.projects.find((p) => p.id === projectId.value)?.status === 'ACTIVE')
  const editable = computed(
    () =>
      active.value &&
      scriptApproved.value &&
      board.value?.version?.approvalStatus === 'APPROVED' &&
      !board.value.stale &&
      !historical.value,
  )
  async function refresh() {
    const token = ++boardRequest,
      id = episode.value
    if (!id) return
    loading.value = true
    try {
      const [data, list] = await Promise.all([
        api
          .GET('/episodes/{id}/shots', {
            params: { ...pathId(id), query: { revisionId: historyId.value || undefined } },
          })
          .then(value),
        allPages(async (cursor) =>
          value(
            await api.GET('/episodes/{id}/shots/history', {
              params: { ...pathId(id), query: { cursor, limit: 100 } },
            }),
          ),
        ),
      ])
      if (token !== boardRequest || id !== episode.value) return
      versions.value = list
      board.value = data
      if (!data.shots.some((s) => s.id === focused.value)) focused.value = data.shots[0]?.id ?? ''
    } catch (e) {
      if (token === boardRequest) store.report(e)
    } finally {
      if (token === boardRequest) loading.value = false
    }
  }
  watch(
    episode,
    () => {
      historyId.value = ''
      board.value = undefined
      versions.value = []
      focused.value = ''
      void refresh()
    },
    { flush: 'sync' },
  )
  watch(historyId, () => {
    board.value = undefined
    void refresh()
  })
  watch(() => store.assetsChanged, refresh)
  let opened = ''
  watch(
    [() => route.fullPath, episodes],
    async ([path, choices]) => {
      if (!choices.length || path === opened) return
      opened = path
      const token = ++linkRequest,
        id = route.query.shot
      if (typeof id !== 'string') return
      try {
        const source = value(
          await api.GET('/shots/{id}', {
            params: {
              ...pathId(id),
              query: {
                shotRevisionId: typeof route.query.revision === 'string' ? route.query.revision : undefined,
              },
            },
          }),
        )
        if (
          token !== linkRequest ||
          path !== route.fullPath ||
          !choices.some((e) => e.value === source.episodeId)
        )
          return
        episode.value = source.episodeId
        const boardId = typeof route.query.board === 'string' ? route.query.board : source.version?.id
        await refresh()
        if (path !== route.fullPath || episode.value !== source.episodeId) return
        historyId.value =
          boardId && versions.value.some((v) => v.id === boardId) && boardId !== versions.value[0]?.id
            ? boardId
            : ''
        focused.value = id
        if (board.value?.version?.id !== boardId) await refresh()
      } catch (e) {
        if (token === linkRequest) store.report(e)
      }
    },
    { immediate: true },
  )
  return {
    script,
    episode,
    episodes,
    board,
    versions,
    historyId,
    focused,
    shot,
    historical,
    active,
    editable,
    loading,
    refresh,
  }
}
