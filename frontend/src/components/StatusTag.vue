<script setup lang="ts">
import { statusText, type Model } from '../api/client'
defineProps<{ state: Model['Status'] }>()
</script>
<template>
  <span class="status-tag" :data-state="state"><i></i>{{ statusText[state] }}</span>
</template>
