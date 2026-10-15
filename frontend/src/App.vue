<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from './api'
import { auth, clearSession, isLoggedIn, setUser, markChecked } from './store'

/**
 * 应用根组件。
 *
 * <p>只负责一件事：启动时确认登录态是否仍有效。
 * 页面布局在 {@code AppShell.vue}，登录页不套壳。
 */
const router = useRouter()

onMounted(async () => {
  if (!isLoggedIn()) {
    markChecked()
    return
  }
  try {
    setUser(await api.me())
  } catch {
    // token 失效（含后端重启导致的清空），直接登出
    clearSession()
    void router.replace({ name: 'login' })
  }
})
</script>

<template>
  <router-view />
</template>
