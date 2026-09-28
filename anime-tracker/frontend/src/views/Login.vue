<template>
  <div class="auth-page">
    <div class="auth-card">
      <h2>🔑 登录 momo</h2>
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

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const form = reactive({ username: '', password: '' })
const error = ref('')
const loading = ref(false)

/**
 * 把 ?redirect= 夹回站内路径.
 *
 * 为什么要夹: 这个参数是给「登录后回到刚才那一页」用的, 而它是 URL 里的东西,
 * 谁都能构造一条 /login?redirect=//evil.com 发出去. 这里**不能**只判断
 * "以 / 开头" —— 以 // 开头的是协议相对地址, 浏览器认它, history.pushState
 * 之后当前标签页就直接跳到 evil.com 了, 用户看到的是"点了登录, 结果到了一个
 * 陌生的网站", 而这一步发生在登录成功之后, 正是他最不会怀疑的时候.
 * 反斜杠那一版(/\evil.com)在部分浏览器里同样会被当成 // 处理, 一起挡掉.
 *
 * 不合规就回首页 —— 那本来就是登录后的默认落点, 比报错好.
 */
function safeRedirect(raw) {
  if (typeof raw !== 'string') return '/'
  if (!raw.startsWith('/')) return '/'
  if (raw.startsWith('//') || raw.startsWith('/\\')) return '/'
  return raw
}

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    const res = await login({ username: form.username, password: form.password })
    if (res.data.code === 200) {
      userStore.setUser(res.data.data)
      // 跳转到登录前访问的页面, 或首页(白名单校验见 safeRedirect)
      router.push(safeRedirect(route.query.redirect))
    } else {
      error.value = res.data.message || '登录失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '网络错误，请稍后重试'
  }
  loading.value = false
}
</script>
