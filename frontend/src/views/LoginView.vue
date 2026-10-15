<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { setSession } from '../store'
import { t } from '../i18n'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  if (!username.value.trim() || !password.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const res = await api.login(username.value.trim(), password.value)
    setSession(res.token, res.user)
    const redirect = (route.query.redirect as string) || '/projects'
    void router.replace(redirect)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    void submit()
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <span class="logo">⌘</span>
        <h1>{{ t('appTitle') }}</h1>
        <p class="subtitle">{{ t('loginSubtitle') }}</p>
      </div>

      <div v-if="error" class="notice error">{{ error }}</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label for="username">{{ t('username') }}</label>
          <input
            id="username"
            v-model="username"
            autocomplete="username"
            placeholder="admin"
            @keydown="onKeydown"
          />
        </div>

        <div class="field">
          <label for="password">{{ t('password') }}</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            @keydown="onKeydown"
          />
        </div>

        <button class="primary submit" type="submit" :disabled="busy || !username.trim() || !password">
          {{ busy ? t('signingIn') : t('signIn') }}
        </button>
      </form>

      <div class="hint">
        {{ t('defaultAccountHint') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(900px 500px at 20% -10%, rgba(79, 140, 255, 0.12), transparent),
    radial-gradient(700px 400px at 90% 110%, rgba(62, 207, 142, 0.08), transparent),
    var(--bg);
}

.login-card {
  width: min(380px, 100%);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 30px 28px;
}

.login-brand {
  text-align: center;
  margin-bottom: 22px;
}
.logo {
  font-size: 30px;
  color: var(--accent);
}
h1 {
  margin: 8px 0 4px;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
}

.field {
  margin-bottom: 14px;
}
.field label {
  display: block;
  margin-bottom: 5px;
  font-size: 12.5px;
  color: var(--text-dim);
}
.field input {
  width: 100%;
  padding: 9px 11px;
}

.submit {
  width: 100%;
  padding: 9px;
  margin-top: 4px;
}

.hint {
  margin-top: 16px;
  padding: 10px 12px;
  background: var(--bg-input);
  border-radius: var(--radius-sm);
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.7;
}
</style>
