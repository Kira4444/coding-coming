<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header-text">
      <h1>{{ title }}</h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 28px 18px;
  border-bottom: 1px solid var(--border);
}
h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
}
.page-subtitle {
  margin: 5px 0 0;
  color: var(--text-dim);
  font-size: 13px;
}
.page-header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
