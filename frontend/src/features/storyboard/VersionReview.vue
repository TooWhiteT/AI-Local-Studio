<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NSelect, useMessage } from 'naive-ui'
import { api, value, mediaUrl, type Asset, type Model } from '../../api/client'
import { approvalLabel } from './production'
import { useStudio } from '../../stores/studio'
const props = defineProps<{ asset: Asset; active: boolean }>()
const emit = defineEmits<{ updated: []; preview: [version: Model['Version']] }>()
const store = useStudio(),
  toast = useMessage()
const compare = ref(false),
  left = ref(''),
  right = ref(''),
  busy = ref(false)
watch(
  [() => props.asset.id, () => props.asset.versions.length],
  () => {
    left.value = props.asset.activeVersionId ?? props.asset.versions.at(-1)?.id ?? ''
    right.value = props.asset.versions.find((v) => v.id !== left.value)?.id ?? ''
    compare.value = false
  },
  { immediate: true },
)
const options = computed(() =>
  props.asset.versions.map((v) => ({
    value: v.id,
    label: 'V' + v.revision + ' · ' + approvalLabel(v.approvalStatus),
  })),
)
const compared = computed(() =>
  [left.value, right.value].map((id) => props.asset.versions.find((v) => v.id === id)),
)
async function review(v: Model['Version'], approve: boolean) {
  if (busy.value) return
  busy.value = true
  try {
    value(
      await api.POST(approve ? '/assets/{id}/versions/{vid}/approve' : '/assets/{id}/versions/{vid}/reject', {
        params: { path: { id: props.asset.id, vid: v.id } },
        body: { expectedRevision: props.asset.reviewRevision },
      }),
    )
    toast.success(approve ? '已选用 V' + v.revision : '已弃用 V' + v.revision)
    emit('updated')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div v-if="asset.versions.length > 1" class="production-toolbar">
    <n-button size="small" @click="compare = !compare">{{ compare ? '关闭对比' : '对比候选' }}</n-button>
  </div>
  <div v-if="compare" class="version-compare">
    <div v-for="(v, i) in compared" :key="i">
      <n-select
        :value="i === 0 ? left : right"
        :aria-label="i === 0 ? '左侧候选' : '右侧候选'"
        :options="options.filter((o) => o.value !== (i === 0 ? right : left))"
        @update:value="(id) => (i === 0 ? (left = id) : (right = id))"
      />
      <img v-if="v" :src="mediaUrl(v.file.id)" :alt="'对比 V' + v.revision" /><small v-if="v" class="hash">{{
        v.file.sha256
      }}</small>
    </div>
  </div>
  <div class="gallery">
    <article v-for="v in asset.versions" :key="v.id" class="version-tile">
      <button class="image-card" @click="emit('preview', v)">
        <img :src="mediaUrl(v.file.id)" :alt="asset.name + ' 版本 ' + v.revision" />
        <span
          ><strong
            >版本 {{ v.revision }} <small v-if="v.id === asset.activeVersionId">· 当前选用</small></strong
          >
          <small
            >{{ v.file.width }} × {{ v.file.height }} · {{ approvalLabel(v.approvalStatus) }}</small
          ></span
        >
      </button>
      <div
        v-if="active && v.approvalStatus !== 'ARCHIVED' && v.id !== asset.activeVersionId"
        class="version-actions"
      >
        <n-button size="tiny" :disabled="busy" @click="review(v, true)"
          >{{ v.approvalStatus === 'APPROVED' ? '选用' : '审核并选用' }} V{{ v.revision }}</n-button
        >
        <n-button
          v-if="v.approvalStatus === 'REVIEW_REQUIRED'"
          size="tiny"
          :disabled="busy"
          @click="review(v, false)"
          >弃用 V{{ v.revision }}</n-button
        >
      </div>
    </article>
  </div>
</template>
