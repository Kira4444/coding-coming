<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { auth, clearSession } from '../store'
import { t } from '../i18n'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)

const nav = computed(() => [
  { to: '/projects', label: t('projects'), icon: '📁', active: route.path.startsWith('/projects') },
  { to: '/sessions', label: t('sessions'), icon: '💬', active: route.path.startsWith('/sessions') },
  { to: '/settings', label: t('settings'), icon: '⚙', active: route.path.startsWith('/settings') },
])

const userName = computed(() => {
  const u = auth.user
  return u ? (u.displayName || u.username) : t('notLoggedIn')
})

async function logout() {
  try {
    await api.logout()
  } catch {
    // 登出失败也要清本地态，否则用户会卡在半登录状态
  }
  clearSession()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside class="nav">
      <div class="brand">
        <span class="logo">⌘</span>
        <span class="brand-name">{{ t('appTitle') }}</span>
      </div>

      <nav class="nav-list">
        <router-link
          v-for="item in nav"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: item.active }"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="nav-footer">
        <button class="user-btn" @click="menuOpen = !menuOpen">
          <span class="avatar">{{ userName.slice(0, 1).toUpperCase() }}</span>
          <span class="user-name">{{ userName }}</span>
        </button>
        <div v-if="menuOpen" class="user-menu">
          <router-link to="/settings" class="menu-item" @click="menuOpen = false">
            {{ t('settings') }}
          </router-link>
          <button class="menu-item" @click="logout">{{ t('logout') }}</button>
        </div>
      </div>
    </aside>

    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 220px 1fr;
  height: 100vh;
  overflow: hidden;
}

.nav {
  background: var(--bg-elevated);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 14px 10px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 10px 16px;
}
.logo {
  font-size: 19px;
  color: var(--accent);
}
.brand-name {
  font-weight: 600;
  font-size: 14.5px;
  letter-spacing: 0.2px;
}

.nav-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-dim);
  text-decoration: none;
  font-size: 13.5px;
}
.nav-item:hover {
  background: var(--bg-input);
  color: var(--text);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--text);
}
.nav-icon {
  width: 18px;
  text-align: center;
}

.nav-footer {
  position: relative;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.user-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 9px;
  border: none;
  background: transparent;
  border-radius: var(--radius-sm);
  text-align: left;
}
.user-btn:hover {
  background: var(--bg-input);
}
.avatar {
  width: 25px;
  height: 25px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.user-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.user-menu {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  background: var(--bg-input);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  padding: 4px;
  margin-bottom: 6px;
}
.menu-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 7px 10px;
  border: none;
  background: transparent;
  border-radius: var(--radius-sm);
  color: var(--text);
  text-decoration: none;
  font-size: 13px;
  cursor: pointer;
}
.menu-item:hover {
  background: var(--border);
}

.content {
  overflow: auto;
  min-width: 0;
}
</style>
