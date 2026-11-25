import { computed, ref, watch, type Ref } from 'vue'
import { api, value, pathId, allPages, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { editableClip, newClip, validVideos, type Clip, type Source } from './timeline'

async function sourceFor(shot: Model['Shot']): Promise<Source> {
  const query = { shotRevisionId: shot.revisionId }
  const [videos, audio] = await Promise.all([
    allPages(async (cursor) =>
      value(
        await api.GET('/shots/{id}/renders', {
          params: { ...pathId(shot.id), query: { ...query, cursor, limit: 100 } },
        }),
      ),
    ),
    api.GET('/shots/{id}/audio', { params: { ...pathId(shot.id), query } }).then(value),
  ])
  return { shot, videos, audio }
}
export function useTimeline(episode: Ref<string>) {
  const store = useStudio(),
    board = ref<Model['Board']>(),
    history = ref<Model['TimelineVersion'][]>([]),
    sources = ref<Source[]>([])
  const view = ref(''),
    clips = ref<Clip[]>([]),
    focus = ref(0),
    baseline = ref('[]'),
    baseRevision = ref(0),
    draftBoard = ref(''),
    busy = ref(false),
    loading = ref(false)
  const current = computed(() => history.value[0]),
    shown = computed(() => history.value.find((v) => v.id === view.value))
  const historical = computed(() => !!shown.value && shown.value.id !== current.value?.id)
  const dirty = computed(() => JSON.stringify(clips.value) !== baseline.value)
  const saveable = computed(
    () =>
      !!board.value?.version &&
      board.value.version.approvalStatus === 'APPROVED' &&
      !board.value.stale &&
      board.value.version.id === draftBoard.value &&
      !historical.value &&
      baseRevision.value === (current.value?.revision ?? 0),
  )
  const focused = computed(() => clips.value[focus.value]),
    source = computed(() => sources.value.find((s) => s.shot.id === focused.value?.shotId))
  let sequence = 0,
    scope = '',
    resetPending = false
  function show(version?: Model['TimelineVersion']) {
    view.value = version?.id ?? ''
    baseRevision.value = current.value?.revision ?? 0
    draftBoard.value = version?.boardId ?? board.value?.version?.id ?? ''
    clips.value = version?.clips.map(editableClip) ?? []
    baseline.value = JSON.stringify(clips.value)
    focus.value = Math.max(0, Math.min(focus.value, clips.value.length - 1))
  }
  async function refresh(reset = false) {
    const id = episode.value,
      token = ++sequence
    if (reset) resetPending = true
    if (!id) {
      loading.value = false
      return
    }
    loading.value = true
    try {
      const [latest, records] = await Promise.all([
        api.GET('/episodes/{id}/shots', { params: pathId(id) }).then(value),
        allPages(async (cursor) =>
          value(
            await api.GET('/episodes/{id}/timeline/history', {
              params: { ...pathId(id), query: { cursor, limit: 100 } },
            }),
          ),
        ),
      ])
      if (token !== sequence) return
      const wasCurrent = !historical.value
      history.value = records
      board.value = latest
      if (resetPending || (!dirty.value && wasCurrent && scope !== 'new')) {
        show(current.value)
        resetPending = false
        scope = ''
      }
      const selectedBoard =
        draftBoard.value && draftBoard.value !== latest.version?.id
          ? value(
              await api.GET('/episodes/{id}/shots', {
                params: { ...pathId(id), query: { revisionId: draftBoard.value } },
              }),
            )
          : latest
      const result: Source[] = []
      for (let i = 0; i < selectedBoard.shots.length; i += 4) {
        if (token !== sequence) return
        result.push(...(await Promise.all(selectedBoard.shots.slice(i, i + 4).map(sourceFor))))
      }
      if (token === sequence) sources.value = result
    } catch (e) {
      if (token === sequence) store.report(e)
    } finally {
      if (token === sequence) loading.value = false
    }
  }
  watch(
    episode,
    () => {
      board.value = undefined
      history.value = []
      sources.value = []
      scope = ''
      show()
      void refresh(true)
    },
    { immediate: true },
  )
  watch(
    () => store.assetsChanged,
    () => void refresh(),
  )
  function selectVersion(id: string) {
    if (dirty.value && !window.confirm('切换并放弃当前未保存的剪辑？')) return
    show(history.value.find((v) => v.id === id))
    scope = 'history'
    void refresh()
  }
  function startCurrentBoard() {
    if (dirty.value && !window.confirm('放弃当前未保存的剪辑？')) return
    scope = 'new'
    show()
    sources.value = []
    void refresh()
  }
  function add(id?: string) {
    const added = sources.value
      .filter(
        (s) =>
          (id ? s.shot.id === id : !clips.value.some((c) => c.shotId === s.shot.id)) && validVideos(s).length,
      )
      .flatMap((s) => newClip(s) ?? [])
    clips.value = [...clips.value, ...added]
    if (added.length) focus.value = clips.value.length - added.length
  }
  function move(i: number, delta: number) {
    if (i + delta < 0 || i + delta >= clips.value.length) return
    const reordered = [...clips.value]
    reordered.splice(i + delta, 0, reordered.splice(i, 1)[0])
    focus.value = reordered.indexOf(clips.value[focus.value])
    clips.value = reordered
  }
  function remove(index: number) {
    clips.value = clips.value.filter((_, i) => i !== index)
    if (index < focus.value || focus.value >= clips.value.length) focus.value = Math.max(0, focus.value - 1)
  }
  function update(clip: Clip) {
    clips.value = clips.value.map((old, i) => (i === focus.value ? clip : old))
  }
  async function save() {
    if (busy.value || !saveable.value || !clips.value.length || !dirty.value) return
    const id = episode.value
    busy.value = true
    try {
      const next = value(
        await api.PUT('/episodes/{id}/timeline', {
          params: pathId(id),
          body: { expectedRevision: baseRevision.value, boardId: draftBoard.value, clips: clips.value },
        }),
      )
      if (id !== episode.value) return
      history.value = [next, ...history.value]
      show(next)
      scope = ''
      await refresh()
    } catch (e) {
      store.report(e)
      await refresh()
    } finally {
      busy.value = false
    }
  }
  return {
    board,
    history,
    sources,
    view,
    clips,
    focus,
    focused,
    source,
    shown,
    current,
    historical,
    dirty,
    saveable,
    baseRevision,
    busy,
    loading,
    add,
    move,
    remove,
    update,
    save,
    selectVersion,
    startCurrentBoard,
  }
}
