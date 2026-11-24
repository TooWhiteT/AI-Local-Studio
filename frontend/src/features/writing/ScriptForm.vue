<script setup lang="ts">
import type { Model } from '../../api/client'
const props = defineProps<{ form: Model['EpisodeScript']; outline?: Model['StoryOutline'] }>()
function addScene() {
  props.form.scenes.push({
    locationKey: props.outline?.locations[0]?.key ?? '',
    characters: [],
    action: '',
    durationMs: 10000,
    dialogues: [],
  })
}
</script>
<template>
  <label class="field">剧本标题<input v-model="form.title" maxlength="120" /></label>
  <section v-for="(scene, i) in form.scenes" :key="i" class="writing-block">
    <div class="writing-section-head">
      <h3>第 {{ i + 1 }} 场</h3>
      <button type="button" class="text-link" @click="form.scenes.splice(i, 1)">移除本场</button>
    </div>
    <div class="writing-pair">
      <label class="field"
        >地点<select v-model="scene.locationKey">
          <option v-for="loc in outline?.locations" :key="loc.key" :value="loc.key">{{ loc.name }}</option>
        </select></label
      >
      <label class="field"
        >场次时长（秒）<input
          type="number"
          min="1"
          :value="scene.durationMs / 1000"
          @input="scene.durationMs = Math.round(Number(($event.target as HTMLInputElement).value) * 1000)"
      /></label>
    </div>
    <div class="field">
      出场角色
      <div class="cast">
        <label v-for="person in outline?.characters" :key="person.key"
          ><input type="checkbox" :value="person.key" v-model="scene.characters" />{{ person.name }}</label
        >
      </div>
    </div>
    <label class="field">场次动作<textarea v-model="scene.action" rows="3" /></label>
    <div class="writing-section-head">
      <h4>对白</h4>
      <button
        type="button"
        class="text-link"
        @click="scene.dialogues.push({ speaker: scene.characters[0] ?? '', text: '', emotion: '' })"
      >
        添加对白
      </button>
    </div>
    <div v-for="(line, j) in scene.dialogues" :key="j" class="writing-line">
      <div class="writing-pair">
        <label class="field"
          >说话人<select v-model="line.speaker">
            <option
              v-for="person in outline?.characters.filter((c) => scene.characters.includes(c.key))"
              :key="person.key"
              :value="person.key"
            >
              {{ person.name }}
            </option>
          </select></label
        >
        <label class="field">情绪<input v-model="line.emotion" maxlength="120" /></label>
      </div>
      <label class="field">台词<textarea v-model="line.text" rows="2" /></label>
      <button type="button" class="text-link" @click="scene.dialogues.splice(j, 1)">移除对白</button>
    </div>
  </section>
  <button type="button" class="text-link" @click="addScene">添加一场</button>
</template>
