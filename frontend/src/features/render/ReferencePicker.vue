<script setup lang="ts">
import { mediaUrl, type Model } from '../../api/client'

defineProps<{
  options: Model['Reference'][]
  modelValue?: string
  editable: boolean
  frameSize?: Model['Manifest']['frameSize']
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template>
  <section class="panel">
    <div class="panel-title">
      <h2>镜头参考图</h2>
      <small>选择一个已审核的资产版本</small>
    </div>
    <p v-if="frameSize" class="inset render-empty">
      按 {{ frameSize.width }} × {{ frameSize.height }} 居中裁切，原图保留。
    </p>
    <p v-if="!options.length" class="inset render-empty">请在分镜工作台为此镜头绑定已审核的参考图。</p>
    <div v-else class="render-frames inset">
      <label v-for="r in options" :key="r.versionId" class="render-frame">
        <img :src="mediaUrl(r.file.id)" :alt="r.name + ' V' + r.revision" />
        <span
          ><input
            type="radio"
            name="render-reference"
            :checked="r.versionId === modelValue"
            :disabled="!editable"
            @change="emit('update:modelValue', r.versionId)"
          />
          {{ r.name }} · V{{ r.revision }}</span
        >
      </label>
    </div>
  </section>
</template>
