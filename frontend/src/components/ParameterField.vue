<script setup lang="ts">
import { NInput, NInputNumber, NSwitch } from 'naive-ui'
import type { Binding, Scalar } from '../api/client'
const props = defineProps<{ binding: Binding; modelValue: Scalar }>()
const emit = defineEmits<{ 'update:modelValue': [value: Scalar] }>()
</script>
<template>
  <label class="field"
    ><span>{{ binding.label }}</span>
    <n-input
      v-if="binding.type === 'STRING'"
      type="textarea"
      :autosize="{ minRows: 2, maxRows: 5 }"
      :maxlength="8000"
      :value="String(modelValue)"
      @update:value="emit('update:modelValue', $event)"
    />
    <n-switch
      v-else-if="binding.type === 'BOOLEAN'"
      :value="Boolean(modelValue)"
      @update:value="emit('update:modelValue', $event)"
    />
    <n-input-number
      v-else
      :value="Number(modelValue)"
      :min="binding.min ?? undefined"
      :max="binding.max ?? undefined"
      :precision="binding.type === 'INT' ? 0 : undefined"
      @update:value="(v) => emit('update:modelValue', v ?? Number(props.binding.default))"
    />
  </label>
</template>
