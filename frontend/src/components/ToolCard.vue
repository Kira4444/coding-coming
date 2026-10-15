<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../i18n'
import type { ToolCall } from '../types'

const props = defineProps<{
  toolCall: ToolCall
  status: 'pending' | 'running' | 'ok' | 'err'
  output?: string
  truncated?: boolean
  elapsedMs?: number
}>()

const open = ref(false)

const statusLabel = computed(() => {
  switch (props.status) {
    case 'ok':
      return t('done')
    case 'err':
      return t('failed')
    case 'running':
      return t('running')
    default:
      return '…'
  }
})

const statusClass = computed(() => {
  switch (props.status) {
    case 'ok':
      return 'ok'
    case 'err':
      return 'err'
    case 'running':
      return 'pending'
    default:
      return ''
  }
})

/** 参数是 JSON 字符串，格式化后展示；解析不了就原样给 */
const argsPretty = computed(() => {
  try {
    return JSON.stringify(JSON.parse(props.toolCall.arguments || '{}'), null, 2)
  } catch {
    return props.toolCall.arguments || ''
  }
})
</script>

<template>
  <div class="tool-card">
    <div class="tool-head" @click="open = !open">
      <span class="tool-name">{{ toolCall.name }}</span>
      <span class="tool-status" :class="statusClass">{{ statusLabel }}</span>
      <span v-if="truncated" class="tool-status pending">{{ t('truncated') }}</span>
      <span class="tool-elapsed" v-if="elapsedMs != null">{{ elapsedMs }}ms</span>
      <span class="tool-elapsed">{{ open ? t('hideDetail') : t('showDetail') }}</span>
    </div>

    <div v-if="open" class="tool-body">
      <pre class="tool-args">{{ argsPretty }}</pre>
      <pre v-if="output" class="tool-output">{{ output }}</pre>
    </div>
  </div>
</template>
