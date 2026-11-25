<script setup lang="ts">
import { ref } from 'vue'
import { NButton } from 'naive-ui'
import { api, mediaUrl, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'

const props = defineProps<{
  shot: Model['Shot']
  editable: boolean
  needsLast: boolean
  frameSize?: Model['Manifest']['frameSize']
}>()
const emit = defineEmits<{ changed: [] }>()
const busy = ref(false)
const store = useStudio()
async function approve(fileId: string, role: 'first' | 'last') {
  if (busy.value || !props.editable) return
  busy.value = true
  try {
    value(
      await api.POST('/shots/{id}/keyframes/{fileId}/approve', {
        params: { path: { id: props.shot.id, fileId } },
        body: {
          shotRevisionId: props.shot.revisionId,
          expectedRevision: props.shot.frameReviewRevision,
          role,
        },
      }),
    )
  } catch (e) {
    store.report(e)
  } finally {
    emit('changed')
    busy.value = false
  }
}
</script>
<template>
  <section class="panel">
    <div class="panel-title">
      <h2>{{ needsLast ? '首尾帧审核' : '首帧审核' }}</h2>
      <small>{{ shot.previews.length }} 张候选图</small>
    </div>
    <p v-if="frameSize" class="inset render-empty">
      按 {{ frameSize.width }} × {{ frameSize.height }} 居中裁切，原图保留。
    </p>
    <p v-if="!shot.previews.length" class="inset render-empty">请在分镜工作台为此镜头生成预览图。</p>
    <div v-else class="render-frames inset">
      <article v-for="(image, index) in shot.previews" :key="image.id" class="render-frame">
        <a :href="mediaUrl(image.id)" target="_blank" rel="noopener"
          ><img :src="mediaUrl(image.id)" :alt="'候选帧 ' + (index + 1)" loading="lazy"
        /></a>
        <div class="frame-info">
          <strong>候选 {{ index + 1 }}</strong
          ><small>{{ image.width }} × {{ image.height }}</small>
        </div>
        <n-button
          size="small"
          :disabled="!editable || busy || image.id === shot.approvedPreviewId"
          @click="approve(image.id, 'first')"
          >{{ image.id === shot.approvedPreviewId ? '已选首帧' : '审核并设为首帧' }}</n-button
        >
        <n-button
          v-if="needsLast"
          size="small"
          :disabled="!editable || busy || image.id === shot.approvedLastFrameId"
          @click="approve(image.id, 'last')"
          >{{ image.id === shot.approvedLastFrameId ? '已选尾帧' : '审核并设为尾帧' }}</n-button
        >
      </article>
    </div>
  </section>
</template>
