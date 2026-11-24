<script setup lang="ts">
import type { Model } from '../../api/client'
defineProps<{ form: Model['EpisodePlan'] }>()
</script>
<template>
  <section v-for="(ep, i) in form.episodes" :key="i" class="writing-block">
    <div class="writing-section-head">
      <h3>第 {{ i + 1 }} 集</h3>
      <button type="button" class="text-link" @click="form.episodes.splice(i, 1)">移除本集</button>
    </div>
    <label class="field">分集标题<input v-model="ep.title" maxlength="120" /></label>
    <label class="field">分集梗概<textarea v-model="ep.summary" rows="3" /></label>
    <label class="field"
      >目标时长（秒）<input
        type="number"
        min="1"
        max="600"
        :value="ep.targetDurationMs / 1000"
        @input="ep.targetDurationMs = Math.round(Number(($event.target as HTMLInputElement).value) * 1000)"
    /></label>
  </section>
  <button
    type="button"
    class="text-link"
    @click="form.episodes.push({ title: '', summary: '', targetDurationMs: 60000 })"
  >
    添加一集
  </button>
</template>
