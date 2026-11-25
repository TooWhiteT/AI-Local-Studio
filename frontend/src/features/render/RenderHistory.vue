<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { api, allPages, dateText, mediaUrl, pathId, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { approvalLabel } from '../storyboard/production'

const props = defineProps<{ shot: Model['Shot']; editable: boolean; boardId: string }>()
const store = useStudio()
const rows = ref<Model['RenderVersion'][]>([]),
  busy = ref(false),
  loading = ref(false)
let request = 0
async function load() {
  const token = ++request,
    id = props.shot.id
  loading.value = true
  try {
    const versions = await allPages(async (cursor) =>
      value(
        await api.GET('/shots/{id}/renders', {
          params: { ...pathId(id), query: { cursor, limit: 100 } },
        }),
      ),
    )
    if (token === request) rows.value = versions
  } catch (e) {
    if (token === request) store.report(e)
  } finally {
    if (token === request) loading.value = false
  }
}
watch(
  () => [
    props.shot.id,
    props.shot.revisionId,
    props.shot.frameReviewRevision,
    props.boardId,
    store.assetsChanged,
  ],
  (next, prev) => {
    if (next[0] !== prev?.[0]) rows.value = []
    void load()
  },
  { immediate: true },
)
function referenceLabel(row: Model['RenderVersion']) {
  const bound = props.shot.references.find((r) => r.versionId === row.referenceVersionId)
  return bound ? bound.name + ' · V' + bound.revision : row.referenceVersionId?.slice(0, 8)
}
function referenceFile(row: Model['RenderVersion']) {
  return store.tasks.find((t) => t.id === row.taskId)?.snapshot.referenceFrame?.id
}
async function review(row: Model['RenderVersion'], approve: boolean) {
  if (!props.editable || busy.value) return
  busy.value = true
  const params = { path: { id: props.shot.id, versionId: row.id } },
    body = { expectedRevision: row.reviewRevision }
  try {
    value(
      approve
        ? await api.POST('/shots/{id}/renders/{versionId}/approve', { params, body })
        : await api.POST('/shots/{id}/renders/{versionId}/reject', { params, body }),
    )
  } catch (e) {
    store.report(e)
  } finally {
    await load()
    busy.value = false
  }
}
</script>
<template>
  <section class="panel video-results">
    <div class="panel-title">
      <h2>视频版本</h2>
      <small>{{ rows.length }} 个结果</small>
    </div>
    <p v-if="!rows.length" class="inset render-empty">
      {{ loading ? '正在读取视频版本…' : '此镜头尚无视频结果。' }}
    </p>
    <article v-for="row in rows" :key="row.id" class="video-version inset">
      <video
        :src="mediaUrl(row.media.id)"
        controls
        playsinline
        preload="metadata"
        :aria-label="'视频版本 ' + row.revision"
      />
      <div class="video-info">
        <div class="video-title">
          <strong>视频 V{{ row.revision }}</strong>
          <span class="status-tag">{{
            row.approvalStatus === 'ARCHIVED' ? '已驳回' : approvalLabel(row.approvalStatus)
          }}</span>
          <span v-if="row.stale" class="stale">上游已更新</span>
        </div>
        <p>
          {{ row.media.width }} × {{ row.media.height }}
          <template v-if="row.media.durationMs != null">
            · {{ (row.media.durationMs / 1000).toFixed(2) }} 秒</template
          >
          <template v-if="row.media.fps != null"> · {{ Number(row.media.fps.toFixed(2)) }} fps</template>
        </p>
        <small
          >{{ dateText(row.createdAt) }} ·
          <template v-if="row.referenceVersionId">
            <a v-if="referenceFile(row)" :href="mediaUrl(referenceFile(row)!)" target="_blank" rel="noopener"
              >参考资产 {{ referenceLabel(row) }}</a
            >
            <span v-else>参考资产 {{ referenceLabel(row) }}</span>
          </template>
          <a v-if="row.firstFrameId" :href="mediaUrl(row.firstFrameId)" target="_blank" rel="noopener"
            >输入首帧</a
          >
          <template v-if="row.lastFrameId">
            · <a :href="mediaUrl(row.lastFrameId)" target="_blank" rel="noopener">输入尾帧</a></template
          ></small
        >
        <router-link :to="{ path: '/tasks', query: { task: row.taskId } }">查看渲染任务</router-link>
        <div
          v-if="
            editable &&
            !row.stale &&
            row.shotRevisionId === shot.revisionId &&
            row.approvalStatus === 'REVIEW_REQUIRED'
          "
          class="version-actions"
        >
          <n-button size="small" type="primary" :disabled="busy" @click="review(row, true)"
            >审核通过 V{{ row.revision }}</n-button
          >
          <n-button size="small" :disabled="busy" @click="review(row, false)"
            >驳回 V{{ row.revision }}</n-button
          >
        </div>
      </div>
    </article>
  </section>
</template>
