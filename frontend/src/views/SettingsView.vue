<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { auth, clearSession } from '../store'
import type { SettingsView } from '../types'
import { t } from '../i18n'
import PageHeader from '../components/PageHeader.vue'

const router = useRouter()

const settings = ref<SettingsView | null>(null)
const loading = ref(true)
const error = ref('')

const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const pwBusy = ref(false)
const pwTip = ref('')
const pwError = ref('')

onMounted(async () => {
  try {
    settings.value = await api.getSettings()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

async function changePassword() {
  pwError.value = ''
  pwTip.value = ''
  if (pw.newPassword.length < 8) {
    pwError.value = t('passwordTooShort')
    return
  }
  if (pw.newPassword !== pw.confirm) {
    pwError.value = t('passwordMismatch')
    return
  }
  pwBusy.value = true
  try {
    await api.changePassword(pw.oldPassword, pw.newPassword)
    pwTip.value = t('passwordChanged')
    // 改密会让所有令牌失效，必须重新登录
    setTimeout(() => {
      clearSession()
      void router.replace({ name: 'login' })
    }, 1200)
  } catch (e) {
    pwError.value = (e as Error).message
  } finally {
    pwBusy.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader :title="t('settings')" />

    <div class="page-body">
      <div v-if="error" class="notice error">{{ error }}</div>
      <div v-if="loading" class="loading">{{ t('loading') }}</div>

      <template v-else>
        <!-- 账号 -->
        <section class="panel">
          <h2>{{ t('accountSettings') }}</h2>

          <div class="account-line">
            <span class="label">{{ t('username') }}</span>
            <span class="value">{{ auth.user?.username ?? '-' }}</span>
            <span class="pill">{{ auth.user?.role ?? '-' }}</span>
          </div>

          <div class="divider"></div>

          <h3>{{ t('changePassword') }}</h3>
          <div v-if="pwError" class="notice error">{{ pwError }}</div>
          <div v-if="pwTip" class="notice info">{{ pwTip }}</div>

          <div class="field">
            <label>{{ t('oldPassword') }}</label>
            <input v-model="pw.oldPassword" type="password" autocomplete="current-password" />
          </div>
          <div class="field">
            <label>{{ t('newPassword') }}</label>
            <input v-model="pw.newPassword" type="password" autocomplete="new-password" />
          </div>
          <div class="field">
            <label>{{ t('confirmPassword') }}</label>
            <input v-model="pw.confirm" type="password" autocomplete="new-password" />
          </div>
          <button
            class="primary"
            :disabled="pwBusy || !pw.oldPassword || !pw.newPassword"
            @click="changePassword"
          >
            {{ t('changePassword') }}
          </button>
        </section>

        <!-- 模型接入 -->
        <section class="panel">
          <h2>{{ t('modelProviders') }}</h2>
          <p class="hint">{{ t('modelProvidersHint') }}</p>

          <div v-if="!settings || settings.providers.length === 0" class="notice info">
            {{ t('noProviders') }}
          </div>

          <template v-else>
            <div class="provider-list">
              <div v-for="p in settings.providers" :key="p.id" class="provider">
                <div class="provider-head">
                  <span class="provider-id">{{ p.id }}</span>
                  <span class="pill" :class="{ ok: p.configured, warn: !p.configured }">
                    {{ p.configured ? '已配置' : '未配置 key' }}
                  </span>
                  <span v-if="p.id === settings.defaultProvider" class="pill accent">默认</span>
                </div>
                <div class="provider-rows">
                  <div class="row">
                    <span class="label">{{ t('baseUrl') }}</span>
                    <span class="value mono">{{ p.baseUrl }}</span>
                  </div>
                  <div class="row">
                    <span class="label">model</span>
                    <span class="value mono">{{ p.model }}</span>
                  </div>
                  <div class="row">
                    <span class="label">{{ t('apiKey') }}</span>
                    <span class="value mono">{{ p.apiKeyMasked }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="Object.keys(settings.taskOverrides).length > 0" class="overrides">
              <div class="label">{{ '按任务类型覆盖' }}</div>
              <div v-for="(v, k) in settings.taskOverrides" :key="k" class="row">
                <span class="label mono">{{ k }}</span>
                <span class="value mono">{{ v }}</span>
              </div>
            </div>
          </template>
        </section>

        <!-- 系统信息 -->
        <section class="panel">
          <h2>{{ t('systemInfo') }}</h2>
          <div class="info-grid">
            <div class="row">
              <span class="label">{{ t('sandboxDriver') }}</span>
              <span class="value mono">{{ settings?.sandboxDriver }}</span>
            </div>
            <div class="row">
              <span class="label">{{ t('sandboxImage') }}</span>
              <span class="value mono">{{ settings?.sandboxImage }}</span>
            </div>
            <div class="row">
              <span class="label">网络</span>
              <span class="value mono">{{ settings?.sandboxNetwork }}</span>
            </div>
            <div class="row">
              <span class="label">CPU / 内存</span>
              <span class="value mono">{{ settings?.sandboxCpu }} 核 / {{ settings?.sandboxMemory }}</span>
            </div>
            <div class="row">
              <span class="label">{{ t('maxConcurrent') }}</span>
              <span class="value mono">{{ settings?.maxConcurrentSessions }}</span>
            </div>
            <div class="row">
              <span class="label">{{ t('tokenBudget') }}</span>
              <span class="value mono">{{ settings?.tokenBudget?.toLocaleString() }}</span>
            </div>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.page-body {
  padding: 22px 28px 48px;
  max-width: 760px;
}
.loading {
  color: var(--text-faint);
}

.panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 18px 20px;
  margin-bottom: 16px;
}
.panel h2 {
  margin: 0 0 12px;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: var(--text-faint);
}
.panel h3 {
  margin: 4px 0 12px;
  font-size: 13.5px;
}
.hint {
  margin: -4px 0 14px;
  color: var(--text-faint);
  font-size: 12px;
  line-height: 1.6;
}

.account-line {
  display: flex;
  align-items: center;
  gap: 11px;
}
.divider {
  height: 1px;
  background: var(--border);
  margin: 16px 0;
}

.field {
  margin-bottom: 11px;
}
.field label {
  display: block;
  margin-bottom: 5px;
  font-size: 12.5px;
  color: var(--text-dim);
}
.field input {
  width: 100%;
}

.row {
  display: flex;
  gap: 12px;
  padding: 5px 0;
  font-size: 12.5px;
}
.row .label {
  color: var(--text-faint);
  min-width: 118px;
  flex-shrink: 0;
}
.row .value {
  color: var(--text);
  word-break: break-all;
}
.mono {
  font-family: var(--mono);
  font-size: 12px;
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.provider {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  background: var(--bg);
}
.provider-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
}
.provider-id {
  font-weight: 600;
  font-family: var(--mono);
  font-size: 13px;
}

.pill {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 20px;
  background: var(--bg-input);
  color: var(--text-dim);
}
.pill.ok {
  background: var(--success-soft);
  color: var(--success);
}
.pill.warn {
  background: var(--warning-soft);
  color: var(--warning);
}
.pill.accent {
  background: var(--accent-soft);
  color: var(--accent);
}

.overrides {
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px solid var(--border);
}

.info-grid {
  display: flex;
  flex-direction: column;
}
</style>
