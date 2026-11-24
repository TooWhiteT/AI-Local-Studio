<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NSelect, useMessage } from 'naive-ui'
import { api, value, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
const props = defineProps<{ scene: Model['BoardScene']; editable: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const store = useStudio(),
  toast = useMessage(),
  actor = ref(''),
  revision = ref(0),
  busy = ref(false)
const options = computed(() => Object.entries(props.scene.cast).map(([value, label]) => ({ value, label })))
const empty = (): Model['Continuity'] => ({
  wardrobe: '',
  location: '',
  injuries: '',
  possessions: '',
  knowledge: '',
})
const fields = {
  wardrobe: '服装',
  location: '位置',
  injuries: '伤势',
  possessions: '持有物',
  knowledge: '已知信息',
}
const before = ref(empty()),
  after = ref(empty())
watch(
  () => props.scene.id,
  () => {
    actor.value = options.value[0]?.value ?? ''
  },
  { immediate: true },
)
function restore() {
  const current = props.scene.states.find((s) => s.characterKey === actor.value)
  before.value = { ...empty(), ...current?.before }
  after.value = { ...empty(), ...current?.after }
  revision.value = current?.revision ?? 0
}
watch([actor, () => props.scene.id], restore, { immediate: true })
async function save() {
  busy.value = true
  try {
    const state = value(
      await api.PUT('/scenes/{id}/characters/{key}/state', {
        params: { path: { id: props.scene.id, key: actor.value } },
        body: { expectedRevision: revision.value, before: before.value, after: after.value },
      }),
    )
    revision.value = state.revision
    emit('saved')
    toast.success('已保存场次人物状态')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <details class="continuity">
    <summary>场次人物状态</summary>
    <label class="field">出场角色<n-select v-model:value="actor" :options="options" /></label>
    <div class="field-pair"><strong>入场</strong><strong>出场</strong></div>
    <div v-for="(label, key) in fields" :key="key" class="field-pair">
      <label class="field"
        >{{ label
        }}<n-input
          v-model:value="before[key]"
          :aria-label="'入场' + label"
          maxlength="500"
          :disabled="!editable"
      /></label>
      <label class="field"
        >{{ label
        }}<n-input
          v-model:value="after[key]"
          :aria-label="'出场' + label"
          maxlength="500"
          :disabled="!editable"
      /></label>
    </div>
    <n-button v-if="editable" :loading="busy" @click="save">保存人物状态</n-button>
  </details>
</template>
