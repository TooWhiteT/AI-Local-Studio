<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { api, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'

const props = defineProps<{
  projectId: string
  roles: { key: string; name: string }[]
  bindings: Model['VoiceBinding'][]
  voices: { id: string; name: string }[]
  editable: boolean
}>()
const emit = defineEmits<{ changed: [] }>()
const choices = ref<Record<string, string>>({}),
  pending = ref(''),
  store = useStudio()
const binding = (key: string) => props.bindings.find((b) => b.characterKey === key)
watch(
  [() => props.projectId, () => props.bindings, () => props.voices, () => props.roles],
  (_, prev) => {
    const current: Record<string, string> = {}
    for (const role of props.roles) {
      const draft = props.projectId === prev?.[0] ? choices.value[role.key] : undefined
      current[role.key] =
        draft && props.voices.some((v) => v.id === draft) ? draft : (binding(role.key)?.voiceId ?? '')
    }
    choices.value = current
  },
  { immediate: true },
)
async function save(key: string) {
  if (pending.value || !choices.value[key]) return
  pending.value = key
  try {
    value(
      await api.PUT('/projects/{id}/voices/{characterKey}', {
        params: { path: { id: props.projectId, characterKey: key } },
        body: { expectedRevision: binding(key)?.revision ?? 0, voiceId: choices.value[key] },
      }),
    )
  } catch (e) {
    store.report(e)
  } finally {
    pending.value = ''
    emit('changed')
  }
}
</script>
<template>
  <section class="panel">
    <div class="panel-title">
      <h2>角色固定音色</h2>
      <small>同项目各集共用</small>
    </div>
    <p v-if="!roles.length" class="inset post-empty">本集尚无可分配音色的角色。</p>
    <div v-else class="inset voice-bindings">
      <div v-for="role in roles" :key="role.key" class="voice-binding">
        <div>
          <strong>{{ role.name }}</strong
          ><small>{{
            binding(role.key)
              ? binding(role.key)!.voiceName + ' · V' + binding(role.key)!.revision
              : '尚未绑定音色'
          }}</small>
        </div>
        <label class="field"
          ><select
            :aria-label="'为' + role.name + '选择音色'"
            v-model="choices[role.key]"
            :disabled="!editable || !voices.length"
          >
            <option value="">选择服务音色</option>
            <option v-for="v in voices" :key="v.id" :value="v.id">{{ v.name }}</option>
          </select></label
        >
        <n-button
          size="small"
          :disabled="
            !editable || !!pending || !choices[role.key] || choices[role.key] === binding(role.key)?.voiceId
          "
          @click="save(role.key)"
          >保存{{ role.name }}音色</n-button
        >
      </div>
    </div>
  </section>
</template>
