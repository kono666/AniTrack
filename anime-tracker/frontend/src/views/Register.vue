<template>
  <div class="auth-page">
    <div class="auth-card">
      <h2>✨ 注册 momo</h2>
      <form class="auth-form" @submit.prevent="handleRegister">
        <label>用户名</label>
        <input v-model="form.username" type="text" placeholder="3-50个字符" required minlength="3" maxlength="50" />
        <label>邮箱（选填）</label>
        <input v-model="form.email" type="email" placeholder="example@mail.com" />
        <label>密码</label>
        <input v-model="form.password" type="password" placeholder="至少6位密码" required minlength="6" />
        <label>确认密码</label>
        <input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" required />
        <div v-if="error" class="auth-error">{{ error }}</div>
        <button class="auth-submit" type="submit" :disabled="loading">
          {{ loading ? '注册中...' : '注册' }}
        </button>
      </form>
      <div class="auth-link">
        已有账号？<router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { register } from '../api'

const router = useRouter()
const userStore = useUserStore()
const form = reactive({ username: '', email: '', password: '', confirmPassword: '' })
const error = ref('')
const loading = ref(false)

async function handleRegister() {
  error.value = ''
  if (form.password !== form.confirmPassword) {
    error.value = '两次密码输入不一致'
    return
  }
  loading.value = true
  try {
    const res = await register({
      username: form.username,
      password: form.password,
      email: form.email || undefined
    })
    if (res.data.code === 200) {
      // data 中已包含 token
      userStore.setUser(res.data.data)
      router.push('/')
    } else {
      error.value = res.data.message || '注册失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '网络错误，请稍后重试'
  }
  loading.value = false
}
</script>
