<template>
  <div class="auth-page">
    <div class="auth-card">
      <h2>登录 AniTrack</h2>
      <form class="auth-form" @submit.prevent="handleLogin">
        <!-- autocomplete 是给密码管理器用的: 没有它, 浏览器只能靠猜,
             常常把注册页填过的密码当成登录密码自动填进来 -->
        <label>用户名</label>
        <input v-model="form.username" type="text" placeholder="请输入用户名" autocomplete="username" required />
        <label>密码</label>
        <input v-model="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" required />
        <div v-if="error" class="auth-error">{{ error }}</div>
        <button class="auth-submit" type="submit" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
      <div class="auth-link">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { login } from '../api'
import { resolvePostAuthPath } from '../utils/loginRedirect'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const form = reactive({ username: '', password: '' })
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    const res = await login({ username: form.username, password: form.password })
    if (res.data.code === 200) {
      userStore.setUser(res.data.data)
      // 回到登录前那一页: URL 上的 redirect 优先, 没有就用路由记下的「刚才那一页」,
      // 都没有才回首页. 站内白名单校验与安全说明见 utils/loginRedirect.js
      router.push(resolvePostAuthPath(route.query.redirect))
    } else {
      error.value = res.data.message || '登录失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '网络错误，请稍后重试'
  }
  loading.value = false
}
</script>
