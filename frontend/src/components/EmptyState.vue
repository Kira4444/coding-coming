<script setup lang="ts">
defineProps<{
  icon?: string
  title: string
  hint?: string
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon">{{ icon ?? '⌘' }}</div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="hint" class="empty-hint">{{ hint }}</div>
    <div class="empty-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 72px 24px;
  text-align: center;
}
.empty-icon {
  font-size: 36px;
  opacity: 0.42;
  margin-bottom: 12px;
}
.empty-title {
  font-size: 15px;
  font-weight: 500;
}
.empty-hint {
  margin-top: 6px;
  color: var(--text-faint);
  font-size: 13px;
}
.empty-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
