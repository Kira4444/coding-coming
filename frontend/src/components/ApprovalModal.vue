<script setup lang="ts">
import { t } from '../i18n'

defineProps<{
  preview: string
  reason: string
  busy?: boolean
}>()

const emit = defineEmits<{
  approve: []
  reject: []
}>()
</script>

<template>
  <div class="approval">
    <h4>⚠ {{ t('approvalTitle') }}</h4>
    <div style="color: var(--text-dim); font-size: 12.5px">{{ reason }}</div>
    <pre class="preview">{{ preview }}</pre>
    <div class="approval-actions">
      <button class="primary" :disabled="busy" @click="emit('approve')">
        {{ t('approve') }}
      </button>
      <button class="danger" :disabled="busy" @click="emit('reject')">
        {{ t('reject') }}
      </button>
    </div>
  </div>
</template>
