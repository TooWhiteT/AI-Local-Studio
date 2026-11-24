<script setup lang="ts">
import type { Model } from '../../api/client'
const props = defineProps<{ form: Model['StoryOutline'] }>()
const groups = [
  { key: 'characters', title: '人物小传', add: '添加角色', prefix: 'c' },
  { key: 'locations', title: '故事场景', add: '添加场景', prefix: 'l' },
] as const
function addEntity(group: (typeof groups)[number]) {
  const rows = props.form[group.key]
  let i = 1
  while (rows.some((r) => r.key === group.prefix + i)) i++
  rows.push({ key: group.prefix + i, name: '', description: '' })
}
</script>
<template>
  <label class="field">剧名<input v-model="form.title" maxlength="120" /></label>
  <label class="field">一句话梗概<textarea v-model="form.logline" rows="2" maxlength="600" /></label>
  <label class="field">世界观<textarea v-model="form.world" rows="3" maxlength="2400" /></label>
  <section v-for="group in groups" :key="group.key" class="writing-section">
    <div class="writing-section-head">
      <h3>{{ group.title }}</h3>
      <button type="button" class="text-link" @click="addEntity(group)">{{ group.add }}</button>
    </div>
    <div v-for="(person, i) in form[group.key]" :key="i" class="writing-block">
      <div class="writing-pair">
        <label class="field">名称<input v-model="person.name" maxlength="80" /></label>
        <label class="field">引用标识<input v-model="person.key" maxlength="32" /></label>
      </div>
      <label class="field">描述<textarea v-model="person.description" rows="2" maxlength="1000" /></label>
      <button type="button" class="text-link" @click="form[group.key].splice(i, 1)">
        移除{{ group.key === 'characters' ? '角色' : '场景' }}
      </button>
    </div>
  </section>
  <section class="writing-section">
    <div class="writing-section-head">
      <h3>情节节拍</h3>
      <button type="button" class="text-link" @click="form.beats.push('')">添加节拍</button>
    </div>
    <div v-for="(_, i) in form.beats" :key="i" class="writing-inline">
      <textarea v-model="form.beats[i]" :aria-label="'情节节拍 ' + (i + 1)" rows="2" />
      <button type="button" class="text-link" @click="form.beats.splice(i, 1)">移除</button>
    </div>
  </section>
</template>
