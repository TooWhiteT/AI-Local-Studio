<script setup lang="ts">
defineProps<{ title: string; detail?: string }>()
</script>
<template>
  <div class="blank">
    <span class="slate" aria-hidden="true"></span>
    <h3>{{ title }}</h3>
    <p v-if="detail">{{ detail }}</p>
    <slot />
  </div>
</template>
