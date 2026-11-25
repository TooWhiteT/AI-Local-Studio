<script setup lang="ts">
import { computed } from 'vue'
import { NButton } from 'naive-ui'
import { mediaUrl } from '../../api/client'
import {
  displayTime,
  seconds,
  selectedAudio,
  shotLabel,
  validVideos,
  type Clip,
  type Source,
} from './timeline'

const props = defineProps<{ clip: Clip; source?: Source; editable: boolean }>()
const emit = defineEmits<{ changed: [clip: Clip] }>()
const video = computed(() => props.source?.videos.find((v) => v.id === props.clip.renderVersionId))
const choices = computed(() => (props.source ? selectedAudio(props.source) : []))
const approved = computed(() => (props.source ? validVideos(props.source) : []))
const audioById = (id: string) =>
  props.source?.audio.lines
    .flatMap((l) => l.takes.map((take) => ({ take, line: l })))
    .find((a) => a.take.id === id)
const fieldValue = (e: Event) => (e.currentTarget as HTMLInputElement).value
function patch(values: Partial<Clip>) {
  if (props.editable) emit('changed', { ...props.clip, ...values })
}
function switchVideo(id: string) {
  const chosen = approved.value.find((v) => v.id === id)
  if (chosen?.media.durationMs)
    patch({ renderVersionId: id, sourceInMs: 0, durationMs: chosen.media.durationMs })
}
function setDialogue(index: number, change: Partial<Clip['dialogues'][number]>) {
  patch({ dialogues: props.clip.dialogues.map((line, i) => (i === index ? { ...line, ...change } : line)) })
}
function addDialogue(id: string) {
  const selected = choices.value.find((a) => a.take.id === id)
  if (!selected || props.clip.dialogues.some((l) => l.takeId === id)) return
  const startMs = props.clip.dialogues.reduce(
    (end, l) => Math.max(end, l.startMs + (audioById(l.takeId)?.take.media.durationMs ?? 0)),
    0,
  )
  patch({
    dialogues: [...props.clip.dialogues, { takeId: id, startMs, gain: 1, subtitle: selected.line.text }],
  })
}
function setCaption(index: number, change: Partial<NonNullable<Clip['captions']>[number]>) {
  patch({ captions: (props.clip.captions ?? []).map((c, i) => (i === index ? { ...c, ...change } : c)) })
}
</script>
<template>
  <section class="panel clip-editor">
    <div class="panel-title">
      <h2>{{ source ? shotLabel(source.shot) : '历史片段' }}</h2>
      <small>时间以片段起点为准</small>
    </div>
    <div class="inset form-stack">
      <video
        v-if="video"
        :key="video.id"
        :src="mediaUrl(video.media.id)"
        controls
        playsinline
        preload="metadata"
        aria-label="原始镜头视频"
      />
      <fieldset class="edit-fields" :disabled="!editable">
        <label class="field"
          >镜头视频<select :value="clip.renderVersionId" @change="switchVideo(fieldValue($event))">
            <option v-if="!approved.some((v) => v.id === clip.renderVersionId)" :value="clip.renderVersionId">
              历史视频 {{ video ? 'V' + video.revision : clip.renderVersionId.slice(0, 8) }}
            </option>
            <option v-for="v in approved" :value="v.id" :key="v.id">
              视频 V{{ v.revision }} · {{ displayTime(v.media.durationMs ?? 0) }}
            </option>
          </select></label
        >
        <div class="post-fields">
          <label class="field"
            >素材入点（秒）<input
              type="number"
              min="0"
              step=".001"
              :value="seconds(clip.sourceInMs)"
              @input="patch({ sourceInMs: Math.round(Number(fieldValue($event)) * 1000) })"
          /></label>
          <label class="field"
            >片段时长（秒）<input
              type="number"
              min=".001"
              step=".001"
              :value="seconds(clip.durationMs)"
              @input="patch({ durationMs: Math.round(Number(fieldValue($event)) * 1000) })"
          /></label>
          <label class="field"
            >原声音量（%）<input
              type="number"
              min="0"
              max="200"
              step="1"
              :value="Math.round(clip.originalGain * 100)"
              @input="patch({ originalGain: Number(fieldValue($event)) / 100 })"
          /></label>
        </div>
        <h3>配音与对白字幕</h3>
        <label class="field"
          >加入已选用的配音<select
            :value="''"
            @change="
              addDialogue(fieldValue($event))
              ;($event.target as HTMLSelectElement).value = ''
            "
          >
            <option value="">选择录音</option>
            <option
              v-for="a in choices"
              :key="a.take.id"
              :value="a.take.id"
              :disabled="clip.dialogues.some((d) => d.takeId === a.take.id)"
            >
              {{ a.line.text }} · {{ displayTime(a.take.media.durationMs ?? 0) }}
            </option>
          </select></label
        >
        <article v-for="(d, i) in clip.dialogues" :key="d.takeId" class="post-item">
          <div class="post-row">
            <strong>{{ audioById(d.takeId)?.line.text ?? '历史配音' }}</strong
            ><n-button
              size="small"
              :disabled="!editable"
              @click="patch({ dialogues: clip.dialogues.filter((_, index) => index !== i) })"
              >移除配音</n-button
            >
          </div>
          <div class="post-fields">
            <label class="field"
              >配音起点（秒）<input
                type="number"
                min="0"
                step=".001"
                :value="seconds(d.startMs)"
                @input="setDialogue(i, { startMs: Math.round(Number(fieldValue($event)) * 1000) })"
            /></label>
            <label class="field"
              >配音音量（%）<input
                type="number"
                min="0"
                max="200"
                :value="Math.round(d.gain * 100)"
                @input="setDialogue(i, { gain: Number(fieldValue($event)) / 100 })"
            /></label>
          </div>
          <label class="field"
            >对白字幕<input
              :value="d.subtitle"
              maxlength="1000"
              placeholder="留空可保留纯配音"
              @input="setDialogue(i, { subtitle: fieldValue($event) })"
          /></label>
        </article>
        <div class="post-row">
          <h3>画面字幕</h3>
          <n-button
            size="small"
            :disabled="!editable"
            @click="
              patch({
                captions: [
                  ...(clip.captions ?? []),
                  { text: '', startMs: 0, durationMs: Math.min(2000, clip.durationMs) },
                ],
              })
            "
            >添加字幕</n-button
          >
        </div>
        <article v-for="(c, i) in clip.captions" :key="i" class="post-item">
          <label class="field"
            >字幕文字<input
              :value="c.text"
              maxlength="1000"
              @input="setCaption(i, { text: fieldValue($event) })"
          /></label>
          <div class="post-fields">
            <label class="field"
              >字幕起点（秒）<input
                type="number"
                min="0"
                step=".001"
                :value="seconds(c.startMs)"
                @input="setCaption(i, { startMs: Math.round(Number(fieldValue($event)) * 1000) })"
            /></label>
            <label class="field"
              >字幕时长（秒）<input
                type="number"
                min=".001"
                step=".001"
                :value="seconds(c.durationMs)"
                @input="setCaption(i, { durationMs: Math.round(Number(fieldValue($event)) * 1000) })"
            /></label>
          </div>
          <n-button
            size="small"
            :disabled="!editable"
            @click="patch({ captions: clip.captions!.filter((_, index) => index !== i) })"
            >移除字幕</n-button
          >
        </article>
      </fieldset>
    </div>
  </section>
</template>
